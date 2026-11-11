package io.github.m0n0t0ne0ne.colophon.core

import kotlin.math.abs

enum class SectionKind(val label: String) {
    CHAPTER("Chapter"), PROLOGUE("Prologue"), EPILOGUE("Epilogue"), INTRO("Intro"), PART("Part"), OTHER("Other")
}

data class Pause(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
}

/**
 * Finds longer pauses in a stream of loudness frames. The threshold follows the narrator's level,
 * so a quiet recording and a loud recording are treated the same way.
 */
class PauseDetector(private val frameMs: Long = 100, private val minPauseMs: Long = 1500) {
    private var speech = 0.0
    private var position = 0L
    val positionMs: Long get() = position
    private var silentSince = -1L
    val pauses = mutableListOf<Pause>()

    fun feed(rms: Double) {
        if (rms > speech) speech = speech * 0.7 + rms * 0.3 else speech = speech * 0.9995 + rms * 0.0005
        val silent = rms < speech * 0.08 || rms < 1e-4
        if (silent) {
            if (silentSince < 0) silentSince = position
        } else {
            closePause()
        }
        position += frameMs
    }

    fun finish() = closePause()

    private fun closePause() {
        if (silentSince >= 0 && position - silentSince >= minPauseMs) pauses += Pause(silentSince, position)
        silentSince = -1
    }
}

object PauseMerger {
    /** Joins pauses that overlap, which happens where scan segments meet. */
    fun merge(pauses: List<Pause>): List<Pause> {
        val out = mutableListOf<Pause>()
        pauses.sortedBy { it.startMs }.forEach { p ->
            val last = out.lastOrNull()
            if (last != null && p.startMs <= last.endMs + 200) {
                out[out.lastIndex] = Pause(last.startMs, maxOf(last.endMs, p.endMs))
            } else out += p
        }
        return out
    }
}

data class Word(val text: String, val startSec: Double, val endSec: Double)

data class Heading(val kind: SectionKind, val number: Int?, val label: String, val name: String, val reason: String?)

