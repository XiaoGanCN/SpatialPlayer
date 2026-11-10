import android.graphics.Rect
import com.gan.spatialplayer.media.MatroskaChapters
import com.gan.spatialplayer.media.M2tsPackets
import com.gan.spatialplayer.media.UpmixMatrix
import com.gan.spatialplayer.ui.VideoRectCalculator as Geometry
import java.io.ByteArrayInputStream
import java.nio.file.Files

private var checks = 0
private fun verify(name: String, condition: Boolean) {
    check(condition) { name }
    checks++
}

// Small, valid EBML fixture: an edition with a named chapter starting at one second.
private fun element(id: String, payload: ByteArray): ByteArray {
    check(payload.size < 127)
    return id.chunked(2).map { it.toInt(16).toByte() }.toByteArray() +
        byteArrayOf((0x80 or payload.size).toByte()) + payload
}

fun main() {
    fun rect(mode: Int, rotation: Int = 0, zoom: Float = 1f, par: Float = 1f) =
        Geometry.compute(1000, 1000, 1920, 1080, par, rotation, mode, zoom)
    verify("fit preserves aspect and centers", rect(Geometry.SCALE_FIT) == Rect(0, 219, 1000, 781))
    verify("fill covers the container", rect(Geometry.SCALE_FILL) == Rect(-389, 0, 1389, 1000))
    verify("zoom at unity equals fill", rect(Geometry.SCALE_ZOOM) == rect(Geometry.SCALE_FILL))
    val zoomed = rect(Geometry.SCALE_ZOOM, zoom = 2f)
    verify("zoom enlarges both axes", zoomed.top == -500 && zoomed.bottom == 1500 && zoomed.left < -389)
    verify("stretch uses container bounds", rect(Geometry.SCALE_STRETCH) == Rect(0, 0, 1000, 1000))
    verify("rotation swaps video axes", rect(Geometry.SCALE_FIT, rotation = 90) == Rect(219, 0, 781, 1000))
    verify("invalid pixel aspect defaults to square", rect(Geometry.SCALE_FIT, par = 0f) == rect(Geometry.SCALE_FIT))
    verify("empty viewport", Geometry.compute(0, 500, 1920, 1080, 1f, 0, 0, 1f) == Rect())
    verify("unknown video fills viewport", Geometry.compute(500, 700, 0, 0, 1f, 0, 0, 1f) == Rect(0, 0, 500, 700))

    val matrix = UpmixMatrix.DEFAULT.withChannel(4, -75, 25).copy(subwooferLowPass = false)
    verify("matrix preferences round trip", UpmixMatrix.decode(matrix.encode()) == matrix)
    verify("missing preference defaults", UpmixMatrix.decode(null) == UpmixMatrix.DEFAULT)
    verify("truncated preference defaults", UpmixMatrix.decode("100,0") == UpmixMatrix.DEFAULT)
    verify("matrix gains clamp", matrix.withChannel(0, -200, 200).gains[0] == (-100 to 100))
    verify("invalid channel leaves matrix intact", matrix.withChannel(6, 0, 0) == matrix)
    verify("rear channels retain opposite polarity", UpmixMatrix.DEFAULT.gains[4] == (100 to -100) && UpmixMatrix.DEFAULT.gains[5] == (-100 to 100))

    val display = element("80", element("85", "Opening".toByteArray()) + element("437C", "eng".toByteArray()))
    val start = element("91", byteArrayOf(0x3b, 0x9a.toByte(), 0xca.toByte(), 0))
    val chapter = element("B6", start + display)
    val fixture = element("18538067", element("1043A770", element("45B9", chapter)))
    val chapters = MatroskaChapters.read(ByteArrayInputStream(fixture))
    verify("chapter timestamp", chapters.single().startMs == 1000L)
    verify("chapter title and language", chapters.single().title == "Opening" && chapters.single().language == "eng")
    verify("chapter depth and optional end", chapters.single().depth == 0 && chapters.single().endMs == null)
    val file = Files.createTempFile("spatial-chapters-", ".mkv").toFile()
    try {
        file.writeBytes(fixture)
        verify("file and stream parser agree", MatroskaChapters.read(file) == chapters)
    } finally { file.delete() }
    verify("invalid input is harmless", MatroskaChapters.read(ByteArrayInputStream(byteArrayOf(1, 2, 3))).isEmpty())
    verify("empty input is harmless", MatroskaChapters.read(ByteArrayInputStream(byteArrayOf())).isEmpty())
    val payload = ByteArray(188*17) { (it*17).toByte() }
    val packets = (0 until 17).flatMap { index ->
        (byteArrayOf(1,2,3,4) + payload.copyOfRange(index*188,(index+1)*188)).toList()
    }.toByteArray()
    for (fragment in listOf(1,7,191,192,4096)) {
        val stream=ByteArrayInputStream(packets)
        val reader=M2tsPackets.Reader({b,o,n->stream.read(b,o,minOf(n,fragment))})
        val result=java.io.ByteArrayOutputStream()
        val chunk=ByteArray(333)
        while(true) { val n=reader.read(chunk,0,chunk.size);if(n<0)break;result.write(chunk,0,n) }
        verify("M2TS strips timestamps with $fragment-byte upstream reads",result.toByteArray().contentEquals(payload))
    }
    for (seek in listOf(0,1,187,188,189,1901,3195)) {
        val stream=ByteArrayInputStream(packets).apply { skip(M2tsPackets.rawPosition(seek.toLong())) }
        val reader=M2tsPackets.Reader({b,o,n->stream.read(b,o,n)},seek%188)
        val actual=ByteArray(payload.size-seek)
        verify("M2TS seek $seek retains exact payload bytes",reader.read(actual,0,actual.size)==actual.size && actual.contentEquals(payload.copyOfRange(seek,payload.size)))
    }
    verify("M2TS offsets stay 64-bit for an 80 GiB movie",M2tsPackets.virtualLength(192L*500_000_000)==188L*500_000_000 && M2tsPackets.rawPosition(188L*500_000_000)==192L*500_000_000)
    verify("M2TS virtual length",M2tsPackets.virtualLength(packets.size.toLong())==payload.size.toLong())
    verify("ordinary TS is not normalized",!M2tsPackets.inspect(ByteArray(192*3),192*3).m2ts)
    println("PASS: $checks host checks (geometry, matrix preferences, Matroska chapters, M2TS packets/seeks)")
}
