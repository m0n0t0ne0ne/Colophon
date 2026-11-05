package io.github.m0n0t0ne0ne.colophon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import io.github.m0n0t0ne0ne.colophon.core.SectionKind
import io.github.m0n0t0ne0ne.colophon.core.Timeline
import io.github.m0n0t0ne0ne.colophon.data.Book
import io.github.m0n0t0ne0ne.colophon.data.Bookmark
import io.github.m0n0t0ne0ne.colophon.data.Chapter

private fun hms(ms: Long): String {
    val t = ms.coerceAtLeast(0) / 1000
    return "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
}

@Composable
fun ChaptersContent(
    current: List<Chapter>,
    original: List<Chapter>,
    bookmarks: List<Bookmark>,
    positionMs: Long,
    hasReview: Boolean,
    onBack: () -> Unit,
    onPlayAt: (Long) -> Unit,
    onEdit: (Chapter) -> Unit,
    onAnalyze: () -> Unit,
    onReview: () -> Unit,
    onAddHere: () -> Unit,
    onReset: () -> Unit,
    onDeleteBookmark: (Long) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val currentIndex = if (current.isEmpty()) 0 else Timeline.chapterAt(current.map { it.startMs }, positionMs)
    LaunchedEffect(current.size) { if (current.isNotEmpty()) listState.scrollToItem((currentIndex - 2).coerceAtLeast(0)) }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        HeaderRow("Chapters", onBack) {
            OutlinedButton(
                onClick = if (hasReview) onReview else onAnalyze, shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (hasReview) C.Warn else C.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = if (hasReview) C.Warn else C.Text),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Icon(Ic.Wave, null, Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
                Text(if (hasReview) "Review results" else "Analyze audio", fontFamily = Sans)
            }
        }
        UnderlineTabs(listOf("Your chapters", "Original file", "Bookmarks"), tab) { tab = it }

        when (tab) {
            0 -> {
                LazyColumn(Modifier.weight(1f), listState) {
                    items(current, key = { it.id }) { c ->
                        val active = current.indexOf(c) == currentIndex
                        Row(
                            Modifier.fillMaxWidth().background(if (active) C.Surface else androidx.compose.ui.graphics.Color.Transparent),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                Modifier.weight(1f).heightIn(min = 54.dp).clickable { onPlayAt(c.startMs) }.padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(c.title, style = Body.copy(color = if (active) C.Accent else C.Text), modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(10.dp))
                                Text(hms(c.startMs), style = Tiny)
                            }
                            IconBtn(Ic.Edit, "Edit ${c.title}", C.Muted) { onEdit(c) }
                        }
                        HorizontalDivider(color = C.Line)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Add chapter here", Modifier.weight(1f), 48.dp, onClick = onAddHere)
                    SecondaryButton("Reset to original", Modifier.weight(1f), 48.dp) { confirmReset = true }
                }
            }
            1 -> LazyColumn(Modifier.fillMaxSize()) {
                items(original, key = { it.id }) { c ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onPlayAt(c.startMs) }.padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(c.title, style = Body, modifier = Modifier.weight(1f))
                        Text(hms(c.startMs), style = Tiny)
                    }
                    HorizontalDivider(color = C.Line)
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (bookmarks.isEmpty()) item { Text("Tap Bookmark in the player to save a moment.", style = Small, modifier = Modifier.padding(vertical = 20.dp)) }
                items(bookmarks, key = { it.id }) { b ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(hms(b.positionMs), style = Body, modifier = Modifier.weight(1f).clickable { onPlayAt(b.positionMs) }.padding(vertical = 16.dp, horizontal = 4.dp))
                        IconBtn(Ic.Close, "Delete bookmark", C.Muted) { onDeleteBookmark(b.id) }
                    }
                    HorizontalDivider(color = C.Line)
                }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset chapters?") },
            text = { Text("Your edits for this book will be replaced by the file's original chapters.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; onReset() }) { Text("Reset", color = C.Warn) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AnalyzerContent(
    books: List<Book>,
    runs: List<AnalysisRun>,
    reviews: List<Long>,
    onlyCharging: Boolean,
    onCharging: (Boolean) -> Unit,
    onAnalyze: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onReview: (Long) -> Unit,
    onClearFailed: () -> Unit,
    fullScan: Boolean = false,
    onFullScan: (Boolean) -> Unit = {},
    onSetAnalyzed: (Long, Boolean) -> Unit = { _, _ -> },
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(8.dp))
        HeaderRow("Analyzer", null)
        Text("Finds chapters and titles by listening after longer pauses. It runs on this phone, and nothing is uploaded.", style = Small)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().clickable { onCharging(!onlyCharging) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Run only while charging", style = Body)
                Text("Applies the next time you start an analysis.", style = Tiny)
            }
            Switch(onlyCharging, onCharging, colors = SwitchDefaults.colors(checkedThumbColor = C.Ground, checkedTrackColor = C.Text))
        }
        Row(Modifier.fillMaxWidth().clickable { onFullScan(!fullScan) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Scan the whole book", style = Body)
                Text("Slower. Off uses the file's chapter markers when it has them.", style = Tiny)
            }
            Spacer(Modifier.width(12.dp))
            Switch(fullScan, onFullScan, colors = SwitchDefaults.colors(checkedThumbColor = C.Ground, checkedTrackColor = C.Text))
        }
        HorizontalDivider(color = C.Line)
        val busy: (Book) -> Boolean = { b -> runs.any { it.bookId == b.id && it.state != WorkInfo.State.FAILED } || b.id in reviews }
        val toDo = books.filter { busy(it) || it.analyzedAt == 0L }
        val analyzed = books.filter { !busy(it) && it.analyzedAt > 0L }
        var section by rememberSaveable { mutableIntStateOf(0) }
        val shown = if (section == 0) toDo else analyzed
        UnderlineTabs(listOf("To analyze (${toDo.size})", "Analyzed (${analyzed.size})"), section) { section = it }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.id }) { book ->
                val run = runs.firstOrNull { it.bookId == book.id }
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { menu = true }, onLongClickLabel = "Book options").padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    DropdownMenu(menu, { menu = false }) {
                        if (book.analyzedAt > 0L) DropdownMenuItem(text = { Text("Move to To analyze") }, onClick = { menu = false; onSetAnalyzed(book.id, false) })
                        else DropdownMenuItem(text = { Text("Mark as analyzed") }, onClick = { menu = false; onSetAnalyzed(book.id, true) })
                    }
                    Cover(book, 52.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(book.title, style = Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when {
                            run?.state == WorkInfo.State.RUNNING -> {
                                ThinProgress(run.percent / 100f)
                                Text("${run.percent}% · ${run.detail.ifBlank { "Starting" }}", style = Tiny)
                            }
                            run?.state == WorkInfo.State.ENQUEUED -> Text(if (onlyCharging) "Waiting for the charger" else "Waiting to start", style = Tiny)
                            run?.state == WorkInfo.State.FAILED -> Text(run.detail.ifBlank { "Analysis failed" }, style = Tiny.copy(color = C.Warn))
                            book.id in reviews -> Text("Results ready to review", style = Tiny.copy(color = C.Warn))
                        }
                    }
                    when {
                        run?.state == WorkInfo.State.RUNNING || run?.state == WorkInfo.State.ENQUEUED ->
                            SecondaryButton("Cancel", Modifier.width(92.dp), 44.dp) { onCancel(book.id) }
                        book.id in reviews -> SecondaryButton("Review", Modifier.width(92.dp), 44.dp, C.Warn) { onReview(book.id) }
                        else -> SecondaryButton(if (book.analyzedAt > 0L) "Run again" else "Analyze", Modifier.width(92.dp), 44.dp) {
                            if (run?.state == WorkInfo.State.FAILED) onClearFailed()
                            onAnalyze(book.id)
                        }
                    }
                }
                HorizontalDivider(color = C.Line)
            }
            if (books.isNotEmpty() && shown.isEmpty()) item {
                Text(if (section == 0) "Nothing left to analyze." else "Books appear here after you save their analyzed chapters.", style = Small, modifier = Modifier.padding(vertical = 20.dp))
            }
            if (books.isEmpty()) item { Text("Add a book to your library first.", style = Small, modifier = Modifier.padding(vertical = 20.dp)) }
        }
    }
}

