package io.github.m0n0t0ne0ne.colophon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m0n0t0ne0ne.colophon.core.SectionKind
import io.github.m0n0t0ne0ne.colophon.core.Timeline
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
    onBack: () -> Unit,
    onPlayAt: (Long) -> Unit,
    onEdit: (Chapter) -> Unit,
    onAddHere: () -> Unit,
    onReset: () -> Unit,
    onPadNumbers: (Int) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val currentIndex = if (current.isEmpty()) 0 else Timeline.chapterAt(current.map { it.startMs }, positionMs)
    // Scroll to the playing chapter only on first open; keep the reader's place after editing a chapter.
    var scrolledToCurrent by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(current.size) {
        if (!scrolledToCurrent && current.isNotEmpty()) {
            listState.scrollToItem((currentIndex - 2).coerceAtLeast(0))
            scrolledToCurrent = true
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        HeaderRow("Chapters", onBack) {
            Box {
                IconBtn(Ic.More, "Chapter options") { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("No number padding (1, 2…)") }, onClick = { menu = false; onPadNumbers(1) })
                    DropdownMenuItem(text = { Text("Pad numbers to 01, 02…") }, onClick = { menu = false; onPadNumbers(2) })
                    DropdownMenuItem(text = { Text("Pad numbers to 001, 002…") }, onClick = { menu = false; onPadNumbers(3) })
                }
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
