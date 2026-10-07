package com.gan.spatialplayer.media

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * A single chapter marker, flattened out of the container's nesting.
 *
 * [depth] survives flattening because the nesting cannot be recovered afterwards, and a UI may well
 * want to indent a bonus-feature chapter beneath the feature it belongs to.
 */
data class Chapter(
    /** Display title, or null when the chapter carries no usable string. */
    val title: String?,

    /** Start offset in milliseconds. Clamped, never negative. */
    val startMs: Long,

    /**
     * End offset in milliseconds, or null when the container declares none.
     *
     * Not inferred from the next chapter's start: the final chapter has no successor to infer from,
     * and a caller that wants contiguous ranges is better placed to close the gaps itself.
     */
    val endMs: Long?,

    /** Language tag exactly as stored, for example `eng`; null when the display omits one. */
    val language: String?,

    /** 0 for a top-level chapter, 1 for a chapter nested inside one, and so on. */
    val depth: Int,
)

/**
 * Reads Matroska chapter markers without dragging in a demuxer.
 *
 * Media3 1.8 exposes no chapter API, and the alternatives are heavy: Media3's own `MkvParser` keeps
 * its chapter handling private, and libmatroska means JNI. Chapters are a flat list of timestamps,
 * so the whole job is a few hundred lines of EBML walking, done lazily enough to sit in front of a
 * 40 GB remux.
 *
 * ## The marker bit, and why it catches people out
 *
 * EBML variable-length integers come in two flavours that look identical on the wire but decode
 * differently. An **element ID** keeps its marker bit, the leading `1` that announces how many
 * bytes follow, so `Segment` is matched as `0x18538067` rather than the bit-stripped remainder. An
 * **element size** drops that bit, so the single byte `0x84` means 4, not 132. Swap the two rules
 * and the parser still runs, which is what makes it dangerous: IDs match nothing, and sizes are
 * read as roughly 32x too large so the reader steps over the very element it was looking for. The
 * two decoders are kept as separate functions here, and neither is used for the other's job.
 *
 * ## What is skipped, and why
 *
 * A cluster holds the media data itself and there is one every couple of seconds, so a feature has
 * tens of thousands of them; the reader steps over a cluster's body by its declared size and never
 * looks inside. The same treatment goes to `SeekHead`, `Info`, `Tracks`, `Cues`, `Tags` and
 * `Attachments`. `SeekHead` exists precisely to avoid a linear scan like this one, but a scan that
 * only skips headers costs a handful of seeks, which is cheaper than following the index.
 *
 * Everything here is defensive by design: a truncated file, a hostile size field, a random non-EBML
 * file or a failing stream all yield whatever was decoded before the failure. Chapters are a
 * convenience and should never be the reason a file refuses to play.
 */
object MatroskaChapters {