object TitleExtractor {
    private val units = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private val ordinals = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
        "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10, "eleventh" to 11, "twelfth" to 12,
        "thirteenth" to 13, "fourteenth" to 14, "fifteenth" to 15, "sixteenth" to 16, "seventeenth" to 17,
        "eighteenth" to 18, "nineteenth" to 19, "twentieth" to 20,
    )
    private val named = listOf(
        listOf("prologue") to (SectionKind.PROLOGUE to "Prologue"),
        listOf("epilogue") to (SectionKind.EPILOGUE to "Epilogue"),
        listOf("epilog") to (SectionKind.EPILOGUE to "Epilogue"),
        listOf("introduction") to (SectionKind.INTRO to "Introduction"),
        listOf("foreword") to (SectionKind.INTRO to "Foreword"),
        listOf("forward") to (SectionKind.INTRO to "Foreword"),
        listOf("preface") to (SectionKind.INTRO to "Preface"),
        listOf("interlude") to (SectionKind.OTHER to "Interlude"),
        listOf("afterword") to (SectionKind.OTHER to "Afterword"),
        listOf("acknowledgments") to (SectionKind.OTHER to "Acknowledgments"),
        listOf("acknowledgements") to (SectionKind.OTHER to "Acknowledgments"),
        listOf("about", "the", "author") to (SectionKind.OTHER to "About the Author"),
        listOf("authors", "note") to (SectionKind.OTHER to "Author's Note"),
        listOf("author's", "note") to (SectionKind.OTHER to "Author's Note"),
        listOf("glossary") to (SectionKind.OTHER to "Glossary"),
    )
    // Named sections (prologue, foreword, …) are announced right at the top, so keep their scan tight.
    private const val HEADING_WINDOW = 5
    // "Chapter / Part / Book N" must still be near the break, but the speech model often drops a filler
    // token first ("and chapter one…"), so scan a little deeper and flag anything heard mid-sentence.
    private const val KEYWORD_WINDOW = 10
    // Words that may sit before a heading without disqualifying it (model artefacts or a lead-in breath).
    private val FILLER = setOf(
        "a", "an", "and", "the", "of", "to", "in", "on", "at", "but", "so", "now", "then",
        "well", "oh", "um", "uh", "er", "as", "we", "i", "he", "she", "it", "that", "this", "there",
    )
    private const val TITLE_GAP_SEC = 0.55
    private const val MAX_TITLE_WORDS = 8
    // A spoken chapter number is followed by a deliberate pause before the scene name.
    private const val NUMBER_HEADING_GAP = 0.4

    fun parseNumber(all: List<String>): Pair<Int, Int>? {
        if (all.isEmpty()) return null
        // Allow an article before the number, as in the archaic "Chapter the First".
        val skip = if (all[0] == "the" && all.size > 1) 1 else 0
        val words = all.drop(skip)
        if (words.isEmpty()) return null
        words[0].toIntOrNull()?.let { return it to (1 + skip) }
        ordinals[words[0]]?.let { return it to (1 + skip) }
        var i = 0
        var value = 0
        var used = false
        if (units.containsKey(words[0]) && words.getOrNull(1) == "hundred") {
            value = units.getValue(words[0]) * 100
            i = 2
            used = true
            if (words.getOrNull(i) == "and") i++
        }
        tens[words.getOrNull(i)]?.let { value += it; i++; used = true }
        val unit = units[words.getOrNull(i)]
        if (unit != null && (unit < 10 || value % 100 == 0)) { value += unit; i++; used = true }
        return if (used) value to (i + skip) else null
    }

    fun extract(words: List<Word>): Heading? {
        if (words.isEmpty()) return null
        val lower = words.map { it.text.lowercase() }

        for ((key, value) in named) {
            val at = indexOf(lower, key)
            if (at in 0 until HEADING_WINDOW) {
                val rest = titleAfter(words, at + key.size)
                val reason = if (at > 2) "Heading heard mid-sentence?" else null
                return Heading(value.first, null, value.second, rest, reason)
            }
        }

        for ((keyword, kind, label) in listOf(
            Triple("chapter", SectionKind.CHAPTER, "Chapter"),
            Triple("chapters", SectionKind.CHAPTER, "Chapter"),
            Triple("part", SectionKind.PART, "Part"),
            Triple("book", SectionKind.PART, "Book"),
        )) {
            val at = lower.indexOf(keyword)
            if (at !in 0 until KEYWORD_WINDOW) continue
            // Past the tight window, only accept the heading when every word before it is filler the
            // speech model may have prepended (an article, conjunction or discourse word). That lets a
            // dropped "and"/"the" through without mistaking "…to the river chapter three" for a heading.
            if (at >= HEADING_WINDOW && !lower.subList(0, at).all { it in FILLER }) continue
            val parsed = parseNumber(lower.drop(at + 1)) ?: continue
            val (number, used) = parsed
            val rest = titleAfter(words, at + 1 + used)
            val reason = if (at > 2) "\"$label\" heard mid-sentence?" else null
            return Heading(kind, number, "$label $number", rest, reason)
        }

        // Some books (numbered scenes) announce just the number, then pause, then the scene name:
        // "Two. Death Cookie". The pause after the number separates it from ordinary narration. A
        // single filler word may precede the number ("…to Nine. Defeat of Miriam"), so try that too.
        val numFrom = if (lower.firstOrNull() in FILLER) 1 else 0
        parseNumber(lower.drop(numFrom))?.let { (number, usedRel) ->
            val used = usedRel + numFrom
            if (used < words.size && words[used].startSec - words[used - 1].endSec >= NUMBER_HEADING_GAP) {
                val name = phraseAfter(words, used)
                // The narrator didn't say "Chapter", so label it with the number alone.
                if (name.isNotBlank()) return Heading(SectionKind.CHAPTER, number, "$number", name, null)
            }
        }
        return null
    }

    /** The scene name spoken after a number's pause, ending at the next pause. */
    private fun phraseAfter(words: List<Word>, from: Int): String {
        if (from >= words.size) return ""
        val out = mutableListOf(words[from].text)
        var i = from + 1
        var sawGap = false
        while (i < words.size && out.size < MAX_TITLE_WORDS) {
            if (words[i].startSec - words[i - 1].endSec >= TITLE_GAP_SEC) { sawGap = true; break }
            out += words[i].text
            i++
        }
        return if (sawGap) titleCase(out) else ""
    }

    private fun indexOf(words: List<String>, key: List<String>): Int {
        for (i in 0..words.size - key.size) if (key.indices.all { words[i + it] == key[it] }) return i
        return -1
    }

    /** The narrator normally pauses after a title, so the title ends at the first real gap. */
    private fun titleAfter(words: List<Word>, from: Int): String {
        if (from >= words.size) return ""
        // The narrator often leaves a deliberate pause between "Chapter One" and the title line; allow
        // a generous gap before giving up, but not so long that we pull in the chapter's first sentence.
        if (words[from].startSec - words[from - 1].endSec > 3.5) return ""
        val out = mutableListOf(words[from].text)
        var i = from + 1
        var sawGap = false
        while (i < words.size && out.size < MAX_TITLE_WORDS) {
            if (words[i].startSec - words[i - 1].endSec >= TITLE_GAP_SEC) { sawGap = true; break }
            out += words[i].text
            i++
        }
        if (!sawGap) return ""
        return titleCase(out)
    }

    private val small = setOf("a", "an", "and", "at", "but", "by", "for", "in", "of", "on", "or", "the", "to", "upon", "with")

    fun titleCase(words: List<String>): String = words.mapIndexed { i, w ->
        if (i != 0 && w.lowercase() in small) w.lowercase()
        else w.lowercase().replaceFirstChar { it.titlecase() }
    }.joinToString(" ")
}

