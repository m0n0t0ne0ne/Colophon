package io.github.m0n0t0ne0ne.colophon

import io.github.m0n0t0ne0ne.colophon.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CoreTest {
    private fun atom(type: String, vararg body: ByteArray): ByteArray {
        val size = 8 + body.sumOf { it.size }
        val b = ByteBuffer.allocate(size)
        b.putInt(size); b.put(type.toByteArray(Charsets.ISO_8859_1)); body.forEach { b.put(it) }
        return b.array()
    }
    private fun ints(vararg v: Int) = ByteBuffer.allocate(v.size * 4).also { b -> v.forEach { b.putInt(it) } }.array()

    @Test fun neroChaptersInM4b() {
        val b = ByteArrayOutputStream()
        b.write(byteArrayOf(0, 0, 0, 0, 2))
        fun ch(ms: Long, t: String) {
            b.write(ByteBuffer.allocate(8).putLong(ms * 10_000).array()); b.write(t.length); b.write(t.toByteArray())
        }
        ch(0, "Prologue"); ch(90_000, "Chapter 1")
        val file = atom("ftyp", "M4B ".toByteArray()) + atom("moov", atom("udta", atom("chpl", b.toByteArray())))
        assertEquals(listOf(ParsedChapter(0, "Prologue"), ParsedChapter(90_000, "Chapter 1")), ChapterParser.parse(ArraySource(file)))
    }

    @Test fun quickTimeChapterTrack() {
        val sampleA = byteArrayOf(0, 5) + "Intro".toByteArray()
        val sampleB = byteArrayOf(0, 3) + "One".toByteArray()
        val ftyp = atom("ftyp", "M4B ".toByteArray())
        val mdat = atom("mdat", sampleA, sampleB)
        val offset = ftyp.size + 8
        val text = atom("trak",
            atom("tkhd", ByteArray(12), ints(2), ByteArray(8)),
            atom("mdia",
                atom("mdhd", ByteArray(12), ints(1000), ByteArray(8)),
                atom("hdlr", ByteArray(8), "text".toByteArray()),
                atom("minf", atom("stbl",
                    atom("stts", ByteArray(4), ints(1, 2, 60_000)),
                    atom("stsc", ByteArray(4), ints(1, 1, 2, 1)),
                    atom("stsz", ByteArray(4), ints(0, 2, sampleA.size, sampleB.size)),
                    atom("stco", ByteArray(4), ints(1, offset)),
                ))))
        val audio = atom("trak", atom("tkhd", ByteArray(12), ints(1), ByteArray(8)), atom("tref", atom("chap", ints(2))))
        val file = ftyp + mdat + atom("moov", audio, text)
        assertEquals(listOf(ParsedChapter(0, "Intro"), ParsedChapter(60_000, "One")), ChapterParser.parse(ArraySource(file)))
    }

    @Test fun id3ChapFrames() {
        fun frame(id: String, body: ByteArray) = id.toByteArray() + ints(body.size) + byteArrayOf(0, 0) + body
        val tit = frame("TIT2", byteArrayOf(3) + "Winter".toByteArray())
        val chap = frame("CHAP", "c1".toByteArray() + byteArrayOf(0) + ints(5000, 9000, -1, -1) + tit)
        val header = "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0, chap.size.toByte())
        assertEquals(listOf(ParsedChapter(5000, "Winter")), ChapterParser.parse(ArraySource(header + chap)))
    }

    @Test fun flacComments() {
        val c = listOf("CHAPTER001=00:01:02.500", "CHAPTER001NAME=Dawn")
        val b = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0); b.putInt(c.size)
        c.forEach { b.putInt(it.length); b.put(it.toByteArray()) }
        val body = b.array().copyOf(b.position())
        val h = byteArrayOf(0x84.toByte(), 0, (body.size shr 8).toByte(), body.size.toByte())
        assertEquals(listOf(ParsedChapter(62_500, "Dawn")), ChapterParser.parse(ArraySource("fLaC".toByteArray() + h + body)))
    }

    @Test fun cueSheet() {
        val cue = "FILE \"a.mp3\" MP3\n TRACK 01 AUDIO\n  TITLE \"Start\"\n  INDEX 01 00:00:00\n TRACK 02 AUDIO\n  TITLE \"Next\"\n  INDEX 01 02:30:00\n"
        assertEquals(listOf(ParsedChapter(0, "Start"), ParsedChapter(150_000, "Next")), CueSheet.parse(cue))
    }

    @Test fun naturalSortPutsTwoBeforeTen() {
        assertEquals(listOf("Track 1", "Track 2", "Track 10"), listOf("Track 10", "Track 1", "Track 2").sortedWith(NaturalOrder))
    }

    @Test fun guessesTheFileNameFromYourLibrary() {
        val g = MetadataGuess.guess("2013 - A Memory of Light Wheel of Time, Book 14 (Unabridged).m4b", "The Wheel of Time")
        assertEquals("A Memory of Light", g.title)
        assertEquals("The Wheel of Time", g.series)
        assertEquals(14.0, g.number!!, 0.0)
        assertEquals(2013, g.year)
    }

    private fun words(vararg s: String): List<Word> = s.mapIndexed { i, w -> Word(w, i * 0.4, i * 0.4 + 0.35) }

    @Test fun numberWords() {
        assertEquals(37 to 2, TitleExtractor.parseNumber(listOf("thirty", "seven", "the")))
        assertEquals(12 to 1, TitleExtractor.parseNumber(listOf("twelve")))
        assertEquals(101 to 4, TitleExtractor.parseNumber(listOf("one", "hundred", "and", "one")))
        assertNull(TitleExtractor.parseNumber(listOf("the")))
    }

    @Test fun headingWithTitleEndingAtPause() {
        val w = listOf(
            Word("chapter", 0.0, .4), Word("five", .45, .7), Word("winter", 1.5, 1.8), Word("night", 1.85, 2.1),
            Word("the", 3.5, 3.6), Word("wind", 3.65, 3.9),
        )
        val h = TitleExtractor.extract(w)!!
        assertEquals("Chapter 5", h.label); assertEquals("Winter Night", h.name); assertNull(h.reason)
    }

    @Test fun epilogueWithoutANumberIsFound() {
        assertEquals(SectionKind.EPILOGUE, TitleExtractor.extract(words("epilogue"))!!.kind)
    }

    @Test fun midSentenceChapterIsFlagged() {
        assertNotNull(TitleExtractor.extract(words("as", "we", "saw", "in", "chapter", "three"))!!.reason)
        assertNull(TitleExtractor.extract(words("he", "walked", "to", "the", "river", "chapter", "three")))
    }

    @Test fun narrationIsNotAHeading() {
        assertNull(TitleExtractor.extract(words("the", "wind", "rose", "over", "the", "hills")))
    }

    private fun cand(ms: Long, n: Int?, pause: Long = 2000) = Candidate(
        ms, pause, n?.let { Heading(SectionKind.CHAPTER, it, "Chapter $it", "", null) }, "",
    )

    @Test fun buildKeepsTimelineOrderAndFlagsGaps() {
        val r = ChapterBuilder.build(listOf(cand(300_000, 3), cand(100_000, 1), cand(200_000, 2), cand(500_000, 5)), 600_000)
        assertEquals(listOf("Opening", "Chapter 1", "Chapter 2", "Chapter 3", "Chapter 5"), r.map { it.title })
        assertEquals(r.map { it.startMs }, r.map { it.startMs }.sorted())
        assertEquals("Chapter 4 was not found", r.last().reason)
    }

    @Test fun partsMayRestartNumbering() {
        val part = Candidate(150_000, 2000, Heading(SectionKind.PART, 2, "Part 2", "", null), "")
        val r = ChapterBuilder.build(listOf(cand(0, 1), cand(100_000, 2), part, cand(200_000, 1)), 300_000)
        assertTrue(r.all { it.reason == null })
    }

    @Test fun shortUnlabeledPausesAreDropped() {
        val r = ChapterBuilder.build(listOf(cand(0, 1), cand(90_000, null, 1600), cand(200_000, null, 5000)), 300_000)
        assertEquals(listOf("Chapter 1", "Untitled Section 1"), r.map { it.title })
    }

    @Test fun pauseDetectorFindsSilence() {
        val d = PauseDetector()
        repeat(100) { d.feed(0.2) }
        repeat(30) { d.feed(0.001) }
        repeat(100) { d.feed(0.2) }
        repeat(5) { d.feed(0.001) }
        repeat(50) { d.feed(0.2) }
        d.finish()
        assertEquals(listOf(Pause(10_000, 13_000)), d.pauses)
    }

    @Test fun timelineCrossesFiles() {
        val files = listOf(1000L, 2000L, 3000L)
        assertEquals(1 to 500L, Timeline.locate(files, 1500))
        assertEquals(2500L, Timeline.global(files, 2, 0) - 500)
        assertEquals(1, Timeline.chapterAt(listOf(0, 100, 200), 150))
    }

    @Test fun rewindGrowsWithTimeAway() {
        assertTrue(Resume.rewindMs(10_000) < Resume.rewindMs(100_000_000))
    }
}

