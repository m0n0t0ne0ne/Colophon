package io.github.m0n0t0ne0ne.colophon.core

import kotlin.math.abs

/** Decides which places are worth saving as a jump-back point, so the undo list stays meaningful. */
object JumpHistory {
    const val MIN_JUMP_MS = 60_000L
    private const val EDGE_MS = 2_000L
    private const val DEDUPE_MS = 5_000L

    fun shouldRemember(fromMs: Long, toMs: Long, totalMs: Long, lastMs: Long?): Boolean {
        if (abs(fromMs - toMs) < MIN_JUMP_MS) return false
        // The very start and the very end aren't useful places to return to, and are where
        // spurious entries land, so drop them.
        if (fromMs <= EDGE_MS) return false
        if (totalMs > 0 && fromMs >= totalMs - EDGE_MS) return false
        // Don't stack near-identical entries from one navigation.
        if (lastMs != null && abs(lastMs - fromMs) < DEDUPE_MS) return false
        return true
    }
}

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
    /** The longer you have been away, the further back the book starts. The long-away
     *  amount (5 minutes or more) is set by the listener; shorter gaps get a small nudge. */
    fun rewindMs(awayMs: Long, longAwaySec: Int = 15): Long = when {
        awayMs < 60_000 -> 2_000
        awayMs < 300_000 -> 5_000
        else -> longAwaySec * 1000L
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

/** A file or folder, independent of Android, so the library rules can be tested directly. */
class ScanNode(
    val name: String,
    val isDirectory: Boolean,
    val handle: Any? = null,
    private val list: () -> List<ScanNode> = { emptyList() },
) {
    val children: List<ScanNode> by lazy { list() }
}

data class BookPlan(
    val files: List<ScanNode>,
    /** The folder that holds only this book, if there is one. */
    val bookFolder: String?,
    val seriesFolder: String?,
    val cue: ScanNode?,
    val playlist: ScanNode?,
    /** Pictures that belong to this book, best match first. */
    val images: List<ScanNode> = emptyList(),
)

object LibraryScanner {
    private val audio = setOf("m4b", "m4a", "mp4", "aac", "mp3", "flac", "ogg", "oga", "opus", "wav")
    val protectedTypes = setOf("aa", "aax", "aaxc")
    private val generic = setOf("audiobooks", "audiobook", "audio books", "books", "library", "download", "downloads", "music", "audio", "media", "documents")
    private val discFolder = Regex("""^(cd|disc|disk|part)\s*[-_.]?\s*\d+.*$""", RegexOption.IGNORE_CASE)
    private val partWords = Regex("""\b(part|pt|disc|disk|cd|track|file)\b|\d+""", RegexOption.IGNORE_CASE)

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    /** The whole tree is one library: a folder of book folders is a series, a book's own folder is not. */
    fun plan(root: ScanNode): List<BookPlan> = walk(root, emptyList(), true)

    private fun walk(dir: ScanNode, ancestors: List<ScanNode>, isRoot: Boolean): List<BookPlan> {
        val visible = dir.children.filter { !it.name.startsWith(".") }
        val files = visible.filter { !it.isDirectory }
        val folders = visible.filter { it.isDirectory }
        val tracks = files.filter { ext(it.name) in audio }
        val cues = files.filter { ext(it.name) == "cue" }
        val playlist = files.firstOrNull { ext(it.name) == "m3u" || ext(it.name) == "m3u8" }
        val out = mutableListOf<BookPlan>()
        val above = ancestors + listOf(dir)

        val discs = folders.filter { discFolder.matches(it.name) && it.children.any { c -> ext(c.name) in audio } }
        if (discs.isNotEmpty() && discs.size == folders.size && !isRoot) {
            val all = tracks + discs.sortedWith(compareBy(NaturalOrder) { it.name }).flatMap { d ->
                d.children.filter { ext(it.name) in audio }.sortedWith(compareBy(NaturalOrder) { it.name })
            }
            return listOf(BookPlan(all, dir.name, seriesFrom(ancestors, dir.name, all), null, playlist, CoverImages.rank(files, dir.name)))
        }

        val separate = tracks.size > 1 && !isParts(tracks.map { it.name }) &&
            (tracks.any { ext(it.name) == "m4b" } || (cues.isNotEmpty() && cues.size == tracks.size))
        when {
            tracks.isEmpty() -> Unit
            separate -> tracks.forEach { t ->
                val cue = cues.firstOrNull { it.name.substringBeforeLast('.') == t.name.substringBeforeLast('.') }
                val base = t.name.substringBeforeLast('.')
                val own = files.filter { CoverImages.isImage(it.name) && it.name.substringBeforeLast('.').equals(base, true) }
                out += BookPlan(listOf(t), null, seriesFrom(above, t.name, listOf(t)), cue, null, own)
            }
            isRoot && tracks.size == 1 ->
                out += BookPlan(tracks, null, seriesFrom(above, tracks[0].name, tracks), cues.firstOrNull(), null,
                    files.filter { CoverImages.isImage(it.name) && it.name.substringBeforeLast('.').equals(tracks[0].name.substringBeforeLast('.'), true) })
            else -> out += BookPlan(tracks, dir.name, seriesFrom(ancestors, dir.name, tracks), cues.firstOrNull(), playlist, CoverImages.rank(files, dir.name))
        }
        folders.forEach { out += walk(it, above, false) }
        return out
    }

    private fun isParts(names: List<String>): Boolean =
        names.map { it.substringBeforeLast('.').replace(partWords, "").filter { c -> c.isLetter() }.lowercase() }.toSet().size == 1

    private fun seriesFrom(ancestors: List<ScanNode>, label: String, files: List<ScanNode>): String? {
        val nearest = ancestors.lastOrNull() ?: return null
        if (nearest.name.trim().lowercase() in generic) return null
        // The folder someone picked may be a whole library, so it only counts when the names agree.
        if (nearest === ancestors.first()) {
            val core = SeriesNames.core(nearest.name)
            val text = SeriesNames.core(label + " " + files.joinToString(" ") { it.name })
            if (core.isEmpty() || !text.contains(core)) return null
        }
        return nearest.name.trim()
    }
}

object CoverImages {
    private val types = setOf("jpg", "jpeg", "png", "webp")
    private val preferred = listOf("cover", "folder", "front", "album", "artwork", "art", "poster")
    private val avoided = Regex("""\b(back|rear|spine|inside|inlay|booklet|disc|cd)\b""", RegexOption.IGNORE_CASE)

    fun isImage(name: String) = LibraryScanner.ext(name) in types

    /** Orders a folder's pictures so a clearly named cover wins, and a "back" or "disc" image is left out when possible. */
    fun rank(files: List<ScanNode>, folderName: String): List<ScanNode> {
        val images = files.filter { isImage(it.name) && !it.name.startsWith(".") }
        val title = SeriesNames.core(folderName)
        fun score(n: ScanNode): Int {
            val base = n.name.substringBeforeLast('.')
            val core = SeriesNames.core(base)
            val word = preferred.indexOfFirst { p -> core == p }
            return when {
                word >= 0 -> word
                preferred.any { p -> Regex("""\b$p\b""", RegexOption.IGNORE_CASE).containsMatchIn(base) } -> 10
                title.isNotEmpty() && (core.contains(title) || title.contains(core)) && !avoided.containsMatchIn(base) -> 20
                avoided.containsMatchIn(base) -> 90
                else -> 50
            }
        }
        return images.sortedWith(compareBy<ScanNode> { score(it) }.then(compareBy(NaturalOrder) { it.name }))
    }
}

object SeriesNames {
    private val leading = Regex("""^(a|an|the)\s+""")
    fun core(name: String): String =
        name.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim().replace(leading, "")
}

object BookNames {
    private val YEAR = Regex("""^((?:1[89]|20)\d{2})\s*[-–.]\s*""")
    private val NUMBER = Regex("""^(\d{1,2}(?:\.\d+)?)[\s.\-–_]+(?=\S)""")
    private val NOISE = Regex("""\s*[\[(](?:un)?abridged[\])]\s*""", RegexOption.IGNORE_CASE)
    private val BOOK_N = Regex("""[,:\s-]+(?:book|bk|vol(?:ume)?\.?)\s*#?(\d+(?:\.\d+)?)\s*$""", RegexOption.IGNORE_CASE)
    private val EXT = Regex("""\.[A-Za-z0-9]{1,5}$""")

    data class Parsed(val title: String, val number: Double?, val year: Int?)

    private fun parse(raw: String, isFile: Boolean): Parsed {
        var s = (if (isFile) raw.replace(EXT, "") else raw).replace('_', ' ').replace(NOISE, " ").trim()
        val year = YEAR.find(s)?.groupValues?.get(1)?.toInt()
        s = s.replace(YEAR, "")
        var number = BOOK_N.find(s)?.groupValues?.get(1)?.toDouble()
        s = s.replace(BOOK_N, "").trim()
        NUMBER.find(s)?.let { m ->
            if (number == null) number = m.groupValues[1].toDouble()
            s = s.substring(m.range.last + 1)
        }
        return Parsed(s.trim().trim('-', ',', ':').trim(), number, year)
    }

    /** Removes a series name from either end of a title. */
    fun withoutSeries(title: String, series: String?): String {
        val core = series?.let(SeriesNames::core)?.takeIf { it.length >= 4 } ?: return title
        val words = title.split(Regex("""\s+"""))
        val n = core.split(" ").size
        for (extra in 1 downTo 0) {
            val take = n + extra
            if (words.size <= take) continue
            if (SeriesNames.core(words.takeLast(take).joinToString(" ")) == core) {
                return words.dropLast(take).joinToString(" ").trim().trim('-', ',', ':').trim()
            }
            if (SeriesNames.core(words.take(take).joinToString(" ")) == core) {
                return words.drop(take).joinToString(" ").trim().trim('-', ',', ':').trim()
            }
        }
        return title
    }

    fun describe(fileName: String, bookFolder: String?, series: String?, fallbackTitle: String?): TitleGuess {
        val file = parse(fileName, true)
        val folder = bookFolder?.let { parse(it, false) }
        val fromFolder = folder?.title?.takeIf { it.any(Char::isLetter) }
        val fromFile = file.title.takeIf { it.any(Char::isLetter) }
        val best = fromFolder ?: fromFile ?: fallbackTitle?.takeIf { it.isNotBlank() } ?: fileName
        return TitleGuess(withoutSeries(best, series), series, file.number ?: folder?.number, file.year ?: folder?.year)
    }
}

object SeriesConsolidator {
    data class Item(val id: Long, val series: String?, val number: Double?, val searchText: String, val locked: Boolean)

    /** Joins spelling variants, finds loose books that name a known series, and drops one-book "series". */
    fun assign(items: List<Item>): Map<Long, String?> {
        val named = items.filter { !it.series.isNullOrBlank() }
        val byCore = named.groupBy { SeriesNames.core(it.series!!) }
        val display = byCore.mapValues { (_, list) ->
            list.groupingBy { it.series!! }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length }).first().key
        }
        val result = HashMap<Long, String?>()
        items.forEach { item ->
            if (item.locked) { result[item.id] = item.series; return@forEach }
            val own = item.series?.takeIf { it.isNotBlank() }?.let { display[SeriesNames.core(it)] }
            result[item.id] = own ?: run {
                val text = SeriesNames.core(item.searchText)
                display.entries.filter { it.key.length >= 6 && " $text ".contains(" ${it.key} ") }.maxByOrNull { it.key.length }?.value
            }
        }
        val counts = result.values.filterNotNull().groupingBy { SeriesNames.core(it) }.eachCount()
        items.forEach { item ->
            val s = result[item.id]
            if (!item.locked && s != null && counts[SeriesNames.core(s)] == 1 && item.number == null) result[item.id] = null
        }
        return result
    }
}