object HeadingLocator {
    private const val MIN_GAP_SEC = 2.0

    /**
     * When a chapter mark sits a few seconds early — in the tail of the previous chapter — the
     * transcribed window reads as "…previous-chapter tail [long pause] Chapter Two…". This finds that
     * deliberate pause and checks a heading is spoken right after it, so the mark can be snapped onto
     * the heading instead of the tail. Returns the pause-end offset (seconds into the window) and the
     * heading found there, or null to keep the mark where it is.
     */
    fun relocate(words: List<Word>): Pair<Double, Heading>? {
        if (words.size < 2) return null
        var bestIdx = -1
        var bestGap = MIN_GAP_SEC
        for (i in 1 until words.size) {
            val gap = words[i].startSec - words[i - 1].endSec
            if (gap >= bestGap) { bestGap = gap; bestIdx = i }
        }
        if (bestIdx < 0) return null
        val heading = TitleExtractor.extract(words.subList(bestIdx, words.size)) ?: return null
        return words[bestIdx].startSec to heading
    }
}

data class Candidate(
    val startMs: Long,
    val pauseMs: Long,
    val heading: Heading?,
    val firstWords: String,
    /** The file's own label for this point, kept when no spoken heading is heard. */
    val fallbackTitle: String? = null,
)

data class Suggested(val startMs: Long, val title: String, val kind: SectionKind, val reason: String?)

object ChapterBuilder {
    private const val MERGE_WINDOW_MS = 20_000L
    private const val UNTITLED_MIN_PAUSE_MS = 3_500L

    fun build(candidates: List<Candidate>, durationMs: Long): List<Suggested> {
        val sorted = candidates.sortedBy { it.startMs }
        val kept = mutableListOf<Candidate>()
        for (c in sorted) {
            if (c.heading == null && c.fallbackTitle == null && c.pauseMs < UNTITLED_MIN_PAUSE_MS) continue
            val last = kept.lastOrNull()
            val bothHeadings = last?.heading != null && c.heading != null && last.heading.label != c.heading.label
            if (last != null && !bothHeadings && abs(c.startMs - last.startMs) < MERGE_WINDOW_MS) {
                if (last.heading == null && c.heading != null) kept[kept.lastIndex] = c
                continue
            }
            kept += c
        }

        val out = mutableListOf<Suggested>()
        if (kept.isEmpty() || kept.first().startMs > 1000) {
            out += Suggested(0, "Opening", SectionKind.OTHER, "Everything before the first heading")
        }
        var untitled = 0
        var lastChapter: Int? = null
        for (c in kept) {
            val h = c.heading
            if (h == null && c.fallbackTitle != null) {
                out += Suggested(c.startMs, c.fallbackTitle, SectionKind.OTHER, null)
                continue
            }
            if (h == null) {
                untitled++
                val hint = c.firstWords.takeIf { it.isNotBlank() }?.let { "Begins \"$it\"" } ?: "Clear pause but no title heard"
                out += Suggested(c.startMs, "Untitled Section $untitled", SectionKind.OTHER, hint)
                continue
            }
            var reason = h.reason
            if (h.kind == SectionKind.CHAPTER && h.number != null) {
                val previous = lastChapter
                if (previous != null) {
                    if (h.number == previous && reason == null) reason = "Repeats chapter $previous"
                    else if (h.number > previous + 1 && reason == null) {
                        reason = if (h.number == previous + 2) "Chapter ${previous + 1} was not found"
                        else "Chapters ${previous + 1} to ${h.number - 1} were not found"
                    } else if (h.number < previous && h.number != 1 && reason == null) reason = "Number is out of sequence"
                }
                lastChapter = h.number
            } else if (h.kind == SectionKind.PART) {
                lastChapter = null
            }
            val title = if (h.name.isBlank()) h.label else "${h.label}: ${h.name}"
            out += Suggested(c.startMs.coerceIn(0, durationMs), title, h.kind, reason)
        }
        return out
    }
}
