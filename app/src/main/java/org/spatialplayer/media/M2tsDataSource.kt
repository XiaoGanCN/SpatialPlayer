package org.spatialplayer.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.ConcurrentHashMap

/** Normalize only TS candidates, without remuxing, transcoding, or copying the original file. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class M2tsDataSource(private val upstream: DataSource, private val factory: Factory) : DataSource {
    class Factory(private val upstream:DataSource.Factory,private val candidate:(Uri)->Boolean) : DataSource.Factory {
        private val layouts=ConcurrentHashMap<Uri,M2tsPackets.Layout>()
        override fun createDataSource():DataSource = M2tsDataSource(upstream.createDataSource(),this)
        fun layout(uri:Uri?) = uri?.let { layouts[it] }
        fun inspect(source:DataSource,spec:DataSpec):M2tsPackets.Layout {
            layouts[spec.uri]?.let { return it }
            if (!candidate(spec.uri)) return M2tsPackets.Layout(false)
            val header=ByteArray(192*32)
            var size=0
            try {
                source.open(spec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build())
                while(size<header.size) { val n=source.read(header,size,header.size-size);if(n<=0)break;size+=n }
            } finally { source.close() }
            return M2tsPackets.inspect(header,size).also { layouts[spec.uri]=it }
        }
    }
    private var reader:M2tsPackets.Reader?=null
    private var remaining=C.LENGTH_UNSET.toLong()
    override fun open(dataSpec:DataSpec):Long {
        val layout=factory.inspect(upstream,dataSpec)
        if(!layout.m2ts) { reader=null;return upstream.open(dataSpec) }
        val offset=(dataSpec.position%188).toInt()
        val raw=dataSpec.buildUpon().setPosition(M2tsPackets.rawPosition(dataSpec.position)).setLength(C.LENGTH_UNSET.toLong()).build()
        val length=upstream.open(raw)
        val available=if(length==C.LENGTH_UNSET.toLong())C.LENGTH_UNSET.toLong() else (M2tsPackets.virtualLength(length)-offset).coerceAtLeast(0)
        remaining=if(dataSpec.length==C.LENGTH_UNSET.toLong())available else if(available<0)dataSpec.length else minOf(available,dataSpec.length)
        reader=M2tsPackets.Reader({b,o,n->upstream.read(b,o,n)},offset)
        return remaining
    }
    override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
        val normalized=reader ?: return upstream.read(buffer,offset,length)
        if(length==0)return 0
        if(remaining==0L)return C.RESULT_END_OF_INPUT
        val n=normalized.read(buffer,offset,if(remaining<0)length else minOf(length.toLong(),remaining).toInt())
        if(n>0 && remaining>0)remaining-=n
        return n
    }
    override fun close() { reader=null;upstream.close() }
    override fun getUri():Uri?=upstream.uri
    override fun getResponseHeaders():Map<String,List<String>> = upstream.responseHeaders
    override fun addTransferListener(transferListener:TransferListener)=upstream.addTransferListener(transferListener)
}