@Composable
fun ReviewContent(
    chapters: List<Chapter>,
    onBack: () -> Unit,
    onPreview: (Long) -> Unit,
    onEdit: (Chapter) -> Unit,
    onAccept: (Chapter) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    previewing: Boolean = false,
    onStopPreview: () -> Unit = {},
) {
    val flagged = chapters.filter { it.reason != null }
    var showAll by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        HeaderRow(if (flagged.isEmpty()) "All checked" else "${flagged.size} to check", onBack)
        Text("${chapters.size} sections found. Playback order always follows the audio timeline.", style = Small)
        Spacer(Modifier.height(4.dp))
        UnderlineTabs(listOf("To check", "All sections"), if (showAll) 1 else 0) { showAll = it == 1 }
        LazyColumn(Modifier.weight(1f)) {
            items(if (showAll) chapters else flagged, key = { it.id }) { c ->
                Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.offset(x = (-10).dp)) { IconBtn(Ic.Play, "Preview ${c.title}") { onPreview(c.startMs) } }
                        Column(Modifier.weight(1f)) {
                            Text(c.title, style = Body)
                            c.reason?.let { Text(it, style = Small.copy(color = C.Warn)) }
                        }
                        Text(hms(c.startMs), style = Tiny)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Edit", Modifier.weight(1f), 44.dp) { onEdit(c) }
                        if (c.reason != null) SecondaryButton("Accept", Modifier.weight(1f), 44.dp) { onAccept(c) }
                    }
                }
                HorizontalDivider(color = C.Line)
            }
        }
        if (previewing) PrimaryButton("Stop preview", Modifier.fillMaxWidth().padding(top = 10.dp), onClick = onStopPreview)
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Discard", Modifier.weight(1f), onClick = onDiscard)
            PrimaryButton("Save chapters", Modifier.weight(1f), onClick = onSave)
        }
    }
}

