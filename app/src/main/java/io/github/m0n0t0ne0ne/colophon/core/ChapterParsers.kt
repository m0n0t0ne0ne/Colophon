package io.github.m0n0t0ne0ne.colophon.core

import java.nio.ByteBuffer

data class ParsedChapter(val startMs: Long, val title: String)

/** Random access to file bytes, so large audiobooks never have to be loaded whole. */
interface ByteSource {
    val size: Long
    fun read(position: Long, length: Int): ByteArray
}

class ArraySource(private val data: ByteArray) : ByteSource {
    override val size: Long get() = data.size.toLong()
    override fun read(position: Long, length: Int): ByteArray {
        if (position >= data.size) return ByteArray(0)
        val end = minOf(data.size.toLong(), position + length).toInt()
        return data.copyOfRange(position.toInt(), end)
    }
}

object ChapterParser {
    /** Picks the parser from the file's own signature rather than trusting its extension. */
    fun parse(source: ByteSource): List<ParsedChapter> = runCatching {
        val head = source.read(0, 12)
        when {
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> Mp4Chapters.parse(source)
            head.size >= 3 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" -> Id3Chapters.parse(source)
            head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "fLaC" -> VorbisChapters.parseFlac(source)
            head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "OggS" -> VorbisChapters.parseOgg(source)
            else -> emptyList()
        }
    }.getOrDefault(emptyList()).sortedBy { it.startMs }
}

object Mp4Chapters {
    private const val MAX_MOOV = 128L * 1024 * 1024

    private data class Track(
        var id: Int = 0,
        var timescale: Long = 0,
        var isText: Boolean = false,
        var chapterRefs: List<Int> = emptyList(),
        var stts: ByteBuffer? = null,
        var stsc: ByteBuffer? = null,
        var stsz: ByteBuffer? = null,
        var chunkOffsets: List<Long> = emptyList(),
    )

    fun parse(source: ByteSource): List<ParsedChapter> {
        val moov = findMoov(source) ?: return emptyList()
        val tracks = mutableListOf<Track>()
        var nero = emptyList<ParsedChapter>()
        forEachAtom(moov) { type, body ->
            when (type) {
                "trak" -> tracks += parseTrack(body)
                "udta" -> forEachAtom(body) { t, b -> if (t == "chpl") nero = parseNero(b) }
            }
        }
        val refs = tracks.flatMap { it.chapterRefs }.toSet()
        val textTrack = tracks.firstOrNull { it.id in refs && it.isText }
        val quickTime = textTrack?.let { readTextTrack(source, it) }.orEmpty()
        return if (quickTime.size >= nero.size) quickTime else nero
    }

    private fun findMoov(source: ByteSource): ByteBuffer? {
        var pos = 0L
        while (pos + 8 <= source.size) {
            val header = source.read(pos, 16)
            if (header.size < 8) return null
            val buf = ByteBuffer.wrap(header)
            var size = buf.int.toLong() and 0xFFFFFFFFL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerLength = 8
            if (size == 1L) {
                if (header.size < 16) return null
                buf.position(8)
                size = buf.long
                headerLength = 16
            } else if (size == 0L) {
                size = source.size - pos
            }
            if (size < headerLength) return null
            if (type == "moov") {
                if (size > MAX_MOOV) return null
                return ByteBuffer.wrap(source.read(pos + headerLength, (size - headerLength).toInt()))
            }
            pos += size
        }
        return null
    }

