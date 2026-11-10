package com.gan.spatialplayer.media

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DtsUtil
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.TsPayloadReader
import java.io.ByteArrayOutputStream

/** Join HDMV's separate core/extension PES packets into one lossless DTS-HD access unit. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class BlurayDtsReader(private val language:String?,private val roles:Int) : ElementaryStreamReader {
    private lateinit var output:TrackOutput
    private var formatId:String?=null
    private var format:Format?=null
    private val pes=ByteArrayOutputStream()
    private val accessUnit=ByteArrayOutputStream()
    private var pesTime=C.TIME_UNSET
    private var frameTime=C.TIME_UNSET
    override fun createTracks(extractorOutput:ExtractorOutput,idGenerator:TsPayloadReader.TrackIdGenerator) {
        idGenerator.generateNewId();formatId=idGenerator.formatId
        output=extractorOutput.track(idGenerator.trackId,C.TRACK_TYPE_AUDIO)
    }
    override fun seek() { pes.reset();accessUnit.reset();pesTime=C.TIME_UNSET;frameTime=C.TIME_UNSET }
    override fun packetStarted(pesTimeUs:Long,flags:Int) { pes.reset();pesTime=pesTimeUs }
    override fun consume(data:ParsableByteArray) {
        // Malformed/missing PES boundaries must not grow a buffer without limit.
        if(pes.size()+data.bytesLeft()>256*1024) { pes.reset();data.skipBytes(data.bytesLeft());return }
        val size=data.bytesLeft();pes.write(data.data,data.position,size);data.skipBytes(size)
    }
    override fun packetFinished(isEndOfInput: Boolean) {
        val bytes=pes.toByteArray()
        pes.reset()
        if(bytes.size<4) { if (isEndOfInput) emit(); return }
        val extension=bytes[0]==0x64.toByte() && bytes[1]==0x58.toByte() && bytes[2]==0x20.toByte() && bytes[3]==0x25.toByte()
        if(!extension) {
            emit()
            if(bytes.size<18)return
            val core=runCatching { DtsUtil.parseDtsFormat(bytes,formatId,language,roles,MimeTypes.VIDEO_MP2T,null) }.getOrNull() ?: return
            if (format == null) publish(core.buildUpon().setSampleMimeType(MimeTypes.AUDIO_DTS_HD).build())
            frameTime=pesTime
            accessUnit.write(bytes)
        } else if(accessUnit.size()>0) {
            // Stock DtsReader labels every extension as DTS Express and emits it separately.
            // That changes formats mid-frame and nextlib cannot construct the Express decoder.
            runCatching { DtsUtil.parseDtsHdHeader(bytes) }.getOrNull()?.let { hd ->
                val current=format
                if(current!=null && hd.channelCount>0 && hd.sampleRate>0) {
                    publish(current.buildUpon().setChannelCount(hd.channelCount).setSampleRate(hd.sampleRate).build())
                }
            }
            if(accessUnit.size()+bytes.size<=256*1024)accessUnit.write(bytes)
            emit()
        }
        if (isEndOfInput) emit()
    }
    private fun publish(value:Format) {
        if(value.sampleRate==format?.sampleRate && value.channelCount==format?.channelCount)return
        format=value;output.format(value)
    }
    private fun emit() {
        if(accessUnit.size()==0)return
        if(frameTime!=C.TIME_UNSET && format!=null) {
            val bytes=accessUnit.toByteArray()
            output.sampleData(ParsableByteArray(bytes),bytes.size)
            output.sampleMetadata(frameTime,C.BUFFER_FLAG_KEY_FRAME,bytes.size,0,null)
        }
        accessUnit.reset()
    }
}
