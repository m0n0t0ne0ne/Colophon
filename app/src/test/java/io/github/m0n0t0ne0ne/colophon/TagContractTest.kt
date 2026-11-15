package io.github.m0n0t0ne0ne.colophon

import io.github.m0n0t0ne0ne.colophon.core.ArraySource
import io.github.m0n0t0ne0ne.colophon.core.BookNames
import io.github.m0n0t0ne0ne.colophon.core.BookTags
import io.github.m0n0t0ne0ne.colophon.core.ChapterHeading
import io.github.m0n0t0ne0ne.colophon.core.ChapterParser
import io.github.m0n0t0ne0ne.colophon.core.ParsedChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tag contract with AudiobookStudio (docs/tag-contract.md). The tagged.* fixtures were written by
 * AudiobookStudio itself, so these tests fail if either side drifts from the contract.
 */
class TagContractTest {
    private fun bytes(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes()
    private fun tags(name: String) = BookTags.parse(ArraySource(bytes(name)))

    @Test fun readsSeriesAndBookFromM4b() {
        val t = tags("tagged.m4b")
        assertEquals("Harry Potter and the Philosopher's Stone", t.title)
        assertEquals("J.K. Rowling", t.author)
        assertEquals("Harry Potter", t.series)
        assertEquals(1.0, t.seriesNumber!!, 0.0)
        assertEquals(1997, t.year)
        assertTrue(t.curated)
        assertTrue(t.trusted)
    }

    @Test fun readsSeriesAndDecimalBookFromMp3() {
        val t = tags("tagged.mp3")
        assertEquals("Harry Potter and the Philosopher's Stone", t.title)
        assertEquals("J.K. Rowling", t.author)
        assertEquals("Harry Potter", t.series)
        assertEquals(1.5, t.seriesNumber!!, 0.0)
        assertEquals(1997, t.year)
        assertTrue(t.curated)
    }

    @Test fun aCuratedBookWithoutASeriesHasNone() {
        val t = tags("tagged_standalone.m4b")
        assertEquals("Piranesi", t.title)
        assertEquals("Susanna Clarke", t.author)
        assertNull(t.series)
        assertNull(t.seriesNumber)
        assertTrue(t.curated)
    }

    @Test fun untaggedFilesBehaveAsBefore() {
        for (ext in listOf("m4b", "mp3", "flac", "ogg", "opus")) {
            val t = tags("sample.$ext")
            assertFalse(t.curated)
            assertFalse(t.trusted)
        }
    }

    @Test fun otherToolsSeriesConventionsAreUnderstood() {
        // grouping only, as many taggers write it
        assertEquals("The Expanse" to 3.0, BookTags.build(mapOf("GROUPING" to "The Expanse, Book 3")).let { it.series to it.seriesNumber })
        // movement name / number (iTunes, Mp3tag)
        assertEquals("Dune" to 2.0, BookTags.build(mapOf("MVNM" to "Dune", "MVIN" to "2/6")).let { it.series to it.seriesNumber })
        // SERIES wins over the others
        assertEquals("Mistborn", BookTags.build(mapOf("SERIES" to "Mistborn", "MVNM" to "Other", "GROUPING" to "X, Book 9")).series)
    }

    @Test fun chaptersFromAudiobookStudioSplitForThePlayer() {
        val expected = listOf(
            ParsedChapter(0, "Opening Credits"),
            ParsedChapter(10_000, "Chapter One: The Boy Who Lived"),
            ParsedChapter(20_000, "Chapter Twenty-Two - The Long Road"),
        )
        assertEquals(expected, ChapterParser.parse(ArraySource(bytes("tagged.m4b"))))
        assertEquals(expected, ChapterParser.parse(ArraySource(bytes("tagged.mp3"))))
        fun split(t: String) = ChapterHeading.of(t).let { it.label to it.name }
        assertEquals("Chapter One" to "The Boy Who Lived", split("Chapter One: The Boy Who Lived"))
        assertEquals("Chapter Twenty-Two" to "The Long Road", split("Chapter Twenty-Two - The Long Road"))
        assertEquals("Chapter Twenty-Two" to null, split("Chapter Twenty-Two"))
        // a written number on its own is a label too
        assertEquals("Twenty-Two" to "The Long Road", split("Twenty-Two: The Long Road"))
        assertEquals("One Hundred and Five" to "The End", split("One Hundred and Five - The End"))
        assertEquals("Seventeen Magpies" to null, split("Seventeen Magpies"))
    }

    @Test fun aSeriesNameThatIsPartOfTheTitleStays() {
        assertEquals("Harry Potter and the Philosopher's Stone", BookNames.withoutSeries("Harry Potter and the Philosopher's Stone", "Harry Potter"))
        assertEquals("The Lord of the Rings", BookNames.withoutSeries("The Lord of the Rings", "Rings"))
        assertEquals("The Chamber of Secrets", BookNames.withoutSeries("Harry Potter - The Chamber of Secrets", "Harry Potter"))
        assertEquals("The Final Empire", BookNames.withoutSeries("Mistborn The Final Empire", "Mistborn"))
        assertEquals("Messiah", BookNames.withoutSeries("Dune Messiah", "Dune"))
    }
}