    private fun forEachAtom(parent: ByteBuffer, block: (String, ByteBuffer) -> Unit) {
        val b = parent.duplicate()
        while (b.remaining() >= 8) {
            val start = b.position()
            var size = b.int.toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4).also { b.get(it) }
            val type = String(typeBytes, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                if (b.remaining() < 8) return
                size = b.long
                header = 16
            } else if (size == 0L) {
                size = (b.limit() - start).toLong()
            }
            if (size < header || start + size > b.limit()) return
            val body = b.duplicate()
            body.position(start + header)
            body.limit((start + size).toInt())
            block(type, body.slice())
            b.position((start + size).toInt())
        }
    }

    private fun parseTrack(trak: ByteBuffer): Track {
        val t = Track()
        forEachAtom(trak) { type, body ->
            when (type) {
                "tkhd" -> {
                    val version = body.get(0).toInt()
                    t.id = body.getInt(if (version == 1) 20 else 12)
                }
                "tref" -> forEachAtom(body) { rt, rb ->
                    if (rt == "chap") {
                        val ids = mutableListOf<Int>()
                        while (rb.remaining() >= 4) ids += rb.int
                        t.chapterRefs = ids
                    }
                }
                "mdia" -> parseMedia(body, t)
            }
        }
        return t
    }

    private fun parseMedia(mdia: ByteBuffer, t: Track) {
        forEachAtom(mdia) { type, body ->
            when (type) {
                "mdhd" -> {
                    val version = body.get(0).toInt()
                    t.timescale = (body.getInt(if (version == 1) 20 else 12).toLong() and 0xFFFFFFFFL)
                }
                "hdlr" -> {
                    val handler = ByteArray(4)
                    body.position(8)
                    body.get(handler)
                    val name = String(handler, Charsets.ISO_8859_1)
                    t.isText = name == "text" || name == "sbtl"
                }
                "minf" -> forEachAtom(body) { mt, mb ->
                    if (mt == "stbl") forEachAtom(mb) { st, sb ->
                        when (st) {
                            "stts" -> t.stts = sb
                            "stsc" -> t.stsc = sb
                            "stsz" -> t.stsz = sb
                            "stco" -> {
                                sb.position(4)
                                val n = sb.int
                                t.chunkOffsets = List(n) { sb.int.toLong() and 0xFFFFFFFFL }
                            }
                            "co64" -> {
                                sb.position(4)
                                val n = sb.int
                                t.chunkOffsets = List(n) { sb.long }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun readTextTrack(source: ByteSource, t: Track): List<ParsedChapter> {
        val stts = t.stts?.duplicate() ?: return emptyList()
        val stsz = t.stsz?.duplicate() ?: return emptyList()
        val stsc = t.stsc?.duplicate() ?: return emptyList()
        if (t.timescale <= 0) return emptyList()

        stts.position(4)
        val starts = mutableListOf<Long>()
        var ticks = 0L
        repeat(stts.int) {
            val count = stts.int
            val delta = stts.int.toLong() and 0xFFFFFFFFL
            repeat(count) { starts += ticks; ticks += delta }
        }

        stsz.position(4)
        val fixed = stsz.int
        val sampleCount = stsz.int
        val sizes = List(sampleCount) { if (fixed != 0) fixed else stsz.int }

        stsc.position(4)
        val entries = List(stsc.int) {
            val first = stsc.int
            val perChunk = stsc.int
            stsc.int
            first to perChunk
        }
        val offsets = mutableListOf<Long>()
        var sample = 0
        t.chunkOffsets.forEachIndexed { index, chunkStart ->
            val chunkNumber = index + 1
            val perChunk = entries.lastOrNull { it.first <= chunkNumber }?.second ?: 1
            var pos = chunkStart
            repeat(perChunk) {
                if (sample < sizes.size) {
                    offsets += pos
                    pos += sizes[sample]
                    sample++
                }
            }
        }

        return offsets.indices.mapNotNull { i ->
            if (i >= starts.size) return@mapNotNull null
            val raw = source.read(offsets[i], minOf(sizes[i], 2048))
            if (raw.size < 2) return@mapNotNull null
            val length = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
            val end = minOf(raw.size, 2 + length)
            val title = decodeSampleText(raw.copyOfRange(2, end)).trim()
            ParsedChapter(starts[i] * 1000 / t.timescale, title)
        }
    }

    private fun decodeSampleText(b: ByteArray): String =
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) String(b, 2, b.size - 2, Charsets.UTF_16BE)
        else if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) String(b, 2, b.size - 2, Charsets.UTF_16LE)
        else String(b, Charsets.UTF_8)

    private fun parseNero(body: ByteBuffer): List<ParsedChapter> {
        val version = body.get().toInt()
        body.position(body.position() + 3)
        if (version != 0) body.position(body.position() + 4)
        val count = body.get().toInt() and 0xFF
        val out = mutableListOf<ParsedChapter>()
        repeat(count) {
            if (body.remaining() < 9) return out
            val start = body.long
            val length = body.get().toInt() and 0xFF
            if (body.remaining() < length) return out
            val name = ByteArray(length).also { body.get(it) }
            out += ParsedChapter(start / 10_000, String(name, Charsets.UTF_8).trim())
        }
        return out
    }
}

object Id3Chapters {
    fun parse(source: ByteSource): List<ParsedChapter> {
        val h = source.read(0, 10)
        if (h.size < 10) return emptyList()
        val major = h[3].toInt()
        if (major != 3 && major != 4) return emptyList()
        val total = syncsafe(h, 6)
        val tag = source.read(10, total)
        val out = mutableListOf<ParsedChapter>()
        forEachFrame(tag, 0, tag.size, major) { id, start, end ->
            if (id == "CHAP") parseChap(tag, start, end, major)?.let { out += it }
        }
        return out
    }

    private fun parseChap(t: ByteArray, start: Int, end: Int, major: Int): ParsedChapter? {
        var p = start
        while (p < end && t[p] != 0.toByte()) p++
        p++
        if (p + 16 > end) return null
        val startMs = ByteBuffer.wrap(t, p, 4).int.toLong() and 0xFFFFFFFFL
        p += 16
        var title = ""
        forEachFrame(t, p, end, major) { id, s, e ->
            if (id == "TIT2") title = decodeText(t, s, e)
        }
        return ParsedChapter(startMs, title)
    }

    private inline fun forEachFrame(t: ByteArray, from: Int, to: Int, major: Int, block: (String, Int, Int) -> Unit) {
        var p = from
        while (p + 10 <= to) {
            if (t[p] == 0.toByte()) return
            val id = String(t, p, 4, Charsets.ISO_8859_1)
            val size = if (major == 4) syncsafe(t, p + 4) else ByteBuffer.wrap(t, p + 4, 4).int
            if (size <= 0 || p + 10 + size > to) return
            block(id, p + 10, p + 10 + size)
            p += 10 + size
        }
    }

    private fun syncsafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun decodeText(t: ByteArray, s: Int, e: Int): String {
        if (e <= s) return ""
        val charset = when (t[s].toInt()) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(t, s + 1, e - s - 1, charset).trim('\u0000', ' ')
    }
}

object VorbisChapters {
    private val TIME = Regex("""(\d+):(\d{2}):(\d{2})(?:[.,](\d{1,3}))?""")

    fun parseFlac(source: ByteSource): List<ParsedChapter> {
        var pos = 4L
        while (pos + 4 <= source.size) {
            val h = source.read(pos, 4)
            if (h.size < 4) return emptyList()
            val type = h[0].toInt() and 0x7F
            val last = h[0].toInt() and 0x80 != 0
            val length = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
            if (type == 4) return fromComments(source.read(pos + 4, length), 0)
            if (last) return emptyList()
            pos += 4 + length
        }
        return emptyList()
    }

    fun parseOgg(source: ByteSource): List<ParsedChapter> {
        val head = source.read(0, 1024 * 1024)
        for (marker in listOf("OpusTags", "\u0003vorbis")) {
            val i = indexOf(head, marker.toByteArray(Charsets.ISO_8859_1))
            if (i >= 0) return fromComments(head, i + marker.length)
        }
        return emptyList()
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun fromComments(b: ByteArray, offset: Int): List<ParsedChapter> {
        val comments = readComments(b, offset)
        val times = sortedMapOf<String, Long>()
        val names = HashMap<String, String>()
        comments.forEach { c ->
            val eq = c.indexOf('=')
            if (eq <= 0) return@forEach
            val key = c.substring(0, eq).uppercase()
            val value = c.substring(eq + 1)
            if (!key.startsWith("CHAPTER")) return@forEach
            if (key.endsWith("NAME")) names[key.removeSuffix("NAME")] = value
            else parseTime(value)?.let { times[key] = it }
        }
        return times.map { (k, t) -> ParsedChapter(t, names[k].orEmpty().trim()) }
    }

    private fun readComments(b: ByteArray, offset: Int): List<String> {
        val buf = ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.position(offset)
        if (buf.remaining() < 8) return emptyList()
        val vendor = buf.int
        if (vendor < 0 || vendor > buf.remaining() - 4) return emptyList()
        buf.position(buf.position() + vendor)
        val count = buf.int
        val out = mutableListOf<String>()
        repeat(count.coerceAtMost(100_000)) {
            if (buf.remaining() < 4) return out
            val n = buf.int
            if (n < 0 || n > buf.remaining()) return out
            val s = ByteArray(n).also { buf.get(it) }
            out += String(s, Charsets.UTF_8)
        }
        return out
    }

    private fun parseTime(s: String): Long? {
        val m = TIME.matchEntire(s.trim()) ?: return null
        val (h, mm, ss, frac) = m.destructured
        return (h.toLong() * 3600 + mm.toLong() * 60 + ss.toLong()) * 1000 + frac.padEnd(3, '0').take(3).ifEmpty { "0" }.toLong()
    }
}

object CueSheet {
    private val TITLE = Regex("""^\s*TITLE\s+"?(.*?)"?\s*$""", RegexOption.IGNORE_CASE)
    private val TRACK = Regex("""^\s*TRACK\s+\d+""", RegexOption.IGNORE_CASE)
    private val INDEX = Regex("""^\s*INDEX\s+01\s+(\d+):(\d+):(\d+)""", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<ParsedChapter> {
        val out = mutableListOf<ParsedChapter>()
        var inTrack = false
        var title = ""
        text.lineSequence().forEach { line ->
            when {
                TRACK.containsMatchIn(line) -> { inTrack = true; title = "" }
                inTrack && TITLE.matches(line) -> title = TITLE.find(line)!!.groupValues[1]
                inTrack && INDEX.containsMatchIn(line) -> {
                    val (m, s, f) = INDEX.find(line)!!.destructured
                    out += ParsedChapter((m.toLong() * 60 + s.toLong()) * 1000 + f.toLong() * 1000 / 75, title.trim())
                }
            }
        }
        return out
    }
}

object M3u {
    fun parse(text: String): List<String> =
        text.lineSequence().map { it.trim().removePrefix("﻿") }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
}
