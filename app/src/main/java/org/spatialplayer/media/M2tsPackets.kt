package org.spatialplayer.media

/** 192-byte Blu-ray packets expose a virtual, seekable stream of ordinary 188-byte TS packets. */
internal object M2tsPackets {
    data class Layout(val m2ts: Boolean, val bluray: Boolean = false, val dualLayerHevc: Boolean = false)
    fun inspect(bytes: ByteArray, count: Int): Layout {
        if (count < 192 * 3 || (0 until 3).any { bytes[4 + it*192] != 0x47.toByte() }) return Layout(false)
        var hdmv = false
        var primary = false
        var enhancement = false
        for (start in 0 until count-191 step 192) {
            val p=start+4
            if (bytes[p+1].toInt() and 0x40 == 0) continue
            var pos=p+4
            if (bytes[p+3].toInt() and 0x20 != 0) pos+=1+(bytes[p+4].toInt() and 255)
            if (pos>=p+188) continue
            pos+=1+(bytes[pos].toInt() and 255)
            if (pos+12>=p+188 || bytes[pos].toInt() != 2) continue
            val length=((bytes[pos+10].toInt() and 15) shl 8) or (bytes[pos+11].toInt() and 255)
            val end=pos+12+length
            if (end>p+188) continue
            for (i in pos+12 until end-3) if (bytes[i]==72.toByte() && bytes[i+1]==68.toByte() && bytes[i+2]==77.toByte() && bytes[i+3]==86.toByte()) hdmv=true
            var entry=end
            while (entry+5<=p+188) {
                val type=bytes[entry].toInt() and 255
                val pid=((bytes[entry+1].toInt() and 31) shl 8) or (bytes[entry+2].toInt() and 255)
                val n=((bytes[entry+3].toInt() and 15) shl 8) or (bytes[entry+4].toInt() and 255)
                if (type==0x24 && pid==0x1011) primary=true
                if (type==0x24 && pid==0x1015) enhancement=true
                entry+=5+n
            }
        }
        return Layout(true,hdmv,hdmv && primary && enhancement)
    }
    fun rawPosition(virtual: Long): Long = virtual/188*192
    fun virtualLength(raw: Long): Long = raw/192*188 + (raw%192-4).coerceAtLeast(0)

    class Reader(private val readRaw: (ByteArray,Int,Int)->Int, offset: Int = 0) {
        private val buffer=ByteArray(192*256)
        private var position=0
        private var limit=0
        private var skip=4+offset
        private var payload=188-offset
        fun read(target:ByteArray,offset:Int,length:Int):Int {
            if(length==0)return 0
            var written=0
            while(written<length) {
                if(position==limit) {
                    limit=readRaw(buffer,0,buffer.size);position=0
                    if(limit<0)return if(written==0)-1 else written
                    if(limit==0)return written
                }
                if(skip>0) { val n=minOf(skip,limit-position);position+=n;skip-=n;continue }
                val n=minOf(payload,limit-position,length-written)
                buffer.copyInto(target,offset+written,position,position+n)
                position+=n;written+=n;payload-=n
                if(payload==0) { skip=4;payload=188 }
            }
            return written
        }
    }
}
