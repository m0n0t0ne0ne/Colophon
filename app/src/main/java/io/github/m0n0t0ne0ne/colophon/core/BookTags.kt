package io.github.m0n0t0ne0ne.colophon.core

import java.nio.ByteBuffer

/**
 * Book-level tags read straight from the file bytes, including the series tags that
 * Android's MediaMetadataRetriever can't see. See docs/tag-contract.md.
 *
 *  meaning        M4B/M4A atom                         MP3 (ID3v2.3/2.4) frame
 *  marker         ----:com.apple.iTunes:COLOPHON        TXXX:COLOPHON
 *  series         ----:com.apple.iTunes:SERIES          TXXX:SERIES        (else ©mvn / MVNM, else grouping)
 *  book number    ----:com.apple.iTunes:SERIES-PART     TXXX:SERIES-PART   (else ©mvi / MVIN, else grouping)
 *  grouping       ©grp "Series, Book 1"                 TIT1
 */
data class BookTags(
    val title: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val artist: String? = null,
    val year: Int? = null,
    val series: String? = null,
    val seriesNumber: Double? = null,
    /** The COLOPHON marker: these tags were set on purpose, so trust them over file and folder names. */
    val curated: Boolean = false,
) {
    val author: String? get() = albumArtist ?: artist

    /** True when the file's tags should decide title, author and series instead of the names. */
    val trusted: Boolean get() = curated || series != null

    companion object {
        private val GROUPING = Regex("""^\s*(.+?)\s*,?\s*(?:book|bk|vol(?:ume)?\.?|#)\s*#?\s*(\d+(?:\.\d+)?)\s*$""", RegexOption.IGNORE_CASE)

        fun parse(source: ByteSource): BookTags = runCatching {
            val head = source.read(0, 12)
            when {
                head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> fromMp4(source)
                head.size >= 3 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" -> fromId3(source)
                else -> BookTags()
            }
        }.getOrDefault(BookTags())

        /** Builds the result from raw key/value text the same way for both formats. */
        internal fun build(v: Map<String, String>): BookTags {
            fun get(k: String) = v[k]?.trim()?.takeIf { it.isNotEmpty() }
            var series = get("SERIES") ?: get("MVNM")
            var number = get("SERIES-PART")?.toNumber() ?: get("MVIN")?.substringBefore('/')?.toNumber()
            if (series == null) get("GROUPING")?.let { g ->
                GROUPING.find(g)?.let { m -> series = m.groupValues[1].trim(); if (number == null) number = m.groupValues[2].toNumber() }
            }
            return BookTags(
                title = get("TITLE"), album = get("ALBUM"), albumArtist = get("ALBUMARTIST"), artist = get("ARTIST"),
                year = get("YEAR")?.take(4)?.toIntOrNull(),
                series = series, seriesNumber = number,
                curated = get("COLOPHON") != null,
            )
        }

        private fun String.toNumber(): Double? = trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }

        // ------------------------------------------------------------------ MP4
        private val MP4_KEYS = mapOf(
            "©nam" to "TITLE", "©alb" to "ALBUM", "aART" to "ALBUMARTIST", "©ART" to "ARTIST",
            "©day" to "YEAR", "©mvn" to "MVNM", "©mvi" to "MVIN", "©grp" to "GROUPING",
        )
        private val FREEFORM = setOf("SERIES", "SERIES-PART", "COLOPHON")

        private fun fromMp4(source: ByteSource): BookTags {
            val moov = Mp4Chapters.findMoov(source) ?: return BookTags()
            val out = HashMap<String, String>()
            Mp4Chapters.forEachAtom(moov) { t, udta ->
                if (t != "udta") return@forEachAtom
                Mp4Chapters.forEachAtom(udta) { t2, meta ->
                    if (t2 != "meta") return@forEachAtom
                    // ISO "meta" is a full box (4 bytes version/flags before its children); QuickTime's isn't.
                    val quickTime = meta.remaining() >= 8 &&
                        String(ByteArray(4).also { meta.duplicate().apply { position(4) }.get(it) }, Charsets.ISO_8859_1) == "hdlr"
                    val m = meta.duplicate().apply { position(if (quickTime) 0 else 4) }
                    Mp4Chapters.forEachAtom(m.slice()) { t3, ilst -> if (t3 == "ilst") readIlst(ilst, out) }
                }
            }
            return build(out)
        }

        private fun readIlst(ilst: ByteBuffer, out: MutableMap<String, String>) {
            Mp4Chapters.forEachAtom(ilst) { key, item ->
                var mean = ""
                var name = ""
                var value: String? = null
                Mp4Chapters.forEachAtom(item) { t, body ->
                    when (t) {
                        "mean" -> mean = text(body, 4)
                        "name" -> name = text(body, 4)
                        "data" -> value = dataValue(body)
                    }
                }
                val v = value ?: return@forEachAtom
                when {
                    key == "----" && mean == "com.apple.iTunes" && name.uppercase() in FREEFORM -> out[name.uppercase()] = v
                    key in MP4_KEYS -> out[MP4_KEYS.getValue(key)] = v
                }
            }
        }

        /** A "data" atom: 1 byte version, 3 bytes type (1 = UTF-8, 21 = integer, 0 = implicit), 4 bytes locale, value. */
        private fun dataValue(body: ByteBuffer): String? {
            if (body.remaining() < 8) return null
            val type = body.getInt(0) and 0xFFFFFF
            val len = body.remaining() - 8
            if (type == 21 && len in 1..8) {                 // big-endian integer, e.g. ©mvi
                var n = 0L
                for (i in 0 until len) n = (n shl 8) or (body.get(8 + i).toLong() and 0xFF)
                return n.toString()
            }
            return text(body, 8)
        }

        private fun text(body: ByteBuffer, skip: Int): String {
            if (body.remaining() <= skip) return ""
            val bytes = ByteArray(body.remaining() - skip)
            body.duplicate().apply { position(skip) }.get(bytes)
            return String(bytes, Charsets.UTF_8).trim('\u0000', ' ')
        }

        // ------------------------------------------------------------------ ID3
        private val ID3_KEYS = mapOf(
            "TIT2" to "TITLE", "TALB" to "ALBUM", "TPE2" to "ALBUMARTIST", "TPE1" to "ARTIST", "TYER" to "YEAR",
            "TDRC" to "YEAR", "MVNM" to "MVNM", "MVIN" to "MVIN", "TIT1" to "GROUPING",
        )

        private fun fromId3(source: ByteSource): BookTags {
            val h = source.read(0, 10)
            if (h.size < 10) return BookTags()
            val major = h[3].toInt()
            if (major != 3 && major != 4) return BookTags()
            val tag = source.read(10, Id3Chapters.syncsafe(h, 6))
            val out = HashMap<String, String>()
            Id3Chapters.forEachFrame(tag, 0, tag.size, major) { id, s, e ->
                when {
                    id == "TXXX" -> txxx(tag, s, e)?.let { (desc, value) ->
                        val k = desc.uppercase()
                        if (k in FREEFORM) out[k] = value
                    }
                    id in ID3_KEYS -> out.putIfAbsent(ID3_KEYS.getValue(id), Id3Chapters.decodeText(tag, s, e).substringBefore('\u0000'))
                }
            }
            return build(out)
        }

        /** TXXX: encoding byte, description, terminator (1 byte, or 2 for UTF-16), value. */
        private fun txxx(t: ByteArray, s: Int, e: Int): Pair<String, String>? {
            if (e - s < 2) return null
            val enc = t[s].toInt()
            val wide = enc == 1 || enc == 2
            var p = s + 1
            while (p < e && !(t[p] == 0.toByte() && (!wide || (p + 1 < e && t[p + 1] == 0.toByte() && (p - s - 1) % 2 == 0)))) p++
            if (p >= e) return null
            val charset = when (enc) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
            val desc = String(t, s + 1, p - s - 1, charset)
            val valueStart = p + if (wide) 2 else 1
            if (valueStart > e) return null
            val value = String(t, valueStart, e - valueStart, charset).trim('\u0000', ' ', '﻿')
            return desc.trim('﻿', ' ') to value
        }
    }
}
