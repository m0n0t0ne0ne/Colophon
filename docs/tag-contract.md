# Tag contract: AudiobookStudio → Colophon (version 1)

AudiobookStudio (the Windows tagging app) writes book details into the audio file. Colophon reads them,
so a book you prepared on the PC shows up on the phone with nothing left to fix. This file is the shared
reference. Both apps must follow it, and `TagContractTest` checks the Colophon side against files
AudiobookStudio actually wrote.

## What the file carries

| Meaning | M4B / M4A (MP4 atom) | MP3 (ID3v2.3 or 2.4 frame) | Colophon field |
|---|---|---|---|
| Book title | `©nam` | `TIT2` | title |
| Album (= book title) | `©alb` | `TALB` | title for multi-file books |
| Author | `aART`, else `©ART` | `TPE2`, else `TPE1` | author |
| Year | `©day` (first 4 digits) | `TYER` / `TDRC` | year |
| **Series** | `----:com.apple.iTunes:SERIES` | `TXXX:SERIES` | series |
| **Book number** | `----:com.apple.iTunes:SERIES-PART` (text, e.g. `1`, `1.5`) | `TXXX:SERIES-PART` | seriesNumber |
| **Marker** | `----:com.apple.iTunes:COLOPHON` = `1` | `TXXX:COLOPHON` = `1` | (see below) |
| Series, other tools | `©mvn` / `©mvi` (integer) | `MVNM` / `MVIN` (`2` or `2/6`) | fallback |
| Grouping | `©grp` = `Series, Book 1` | `TIT1` | last fallback |
| Cover | `covr` | `APIC` (front cover) | cover |
| Chapters | QuickTime chapter track / Nero `chpl` | `CHAP` + `CTOC` | chapters |
| Narrator | `©wrt` | `TCOM` | not shown yet |

**The series is resolved in this order:** `SERIES`, then movement name (`©mvn`/`MVNM`), then a grouping
written as "Name, Book N". The book number follows the same order: `SERIES-PART`, then movement number,
then the grouping's number. Missing tags fall back to the next source in the list.

**The marker** (`COLOPHON` = `1`) means "these details were set on purpose". AudiobookStudio writes it on
every file it saves. The value is the contract version, so a future version 2 can be told apart.

## How Colophon uses them

A file's tags are **trusted** when it has the marker or names a series.

| Situation | Title / author | Series / book number | Year |
|---|---|---|---|
| Marker present | tags (blank tag → folder/file-name guess) | tags. No series tag means **no series**, whatever the folder is called | tag, else guess |
| Series tag but no marker (other taggers) | folder/file-name guess, minus the series name | tags | tag, else guess |
| Neither (most existing files) | unchanged: folder/file-name guess | unchanged | unchanged |
| You edited the details in Colophon (`userEdited`) | never overwritten | never overwritten | never overwritten |

Books whose details came from tags are stored with `fromTags = true`. Series tidying
(`SeriesConsolidator`) and the series-name trimming (`BookNames.withoutSeries`) leave those books alone.

## Re-editing a book that's already on the phone

Colophon stores each book's file size and date (`fileStamp`). When a library refresh sees them change
(the file was re-saved, for example by AudiobookStudio), it reads the file again:

- **Details** (title, author, series, year) come from the tags again, unless you edited them in Colophon.
- **Chapters:** the file's chapters replace the originals. Your *current* chapters follow them only if
  you never edited chapters in Colophon. Edited chapters are kept, and "Reset chapters" brings in the
  file's version.
- **Cover:** the embedded cover replaces the old one, unless you picked a cover in Colophon. A picture
  in the book's folder still takes priority, as before.
- **Place:** your position is kept. If the file got shorter, it's clamped to the end.

**Reload from file** (Book details) forgets your Colophon edits for that book and takes everything from
the file: details, cover and chapters. Use it once on books you fixed by hand before this version.

The first refresh after upgrading records every book's stamp without reloading chapters or covers, and
applies series tags to books you haven't edited. Single-file books (added with "Add files" rather than
a folder) are checked on refresh too.

## Chapter titles

Colophon shows a chapter on two lines when a **label** comes before `:` or a dash with a space beside it:

- `Chapter Three - The Letters from No One` → **CHAPTER THREE** / The Letters from No One
- `Chapter Twenty-Two: The Long Road` → **CHAPTER TWENTY-TWO** / The Long Road (the hyphen in
  "Twenty-Two" has no spaces, so it never splits)
- `022 - The Long Road`, `Twenty-Two: The Long Road` → a number on its own (digits or written out) is a label
- `Chapter Twenty-Two`, `Self-Portrait` → one line

AudiobookStudio's **Format Chapter Titles** writes titles in these shapes.