    /**
     * Reads the chapters of [file], or an empty list if there are none or the file is not Matroska.
     *
     * A [RandomAccessFile] rather than a stream, because stepping over the cluster region should
     * cost a seek and not a read. Never throws.
     */
    fun read(file: File): List<Chapter> {
        return try {
            RandomAccessFile(file, "r").use { handle -> Parser(FileSource(handle)).parse() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Reads chapters from an already-open stream, for callers holding a pipe or a `content://`
     * handle.
     *
     * The stream is left open, since the caller owns it. Never throws.
     */
    fun read(input: InputStream): List<Chapter> = Parser(StreamSource(input)).parse()

    /** An element header together with the end of the body that follows it. */
    private class Element(
        val id: Long,
        val size: Long,
        val bodyEnd: Long,
    )

    /** One `ChapterDisplay`, which may carry a title, a language, both or neither. */
    private class Display(val title: String?, val language: String?)

    private class Parser(private val source: Source) {

        /** End of the whole file, or [Long.MAX_VALUE] when the source cannot report a length. */
        private val fileEnd = source.length() ?: Long.MAX_VALUE

        /** An element already read while walking for the end of an unknown-size parent. */
        private var pending: Element? = null

        fun parse(): List<Chapter> {
            val chapters = ArrayList<Chapter>()
            try {
                collect(chapters)
            } catch (_: Exception) {
                // Truncated, hostile or merely unexpected input: keep whatever was decoded before
                // the failure rather than propagating it to a caller that only wanted a menu.
            }
            return chapters
        }

        private fun collect(out: MutableList<Chapter>) {
            val first = nextElement(fileEnd) ?: return
            when (first.id) {
                ID_EBML -> if (!skipEbmlHeader(first)) return
                // Headerless files turn up after sloppy remuxing, so a leading Segment is accepted
                // as if the header had been read and skipped.
                ID_SEGMENT -> pending = first
                else -> return
            }

            while (true) {
                val element = nextElement(fileEnd) ?: return
                when (element.id) {
                    ID_SEGMENT -> readSegment(element, out)
                    // Chapters belong inside a Segment. Outside one the file is malformed, but
                    // reading them anyway costs nothing.
                    ID_CHAPTERS -> readChapters(element, out, 0)
                    else -> if (!skipBody(element, 0)) return
                }
            }
        }

        private fun readSegment(segment: Element, out: MutableList<Chapter>) {
            val limit = segment.bodyEnd
            while (true) {
                val child = nextElement(limit) ?: return
                when (child.id) {
                    ID_CHAPTERS -> readChapters(child, out, 0)
                    // Clusters carry the media, and SeekHead, Info, Tracks, Cues, Tags and
                    // Attachments are irrelevant here. Stepping over a cluster by its declared size
                    // is the whole reason this reader copes with a feature-length file.
                    else -> if (!skipBody(child, 1)) return
                }
            }
        }

        private fun readChapters(chapters: Element, out: MutableList<Chapter>, guard: Int) {
            if (guard > MAX_DEPTH) return
            val limit = chapters.bodyEnd
            while (true) {
                val child = nextElement(limit) ?: return
                when (child.id) {
                    ID_EDITION_ENTRY -> readEdition(child, out, guard + 1)
                    else -> if (!skipBody(child, guard + 1)) return
                }
            }
        }

        private fun readEdition(edition: Element, out: MutableList<Chapter>, guard: Int) {
            if (guard > MAX_DEPTH) return
            val limit = edition.bodyEnd
            while (true) {
                val child = nextElement(limit) ?: return
                when (child.id) {
                    // Ordered and unordered editions both flatten the same way; the flag that
                    // distinguishes them changes nothing about where the chapters are.
                    ID_CHAPTER_ATOM -> readAtom(child, out, depth = 0, guard = guard + 1)
                    else -> if (!skipBody(child, guard + 1)) return
                }
            }
        }

        /**
         * Reads one `ChapterAtom`, appending it and then its descendants.
         *
         * The atom itself is appended only once its body has been scanned, because its time and
         * display elements may follow a nested atom in the document. Descendants go into a local
         * list first so the whole walk stays a single forward pass, which is the only kind of pass a
         * non-seekable stream allows.
         */
        private fun readAtom(atom: Element, out: MutableList<Chapter>, depth: Int, guard: Int) {
            if (guard > MAX_DEPTH || out.size >= MAX_CHAPTERS) {
                skipBody(atom, guard)
                return
            }

            val descendants = ArrayList<Chapter>()
            var startNanos: Long? = null
            var endNanos: Long? = null
            var title: String? = null
            var language: String? = null
            val limit = atom.bodyEnd

            while (true) {
                val child = nextElement(limit) ?: break
                when (child.id) {
                    // A time cut off by the end of the input ends this atom's scan but leaves the
                    // fields already read intact, so a half-present chapter still appears.
                    ID_CHAPTER_TIME_START -> startNanos = readUnsigned(child.size) ?: break
                    ID_CHAPTER_TIME_END -> endNanos = readUnsigned(child.size) ?: break
                    ID_CHAPTER_DISPLAY -> {
                        val display = readDisplay(child, guard + 1)
                        // A display may repeat once per language, and muxers write the original
                        // language first, so the first non-empty value of each field wins.
                        if (title == null) title = display.title
                        if (language == null) language = display.language
                    }
                    ID_CHAPTER_ATOM -> readAtom(child, descendants, depth + 1, guard + 1)
                    else -> if (!skipBody(child, guard + 1)) break
                }
            }

            if (out.size >= MAX_CHAPTERS) return
            out += Chapter(
                title = title,
                startMs = toMillis(startNanos),
                endMs = endNanos?.let { toMillis(it) },
                language = language,
                depth = depth,
            )
            out += descendants
        }

        private fun readDisplay(display: Element, guard: Int): Display {
            var title: String? = null
            var language: String? = null
            val limit = display.bodyEnd
            while (true) {
                val child = nextElement(limit) ?: break
                when (child.id) {
                    ID_CHAP_STRING -> if (title == null) {
                        title = readText(child.size, Charsets.UTF_8)
                    }
                    ID_CHAP_LANGUAGE -> if (language == null) {
                        language = readText(child.size, Charsets.US_ASCII)
                    }
                    else -> if (!skipBody(child, guard + 1)) break
                }
            }
            return Display(title, language)
        }

        /**
         * Reads the next element header, or null at end of input, on a malformed header, or when
         * the declared body runs past [limit].
         *
         * A body wider than what is left is a corrupt size field or a truncated download, and the
         * two want opposite treatment. A container is clamped to what is actually present and
         * walked anyway, because chapters sit at the front of the file and survive a download that
         * was cut off in the middle of the media. A leaf or a cluster is dropped, since reading a
         * meaningless run of bytes as a timestamp, or scanning the rest of the file for the end of
         * a cluster that has none, helps nobody.
         */
        private fun nextElement(limit: Long): Element? {
            pending?.let { element ->
                pending = null
                return element
            }
            if (source.position >= limit) return null

            val id = readId()
            if (id == NO_VALUE) return null
            val size = readSize()
            if (size == NO_VALUE) return null

            val bodyStart = source.position
            if (size != UNKNOWN_SIZE && size > limit - bodyStart) {
                if (!isContainer(id)) return null
                return Element(id, size, limit)
            }
            val bodyEnd = if (size == UNKNOWN_SIZE) limit else bodyStart + size
            return Element(id, size, bodyEnd)
        }

        /**
         * Reads an element ID, marker bit and all.
         *
         * The leading `1` of the first byte announces the length and stays in the value, which is
         * why `0x18538067` is compared as written. Stripping it here produces IDs that match no
         * known element, and the parser then walks the file as a pile of unknown elements.
         */
        private fun readId(): Long {
            val first = source.read()
            if (first <= 0) return NO_VALUE

            val length = Integer.numberOfLeadingZeros(first) - 23
            // Matroska caps IDs at four bytes; anything longer is a length byte read out of a
            // field that is not an ID at all.
            if (length > MAX_ID_BYTES) return NO_VALUE

            var value = first.toLong()
            repeat(length - 1) {
                val next = source.read()
                if (next < 0) return NO_VALUE
                value = value shl 8 or next.toLong()
            }
            return value
        }

        /**
         * Reads an element size, with the marker bit removed.
         *
         * A payload of all ones is not a length but the "unknown" marker a live muxer writes when it
         * cannot know the length up front, so it comes back as [UNKNOWN_SIZE] and the caller reads
         * children until the parent's declared end.
         */
        private fun readSize(): Long {
            val first = source.read()
            if (first <= 0) return NO_VALUE

            val length = Integer.numberOfLeadingZeros(first) - 23
            // `0xFF ushr length` keeps the payload bits below the marker and clears the rest.
            var value = (first and (0xFF ushr length)).toLong()
            repeat(length - 1) {
                val next = source.read()
                if (next < 0) return NO_VALUE
                value = value shl 8 or next.toLong()
            }

            val unknown = (1L shl (7 * length)) - 1
            return if (value == unknown) UNKNOWN_SIZE else value
        }

        /**
         * Reads an unsigned integer of [size] bytes, saturating instead of wrapping.
         *
         * Kotlin has no unsigned `Long`, so an eight-byte field with its top bit set would arrive
         * negative and read as a chapter before the start of the film. Saturating gives [toMillis]
         * something it can clamp. A field that ends at end of input yields null rather than the
         * bytes that happened to arrive, which is the same answer the seekable path reaches when it
         * sees a body running past the end of the file.
         */
        private fun readUnsigned(size: Long): Long? {
            if (size <= 0) return 0L
            if (size > 8) {
                // Wider than any timestamp can be, so the bytes are dropped rather than decoded.
                skipFully(size)
                return Long.MAX_VALUE
            }

            var value = 0L
            var consumed = 0L
            while (consumed < size) {
                val next = source.read()
                if (next < 0) return null
                value = value shl 8 or next.toLong()
                consumed++
            }
            return if (value < 0) Long.MAX_VALUE else value
        }

        /**
         * Reads a text element, refusing sizes that cannot be text.
         *
         * A title is a few dozen bytes. Anything past [MAX_TEXT_BYTES] is a corrupt size field, and
         * allocating for it would be the one place a hostile file could exhaust memory. A field cut
         * off by the end of the input is reported as absent rather than shown half-spelled.
         */
        private fun readText(size: Long, charset: Charset): String? {
            if (size <= 0) return null
            if (size > MAX_TEXT_BYTES) {
                skipFully(size)
                return null
            }

            val bytes = ByteArray(size.toInt())
            var filled = 0
            while (filled < bytes.size) {
                val count = source.read(bytes, filled, bytes.size - filled)
                if (count <= 0) break
                filled += count
            }
            if (filled < bytes.size) return null
            return String(bytes, 0, filled, charset).trim().takeIf { it.isNotEmpty() }
        }

        /**
         * Steps over the EBML header so the Segment can be found.
         *
         * A sized header is one skip. An unsized one has to be walked field by field: taking the
         * rest of the file as its body would swallow the Segment, and with it every chapter.
         */
        private fun skipEbmlHeader(header: Element): Boolean {
            if (header.size != UNKNOWN_SIZE) return skipBody(header, 0)
            while (source.position < header.bodyEnd) {
                val field = nextElement(header.bodyEnd) ?: return false
                if (!isEbmlHeaderField(field.id)) {
                    // The header is over; the element just read belongs to the caller.
                    pending = field
                    return true
                }
                if (!skipBody(field, 1)) return false
            }
            return true
        }

        /**
         * Advances past an element's body.
         *
         * An unknown size means the element ends where the next element at its parent's level
         * begins, which is the one case where a body must be walked rather than skipped. Only
         * headers are read on that walk, so media data is still never touched.
         */
        private fun skipBody(element: Element, guard: Int): Boolean {
            if (element.size != UNKNOWN_SIZE) {
                return skipFully(element.bodyEnd - source.position)
            }
            if (guard > MAX_DEPTH) return false

            while (source.position < element.bodyEnd) {
                val child = nextElement(element.bodyEnd) ?: return false
                // An ID belonging to the parent's level marks the end of the unsized element, so it
                // is handed back for the caller to deal with instead of being swallowed here.
                if (isSegmentLevel(child.id)) {
                    pending = child
                    return true
                }
                if (!skipBody(child, guard + 1)) return false
            }
            return true
        }

        private fun skipFully(count: Long): Boolean {
            var remaining = count
            while (remaining > 0) {
                val advanced = source.skip(remaining)
                if (advanced <= 0) return false
                remaining -= advanced
            }
            return true
        }

        private fun toMillis(nanos: Long?): Long {
            return (nanos ?: 0L).coerceIn(0L, MAX_TIME_NANOS) / NANOS_PER_MILLI
        }

        private fun isSegmentLevel(id: Long): Boolean = when (id) {
            ID_SEGMENT, ID_SEEK_HEAD, ID_INFO, ID_TRACKS, ID_CHAPTERS,
            ID_CLUSTER, ID_CUES, ID_TAGS, ID_ATTACHMENTS -> true
            else -> false
        }

        /** Elements the reader descends into, and which are worth walking when cut short. */
        private fun isContainer(id: Long): Boolean = when (id) {
            ID_SEGMENT, ID_CHAPTERS, ID_EDITION_ENTRY, ID_CHAPTER_ATOM, ID_CHAPTER_DISPLAY -> true
            else -> false
        }

        private fun isEbmlHeaderField(id: Long): Boolean = when (id) {
            ID_EBML_VERSION, ID_EBML_READ_VERSION, ID_EBML_MAX_ID_LENGTH, ID_EBML_MAX_SIZE_LENGTH,
            ID_DOC_TYPE, ID_DOC_TYPE_VERSION, ID_DOC_TYPE_READ_VERSION, ID_CRC32 -> true
            else -> false
        }

        /**
         * Element IDs and other fixed parts of the format.
         *
         * They sit on the parser rather than on [MatroskaChapters] because a standalone
         * `object` cannot declare a companion of its own.
         */
        private companion object {
            /** Matroska's own `EBMLMaxIDLength`; a longer run of bytes is not an ID we could match. */
            private const val MAX_ID_BYTES = 4

            /** Element nesting depth walked before the file is treated as hostile. */
            private const val MAX_DEPTH = 32

            /** Chapter count kept before the rest are ignored; no real release comes close. */
            private const val MAX_CHAPTERS = 5000

            /** Longest title or language field worth allocating for. */
            private const val MAX_TEXT_BYTES = 4 * 1024

            /**
             * Ceiling for a chapter time.
             *
             * Matroska timestamps are 56-bit nanosecond values, so `1L shl 56` is about two and a half
             * years; anything larger is corruption rather than a very long film.
             */
            private const val MAX_TIME_NANOS = 1L shl 56

            private const val NANOS_PER_MILLI = 1_000_000L

            /** Returned in place of an ID or size when the input ends or the header is malformed. */
            private const val NO_VALUE = -1L

            /** The all-ones size payload, meaning the element's length is not known up front. */
            private const val UNKNOWN_SIZE = -2L

            private const val ID_EBML = 0x1A45DFA3L
            private const val ID_SEGMENT = 0x18538067L
            private const val ID_SEEK_HEAD = 0x114D9B74L
            private const val ID_INFO = 0x1549A966L
            private const val ID_TRACKS = 0x1654AE6BL
            private const val ID_CHAPTERS = 0x1043A770L
            private const val ID_CLUSTER = 0x1F43B675L
            private const val ID_CUES = 0x1C53BB6BL
            private const val ID_TAGS = 0x1254C367L
            private const val ID_ATTACHMENTS = 0x1941A469L

            private const val ID_EDITION_ENTRY = 0x45B9L
            private const val ID_CHAPTER_ATOM = 0xB6L
            private const val ID_CHAPTER_TIME_START = 0x91L
            private const val ID_CHAPTER_TIME_END = 0x92L
            private const val ID_CHAPTER_DISPLAY = 0x80L
            private const val ID_CHAP_STRING = 0x85L
            private const val ID_CHAP_LANGUAGE = 0x437CL

            // Header fields, only needed to find the end of an unsized EBML header.
            private const val ID_EBML_VERSION = 0x4286L
            private const val ID_EBML_READ_VERSION = 0x42F7L
            private const val ID_EBML_MAX_ID_LENGTH = 0x42F2L
            private const val ID_EBML_MAX_SIZE_LENGTH = 0x42F3L
            private const val ID_DOC_TYPE = 0x4282L
            private const val ID_DOC_TYPE_VERSION = 0x4287L
            private const val ID_DOC_TYPE_READ_VERSION = 0x4285L
            private const val ID_CRC32 = 0xBFL
        }

    }

    /** Where bytes come from, so the same parser serves a file and a pipe. */
    private interface Source {
        /** Absolute offset of the next byte to be read. */
        val position: Long

        /** Bytes available, or null when the source cannot know (a pipe or a socket). */
        fun length(): Long?

        /** Next byte, or -1 at end of input. */
        fun read(): Int

        /** Reads into [into]; returns the bytes stored, or -1 at end of input. */
        fun read(into: ByteArray, offset: Int, length: Int): Int

        /** Advances as far as it can, returning the bytes actually consumed. */
        fun skip(count: Long): Long
    }

    /**
     * A seekable file.
     *
     * [skip] is clamped to the real length because `seek` happily moves past the end and would
     * report a truncated element as fully skipped, hiding the truncation from the parser.
     */
    private class FileSource(private val handle: RandomAccessFile) : Source {
        override val position: Long get() = handle.filePointer

        override fun length(): Long = handle.length()

        override fun read(): Int = handle.read()

        override fun read(into: ByteArray, offset: Int, length: Int): Int =
            handle.read(into, offset, length)

        override fun skip(count: Long): Long {
            val available = (handle.length() - handle.filePointer).coerceAtLeast(0L)
            val consumed = minOf(count, available)
            handle.seek(handle.filePointer + consumed)
            return consumed
        }
    }

    /**
     * A non-seekable stream, position counted by hand.
     *
     * `skip` on a `FileInputStream` seeks underneath and is cheap, but on a pipe or a socket it may
     * move fewer bytes than asked or none at all. A zero-length skip means "nothing available yet"
     * rather than end of input, so progress is forced by reading and discarding one byte.
     */
    private class StreamSource(private val input: InputStream) : Source {
        override var position: Long = 0L
            private set

        override fun length(): Long? = null

        override fun read(): Int {
            val next = input.read()
            if (next >= 0) position++
            return next
        }

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            val count = input.read(into, offset, length)
            if (count > 0) position += count
            return count
        }

        override fun skip(count: Long): Long {
            var remaining = count
            var consumed = 0L
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                    consumed += skipped
                    continue
                }
                if (input.read() < 0) break
                remaining--
                consumed++
            }
            position += consumed
            return consumed
        }
    }

}