/** Sections of a book that the system's media controls treat as separate tracks. */
class SectionMap(starts: List<Long>, val totalMs: Long) {
    // Anything before the first marker belongs to the first section.
    private val starts: List<Long> = starts.ifEmpty { listOf(0L) }.mapIndexed { i, s -> if (i == 0) 0L else s }
    val count: Int get() = starts.size

    fun indexAt(positionMs: Long): Int = Timeline.chapterAt(starts, positionMs)
    fun startOf(index: Int): Long = starts[index]
    fun endOf(index: Int): Long = (starts.getOrNull(index + 1) ?: totalMs).coerceAtLeast(starts[index] + 1)

    /** Where "next" goes, or null in the last section. */
    fun next(positionMs: Long): Long? = starts.getOrNull(indexAt(positionMs) + 1)

    /** Where "previous" goes: the start of this section, or the one before if already near its start. */
    fun previous(positionMs: Long): Long? {
        if (count == 1) return null
        val index = indexAt(positionMs)
        return if (positionMs - starts[index] > RESTART_WINDOW_MS || index == 0) starts[index] else starts[index - 1]
    }

    private companion object {
        const val RESTART_WINDOW_MS = 3_000L
    }
}

/**
 * A chapter title split into its place in the book and its name, for the two-line player display.
 * The split is driven by the separator: whatever sits before a dash or colon is the label
 * ("Chapter 16", "Part 1", or a number like "002"), and the rest is the name.
 */