class JumpHistoryTest {
    @org.junit.Test fun keepsRealJumpsAndDropsJunk() {
        val total = 9_200_000L
        // A genuine jump of more than a minute from a mid-book spot is remembered.
        assertTrue(JumpHistory.shouldRemember(fromMs = 2_300_000, toMs = 100_000, totalMs = total, lastMs = null))
        // Tiny moves are not jumps.
        assertFalse(JumpHistory.shouldRemember(fromMs = 2_300_000, toMs = 2_280_000, totalMs = total, lastMs = null))
        // The spurious 0:00:00 and end-of-book entries are dropped.
        assertFalse(JumpHistory.shouldRemember(fromMs = 0, toMs = 2_000_000, totalMs = total, lastMs = null))
        assertFalse(JumpHistory.shouldRemember(fromMs = total, toMs = 1_000_000, totalMs = total, lastMs = null))
        // A repeat of the last remembered spot is not stacked.
        assertFalse(JumpHistory.shouldRemember(fromMs = 2_301_000, toMs = 100_000, totalMs = total, lastMs = 2_300_000))
        // Unknown duration still allows a real jump.
        assertTrue(JumpHistory.shouldRemember(fromMs = 500_000, toMs = 100_000, totalMs = 0, lastMs = null))
    }
}

class ResumeRewindTest {
    @org.junit.Test fun longAwayUsesTheSetting() {
        assertEquals(2_000L, Resume.rewindMs(30_000, 15))           // under a minute: small nudge
        assertEquals(5_000L, Resume.rewindMs(120_000, 15))          // under five minutes
        assertEquals(15_000L, Resume.rewindMs(600_000, 15))         // long away: the setting
        assertEquals(60_000L, Resume.rewindMs(3_600_000, 60))       // a different setting
        assertEquals(0L, Resume.rewindMs(3_600_000, 0))             // off
    }
}

class ChapterNumberingTest {
    @org.junit.Test fun padsToThreeDigits() {
        val out = ChapterNumbering.padTitles(listOf("2 - Death Cookie", "16 - Scene", "110 - End", "Chapter 3"), 3)
        assertEquals(listOf("002 - Death Cookie", "016 - Scene", "110 - End", "Chapter 003"), out)
    }
    @org.junit.Test fun padsToTwoDigitsAndKeepsLongerNumbersWhole() {
        val out = ChapterNumbering.padTitles(listOf("2 - A", "60 - B", "110 - C"), 2)
        assertEquals(listOf("02 - A", "60 - B", "110 - C"), out)
    }
    @org.junit.Test fun leavesTitlesWithoutLeadingNumbersAlone() {
        val out = ChapterNumbering.padTitles(listOf("5 - A", "Prologue", "The 3 Musketeers"), 2)
        assertEquals(listOf("05 - A", "Prologue", "The 3 Musketeers"), out)
    }
    @org.junit.Test fun isIdempotent() {
        val once = ChapterNumbering.padTitles(listOf("2 - A", "100 - B"), 3)
        assertEquals(once, ChapterNumbering.padTitles(once, 3))
    }
}
