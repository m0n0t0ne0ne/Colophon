package io.github.m0n0t0ne0ne.colophon

import io.github.m0n0t0ne0ne.colophon.core.*
import org.junit.Assert.assertEquals
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
}