@Composable
fun EditorContent(
    chapter: Chapter,
    durationMs: Long,
    onBack: () -> Unit,
    onPreview: (Long) -> Unit,
    onSave: (Chapter) -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
    onMergePrevious: () -> Unit = {},
    hasPrevious: Boolean = false,
    hasNext: Boolean = true,
    previewing: Boolean = false,
    onStopPreview: () -> Unit = {},
) {
    var title by rememberSaveable { mutableStateOf(chapter.title) }
    var kind by rememberSaveable { mutableStateOf(chapter.kind) }
    var start by rememberSaveable { mutableLongStateOf(chapter.startMs) }
    var confirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HeaderRow("Edit chapter", onBack) {
            IconBtn(Ic.Undo, "Undo changes") { title = chapter.title; kind = chapter.kind; start = chapter.startMs }
        }
        Field("Title", title) { title = it }
        Text("Type", style = Small)
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(SectionKind.CHAPTER, SectionKind.PROLOGUE, SectionKind.EPILOGUE, SectionKind.INTRO, SectionKind.OTHER).forEach { k ->
                ChoiceChip(k.label, kind == k.name) { kind = k.name }
            }
        }
        HorizontalDivider(color = C.Line)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Starts at", style = Small)
            Text(hms(start), style = Body.copy(fontSize = 20.sp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-5_000L, -1_000L, 1_000L, 5_000L).forEach { d ->
                SecondaryButton((if (d > 0) "+" else "") + "${d / 1000}s", Modifier.weight(1f), 44.dp) {
                    start = (start + d).coerceIn(0, durationMs)
                }
            }
        }
        if (previewing) PrimaryButton("Stop preview", Modifier.fillMaxWidth().height(44.dp), onClick = onStopPreview)
        else SecondaryButton("Preview from here", Modifier.fillMaxWidth(), 44.dp) { onPreview(start) }
        HorizontalDivider(color = C.Line)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasPrevious) SecondaryButton("Merge with previous", Modifier.weight(1f), 44.dp, onClick = onMergePrevious)
            if (hasNext) SecondaryButton("Merge with next", Modifier.weight(1f), 44.dp, onClick = onMerge)
        }
        SecondaryButton("Delete", Modifier.fillMaxWidth(), 44.dp, C.Warn) { confirm = true }
        Spacer(Modifier.weight(1f))
        Text("Saved separately. Your audio file isn't changed.", style = Small, modifier = Modifier.align(Alignment.CenterHorizontally))
        PrimaryButton("Save", Modifier.fillMaxWidth().padding(bottom = 12.dp).navigationBarsPadding()) {
            onSave(chapter.copy(title = title.trim().ifEmpty { chapter.title }, kind = kind, startMs = start))
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete this chapter marker?") },
            text = { Text("The audio stays in the book and becomes part of the previous chapter.") },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete", color = C.Warn) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep") } },
        )
    }
}
