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

    @Test fun chapterKeepsTitleAcrossADeliberatePauseBeforeIt() {
        // "Chapter One. [long pause] The Early Married Life of the Morels." (Sons and Lovers)
        val h = TitleExtractor.extract(timed(
            "chapter" to 0.0, "one" to 0.3, "the" to 2.6, "early" to 0.05, "married" to 0.05,
            "life" to 0.05, "of" to 0.05, "the" to 0.05, "morels" to 0.05, "when" to 0.8,
        ))!!
        assertEquals(SectionKind.CHAPTER, h.kind)
        assertEquals(1, h.number)
        assertEquals("Chapter 1", h.label)
        assertEquals("The Early Married Life of the Morels", h.name)
    }

    @Test fun headingAfterFillerWordsIsStillFound() {
        // Model artefacts prepended filler, pushing "chapter" past the tight 5-word window; every word
        // before it is filler, so the heading is still found (and flagged as heard mid-sentence).
        val h = TitleExtractor.extract(spoken("well and so then the chapter eleven strife in love"))!!
        assertEquals(11, h.number)
        assertEquals("Chapter 11", h.label)
        assertEquals("\"Chapter\" heard mid-sentence?", h.reason)
    }

    @Test fun contentWordsBeforeKeywordAreNotAHeading() {
        // "…walked to the river chapter three" is narration — content words precede the keyword.
        assertNull(TitleExtractor.extract(spoken("he walked to the river chapter three began")))
    }

    @Test fun archaicChapterTheFirstParses() {
        val h = TitleExtractor.extract(timed(
            "chapter" to 0.0, "the" to 0.3, "first" to 0.3, "the" to 1.0, "early" to 0.05, "years" to 0.05, "it" to 0.8,
        ))!!
        assertEquals(1, h.number)
        assertEquals("Chapter 1", h.label)
        assertEquals("The Early Years", h.name)
    }

    @Test fun relocatesAnEarlyMarkOntoTheHeadingAfterThePause() {
        // "…and she was gone. [long pause] Chapter Two. The Trial of Paul." — the mark landed in the
        // previous chapter's tail, so the heading sits after the inter-chapter pause.
        val words = timed(
            "and" to 0.0, "she" to 0.05, "was" to 0.05, "gone" to 0.05,
            "chapter" to 2.6, "two" to 0.3, "the" to 1.0, "trial" to 0.05, "of" to 0.05, "paul" to 0.05, "when" to 0.8,
        )
        val (offset, heading) = HeadingLocator.relocate(words)!!
        assertEquals(2, heading.number)
        assertEquals("Chapter 2", heading.label)
        assertEquals("The Trial of Paul", heading.name)
        assertEquals(3.95, offset, 0.001) // snaps to the first word after the pause
    }

    @Test fun doesNotRelocateToAPauseWithNoHeadingAfterIt() {
        val words = timed(
            "chapter" to 0.0, "two" to 0.3, "the" to 0.5, "trial" to 0.05, "began" to 0.05, "slowly" to 0.05,
            "the" to 2.2, "rain" to 0.05, "fell" to 0.05,
        )
        assertNull(HeadingLocator.relocate(words))
    }

    @Test fun overlappingScanSegmentsJoinPauses() {
        val merged = PauseMerger.merge(listOf(Pause(10_000, 12_000), Pause(11_000, 13_000), Pause(50_000, 52_000)))
        assertEquals(listOf(Pause(10_000, 13_000), Pause(50_000, 52_000)), merged)
    }
}
