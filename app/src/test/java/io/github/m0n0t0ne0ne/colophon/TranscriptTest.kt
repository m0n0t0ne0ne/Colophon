package io.github.m0n0t0ne0ne.colophon

import io.github.m0n0t0ne0ne.colophon.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Word lists below are what the bundled speech model actually returned for spoken test audio. */
class TranscriptTest {
    private fun spoken(line: String) = line.split(" ").mapIndexed { i, w -> Word(w, i * 0.4, i * 0.4 + 0.35) }

    @Test fun fullPipelineFindsSectionsAndTheMissingChapter() {
        val heard = listOf(
            4_000L to "prologue that why to tied but when moved slowly",
            7_400L to "that wind move slowly over the long grass and",
            24_000L to "chapter one verse old road but when moved slowly",
            27_600L to "that wind moved slowly over the long grass and",
            44_200L to "chapter two mohammad en marche that wind move slowly",
            64_500L to "chapter four winter road but wind moved slowly over",
            84_400L to "epilogue going though that wind move slowly over the",
        )
        val candidates = heard.map { (at, text) ->
            Candidate(at, 2_000, TitleExtractor.extract(spoken(text)), text.split(" ").take(5).joinToString(" "))
        }
        val result = ChapterBuilder.build(candidates, 100_000)
        assertEquals(
            listOf("Opening", "Prologue", "Chapter 1", "Chapter 2", "Chapter 4", "Epilogue"),
            result.map { it.title },
        )
        assertEquals("Chapter 3 was not found", result[4].reason)
        assertEquals(SectionKind.EPILOGUE, result.last().kind)
    }

    @Test fun markerKeepsItsLabelWhenNoHeadingIsHeard() {
        val r = ChapterBuilder.build(listOf(
            Candidate(0, 0, null, "the wind", "Chapter 37-1"),
            Candidate(900_000, 0, null, "and then", "Chapter 37-2"),
            Candidate(1_800_000, 0, TitleExtractor.extract(spoken("chapter thirty eight")), "chapter thirty eight", "Chapter 37-3"),
        ), 3_000_000)
        assertEquals(listOf("Chapter 37-1", "Chapter 37-2", "Chapter 38"), r.map { it.title })
    }

    // Build words with an explicit gap (seconds) before each one.
    private fun timed(vararg parts: Pair<String, Double>): List<Word> {
        var t = 0.0
        return parts.map { (text, gap) -> t += gap; Word(text, t, t + 0.3).also { t += 0.3 } }
    }

    @Test fun bareSpokenNumberThenSceneNameBecomesAChapter() {
        val h = TitleExtractor.extract(timed(
            "two" to 0.0, "death" to 0.7, "cookie" to 0.05, "the" to 0.8, "city" to 0.05, "slept" to 0.05,
        ))!!
        assertEquals(SectionKind.CHAPTER, h.kind)
        assertEquals(2, h.number)
        assertEquals("Death Cookie", h.name)
        assertEquals("2", h.label) // no spoken "Chapter", so just the number
    }

    @Test fun aNumberInsideNarrationIsNotAChapter() {
        // "two men walked in" — no pause after the number, so it is not a heading.
        assertNull(TitleExtractor.extract(timed("two" to 0.0, "men" to 0.05, "walked" to 0.05, "in" to 0.05)))
    }

    @Test fun overlappingScanSegmentsJoinPauses() {
        val merged = PauseMerger.merge(listOf(Pause(10_000, 12_000), Pause(11_000, 13_000), Pause(50_000, 52_000)))
        assertEquals(listOf(Pause(10_000, 13_000), Pause(50_000, 52_000)), merged)
    }
}
