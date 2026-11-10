package com.gan.spatialplayer.media

import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader

/** HDMV reuses 0x86 for DTS-HD. Broadcast TS uses that number for SCTE-35; keep them separate. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class BlurayTsExtractor private constructor(
    private val layout:()->M2tsPackets.Layout?,private val delegate:Extractor,
) : Extractor by delegate {
    constructor(layout:()->M2tsPackets.Layout?):this(layout,TsExtractor(
        TsExtractor.MODE_SINGLE_PMT, TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
        androidx.media3.extractor.text.SubtitleParser.Factory.UNSUPPORTED, TimestampAdjuster(0), Readers(layout),
        // UHD Blu-ray PCRs can be farther apart in bytes than the broadcast-oriented default.
        TsExtractor.TS_PACKET_SIZE * 6000,
    ))
    override fun init(output:ExtractorOutput) {
        delegate.init(object : ExtractorOutput by output {
            override fun track(id:Int,type:Int):TrackOutput {
                // Blu-ray's DV enhancement PID is not a second playable picture. On a 1080p phone
                // viewport-based selection otherwise chooses that layer instead of the 4K base.
                if(layout()?.dualLayerHevc==true && type==C.TRACK_TYPE_VIDEO && id==0x1015)return DiscardingTrackOutput()
                return output.track(id,type)
            }
        })
    }
    private class Readers(private val layout:()->M2tsPackets.Layout?):TsPayloadReader.Factory {
        private val normal=DefaultTsPayloadReaderFactory()
        private val bluray=DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
        override fun createInitialPayloadReaders():SparseArray<TsPayloadReader> = normal.createInitialPayloadReaders()
        override fun createPayloadReader(streamType:Int,esInfo:TsPayloadReader.EsInfo):TsPayloadReader? {
            if(layout()?.bluray!=true)return normal.createPayloadReader(streamType,esInfo)
            if(streamType==0x86) return androidx.media3.extractor.ts.PesReader(BlurayDtsReader(esInfo.language,esInfo.getRoleFlags()))
            return bluray.createPayloadReader(streamType,esInfo)
        }
    }
}
