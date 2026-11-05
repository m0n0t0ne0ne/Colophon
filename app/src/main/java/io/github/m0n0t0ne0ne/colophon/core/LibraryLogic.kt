package io.github.m0n0t0ne0ne.colophon.core

/** Sorts "Track 2" before "Track 10", which plain alphabetical sorting gets wrong. */
object NaturalOrder : Comparator<String> {
    private val parts = Regex("""\d+|\D+""")
    override fun compare(a: String, b: String): Int {
        val x = parts.findAll(a.lowercase()).map { it.value }.toList()
        val y = parts.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val nx = x[i].toBigIntegerOrNull()
            val ny = y[i].toBigIntegerOrNull()
            val c = if (nx != null && ny != null) nx.compareTo(ny) else x[i].compareTo(y[i])
            if (c != 0) return c
        }
        return x.size - y.size
    }
}

data class TitleGuess(val title: String, val series: String?, val number: Double?, val year: Int?)

object MetadataGuess {
    private val YEAR = Regex("""^((?:19|20)\d{2})\s*-\s*""")
    private val NOISE = Regex("""\s*[\[(](?:un)?abridged[\])]\s*""", RegexOption.IGNORE_CASE)
    private val BOOK_N = Regex("""^(.*?)[,:\s-]+(?:book|bk|vol(?:ume)?\.?)\s*#?(\d+(?:\.\d+)?)\s*$""", RegexOption.IGNORE_CASE)
    private val LEADING_N = Regex("""^(\d+(?:\.\d+)?)\s*[-.]\s+(.+)$""")
    private val EXT = Regex("""\.[A-Za-z0-9]{1,5}$""")

    fun guess(fileName: String, folderName: String?, tagTitle: String? = null): TitleGuess {
        var s = fileName.replace(EXT, "").replace('_', ' ').trim()
        val year = YEAR.find(s)?.groupValues?.get(1)?.toIntOrNull()
        s = s.replace(YEAR, "").replace(NOISE, " ").trim()
        val folder = folderName?.trim()?.takeIf { it.isNotEmpty() }

        BOOK_N.find(s)?.let { m ->
            val before = m.groupValues[1].trim()
            val number = m.groupValues[2].toDouble()
            if (folder != null) {
                val core = stripThe(folder)
                val at = before.lowercase().lastIndexOf(core.lowercase())
                if (at > 0 && at + core.length == before.length) {
                    return TitleGuess(cleanEnd(before.substring(0, at)), folder, number, year)
                }
            }
            return TitleGuess(tagTitle?.takeIf { it.isNotBlank() } ?: before, folder, number, year)
        }
        LEADING_N.find(s)?.let { m ->
            return TitleGuess(m.groupValues[2].trim(), folder, m.groupValues[1].toDouble(), year)
        }
        return TitleGuess(tagTitle?.takeIf { it.isNotBlank() } ?: s, null, null, year)
    }

    private fun stripThe(s: String) = s.replace(Regex("""^the\s+""", RegexOption.IGNORE_CASE), "")
    private fun cleanEnd(s: String) = s.trim().trimEnd('-', ',', ':').trim().replace(Regex("""\s+the$""", RegexOption.IGNORE_CASE), "")
}

object Timeline {
    fun locate(durations: List<Long>, positionMs: Long): Pair<Int, Long> {
        var rest = positionMs.coerceAtLeast(0)
        durations.forEachIndexed { i, d ->
            if (rest < d || i == durations.lastIndex) return i to rest.coerceAtMost(d)
            rest -= d
        }
        return 0 to 0
    }

    fun global(durations: List<Long>, index: Int, positionMs: Long): Long =
        durations.take(index).sum() + positionMs

    /** Index of the chapter containing the position. Chapters come sorted by start time. */
    fun chapterAt(starts: List<Long>, positionMs: Long): Int {
        var found = 0
        for (i in starts.indices) if (starts[i] <= positionMs) found = i else break
        return found
    }
}

object Resume {
    /** The longer you have been away, the further back the book starts. */
    fun rewindMs(awayMs: Long): Long = when {
        awayMs < 60_000 -> 2_000
        awayMs < 3_600_000 -> 5_000
        awayMs < 86_400_000 -> 15_000
        else -> 30_000
    }
}

fun formatClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0)) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun formatLeft(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / 60_000
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
