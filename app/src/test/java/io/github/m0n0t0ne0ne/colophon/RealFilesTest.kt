package io.github.m0n0t0ne0ne.colophon

import io.github.m0n0t0ne0ne.colophon.core.ArraySource
import io.github.m0n0t0ne0ne.colophon.core.ChapterParser
import io.github.m0n0t0ne0ne.colophon.core.ParsedChapter
import org.junit.Assert.assertEquals
import org.junit.Test

/** These files were written by FFmpeg, so the parsers are checked against real-world output. */
class RealFilesTest {
    private val expected = listOf(
        ParsedChapter(0, "Prologue"), ParsedChapter(30_000, "Chapter One"), ParsedChapter(75_000, "Epilogue"),
    )

    private fun parse(ext: String) =
        ChapterParser.parse(ArraySource(javaClass.getResourceAsStream("/fixtures/sample.$ext")!!.readBytes()))

    @Test fun m4b() = assertEquals(expected, parse("m4b"))
    @Test fun mp3() = assertEquals(expected, parse("mp3"))
    @Test fun flac() = assertEquals(expected, parse("flac"))
    @Test fun ogg() = assertEquals(expected, parse("ogg"))
    @Test fun opus() = assertEquals(expected, parse("opus"))
}