data class ChapterHeading(val label: String, val name: String?) {
    companion object {
        private val KEYWORD = Regex(
            """^(?:chapter|ch\.?|part|book|section|volume|scene|track|episode|prologue|epilogue|introduction|intro|interlude|foreword|afterword)\b.*$""",
            RegexOption.IGNORE_CASE,
        )
        // A colon, or a dash/en dash/em dash with a space on at least one side, so "Self-Portrait" stays whole.
        private val SEPARATOR = Regex("""\s*[-–—]\s+|\s+[-–—]\s*|\s*:\s*""")
        // A bare chapter or track number: one to three digits, or zero-padded like "002" (not a 4-digit year).
        private val NUMBER = Regex("""0\d+|\d{1,3}""")
        // "Chapter 16 Telling the Wheel": a numbered keyword, then the name, with only a space between.
        private val KEYWORD_SPACE = Regex(
            """^((?:chapter|ch\.?|part|book|section|volume|scene|track|episode)\s+\d+)\s+(\S.*)$""",
            RegexOption.IGNORE_CASE,
        )

        private fun isLabel(text: String) = KEYWORD.matches(text) || NUMBER.matches(text)

        fun of(title: String): ChapterHeading {
            val clean = title.trim()
            SEPARATOR.find(clean)?.let { m ->
                val before = clean.substring(0, m.range.first).trim()
                val after = clean.substring(m.range.last + 1).trim()
                if (after.isNotEmpty() && isLabel(before)) return ChapterHeading(before, after)
            }
            KEYWORD_SPACE.find(clean)?.let { return ChapterHeading(it.groupValues[1].trim(), it.groupValues[2].trim()) }
            return ChapterHeading(clean, null)
        }
    }
}
