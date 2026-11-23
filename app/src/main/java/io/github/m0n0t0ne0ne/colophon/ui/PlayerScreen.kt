package io.github.m0n0t0ne0ne.colophon.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.roundToInt
import coil.request.ImageRequest
import io.github.m0n0t0ne0ne.colophon.core.ChapterHeading
import io.github.m0n0t0ne0ne.colophon.core.Roman
import io.github.m0n0t0ne0ne.colophon.core.Timeline
import io.github.m0n0t0ne0ne.colophon.core.formatClock
import io.github.m0n0t0ne0ne.colophon.data.Book
import io.github.m0n0t0ne0ne.colophon.data.Chapter
import io.github.m0n0t0ne0ne.colophon.playback.PlayerState
import java.io.File

private val OnArt = Color(0xFFF4F3EE)
private val OnArtSoft = OnArt.copy(alpha = 0.85f)

data class PlayerActions(
    val onBack: () -> Unit = {},
    val onSeries: () -> Unit = {},
    val onDetails: () -> Unit = {},
    val onChapters: () -> Unit = {},
    val onToggle: () -> Unit = {},
    val onSeekBy: (Long) -> Unit = {},
    val onSeekTo: (Long) -> Unit = {},
    val onSpeed: (Float) -> Unit = {},
    val onSleep: (Int?) -> Unit = {},
    val onSleepChapter: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onBookmark: () -> Unit = {},
    val onMode: (BackgroundMode) -> Unit = {},
    val onStrength: (Float) -> Unit = {},
    val onAccent: (Color?) -> Unit = {},
    val onDogear: (Color?) -> Unit = {},
    val onDogearOpacity: (Float) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerContent(
    book: Book,
    state: PlayerState,
    chapters: List<Chapter>,
    mode: BackgroundMode,
    strength: Float,
    colors: ArtColors,
    accentChoice: Color?,
    dogearChoice: Color?,
    dogearOpacity: Float,
    romanNumber: Boolean,
    jumps: List<Long>,
    actions: PlayerActions,
) {
    var sheet by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var menu by remember { mutableStateOf(false) }
    var sleepCustom by remember { mutableStateOf("") }
    var zoomed by remember { mutableStateOf(false) }
    var coverBounds by remember { mutableStateOf(Rect.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    val zoomProgress by animateFloatAsState(if (zoomed) 1f else 0f, tween(360), label = "coverZoom")

    val position = state.positionMs
    val duration = state.durationMs.coerceAtLeast(1)
    val starts = chapters.map { it.startMs }
    val index = if (chapters.isEmpty()) -1 else Timeline.chapterAt(starts, position)
    val chapter = chapters.getOrNull(index)
    val chapterStart = chapter?.startMs ?: 0L
    val chapterEnd = chapters.getOrNull(index + 1)?.startMs ?: duration
    val chapterLength = (chapterEnd - chapterStart).coerceAtLeast(1)
    val inChapter = (position - chapterStart).coerceIn(0, chapterLength)
    val autoAccent = if (mode == BackgroundMode.Black) C.Accent else colors.accent
    val accent = accentChoice ?: autoAccent

    Box(Modifier.fillMaxSize().background(C.Ground).onSizeChanged { rootSize = it }) {
        Backdrop(book, mode, strength, colors)

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.offset(x = (-12).dp)) { IconBtn(Ic.Down, "Close player", OnArt, actions.onBack) }
                val series = book.series
                Text(
                    if (series != null) listOfNotNull(book.seriesNumber?.let { "Book ${Roman.bookNumber(it, romanNumber)}" }, series).joinToString(" · ") else book.author,
                    style = Small.copy(color = OnArtSoft, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).then(if (series != null) Modifier.clickable(onClick = actions.onSeries) else Modifier).padding(vertical = 12.dp),
                )
                Box(Modifier.offset(x = 12.dp)) {
                    IconBtn(Ic.More, "More options", OnArt) { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Book details and cover") }, onClick = { menu = false; actions.onDetails() })
                        DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; actions.onSettings() })
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                ThinProgress(position.toFloat() / duration, color = accent, track = OnArt.copy(alpha = 0.22f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${formatLong(position)} of ${formatLong(duration)}", style = Tiny.copy(color = OnArtSoft))
                    Text("${(position * 100 / duration)}% · ${formatLong(duration - position)} left", style = Tiny.copy(color = OnArtSoft))
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                BoxWithConstraints {
                    // Tapping the art opens a full-screen zoom. Hide the resting cover while it's open so
                    // only the zoomed copy shows.
                    val tap = if (book.coverPath != null) {
                        Modifier
                            .onGloballyPositioned { coverBounds = it.boundsInRoot() }
                            .clickable(remember { MutableInteractionSource() }, indication = null) { zoomed = true }
                            .graphicsLayer { alpha = if (zoomProgress > 0.02f) 0f else 1f }
                    } else Modifier
                    Cover(book, minOf(maxWidth, maxHeight), modifier = tap, cueColor = dogearChoice ?: accent, cueOpacity = dogearOpacity)
                }
            }

            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val heading = chapter?.let { ChapterHeading.of(it.title) }
                if (heading?.name != null) {
                    Text(
                        heading.label.uppercase(), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        style = Tiny.copy(color = OnArtSoft, letterSpacing = 1.8.sp, fontWeight = FontWeight.Medium, lineHeight = 15.sp),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                AutoTitle(heading?.name ?: heading?.label ?: book.title)
                Spacer(Modifier.height(12.dp))
                Text(book.title, style = Small.copy(color = OnArtSoft, fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Column {
                SeekBar(
                    fraction = dragging ?: (inChapter.toFloat() / chapterLength),
                    accent = accent,
                    onScrub = { dragging = it },
                    onCommit = {
                        dragging?.let { actions.onSeekTo(chapterStart + (it * chapterLength).toLong()) }
                        dragging = null
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatClock(inChapter), style = Tiny.copy(color = OnArtSoft))
                    Text("-${formatClock(chapterLength - inChapter)}", style = Tiny.copy(color = OnArtSoft))
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.Prev, "Previous chapter", OnArt) {
                    val target = if (inChapter > 3000 || index <= 0) chapterStart else chapters[index - 1].startMs
                    actions.onSeekTo(target)
                }
                SkipButton("-30", "Back 30 seconds") { actions.onSeekBy(-30_000) }
                IconButton(
                    onClick = actions.onToggle,
                    modifier = Modifier.size(68.dp),
                    colors = IconButtonDefaults.iconButtonColors(containerColor = OnArt, contentColor = C.Ground),
                ) { Icon(if (state.playing) Ic.Pause else Ic.Play, if (state.playing) "Pause" else "Play", Modifier.size(26.dp)) }
                SkipButton("+30", "Forward 30 seconds") { actions.onSeekBy(30_000) }
                IconBtn(Ic.Next, "Next chapter", OnArt) {
                    chapters.getOrNull(index + 1)?.let { actions.onSeekTo(it.startMs) }
                }
            }

            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                val speeds = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.8f)
                Tool("Speed", Modifier.weight(1f), text = "${state.speed.toString().removeSuffix(".0")}x") {
                    actions.onSpeed(speeds[(speeds.indexOf(state.speed) + 1).mod(speeds.size)])
                }
                Tool("Chapters", Modifier.weight(1f), Ic.Chapters, onClick = actions.onChapters)
                Tool(state.sleepLeftMs?.let { formatClock(it) } ?: "Sleep", Modifier.weight(1f), Ic.Moon) { sheet = "sleep" }
                Tool("Bookmark", Modifier.weight(1f), Ic.Bookmark, onClick = actions.onBookmark)
                Tool("Backdrop", Modifier.weight(1f), Ic.Picture) { sheet = "background" }
            }
        }

        // Full-screen cover zoom: the art grows from its resting spot to fit the screen width over a
        // translucent black scrim. Tap anywhere or swipe down to close.
        if (zoomProgress > 0.001f && book.coverPath != null && rootSize.width > 0) {
            BackHandler(enabled = zoomed) { zoomed = false }
            val density = LocalDensity.current
            val w = rootSize.width.toFloat()
            val h = rootSize.height.toFloat()
            val target = Rect(0f, (h - w) / 2f, w, (h + w) / 2f)
            val r = lerp(coverBounds, target, zoomProgress)
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { zoomed = false } }
                    .pointerInput(Unit) {
                        var dy = 0f
                        detectVerticalDragGestures(
                            onDragEnd = { if (dy > 120f) zoomed = false; dy = 0f },
                            onVerticalDrag = { _, d -> dy += d },
                        )
                    },
            ) {
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.9f * zoomProgress)))
                AsyncImage(
                    model = File(book.coverPath!!), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                        .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() }),
                )
            }
        }

        when (sheet) {
            "background" -> Sheet("Background", { sheet = null }) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BackgroundMode.entries.forEach { m -> ChoiceChip(m.label, m == mode, Modifier.weight(1f)) { actions.onMode(m) } }
                }
                if (mode != BackgroundMode.Black) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Strength", style = Small)
                        Text("${(strength * 100).toInt()}%", style = Small)
                    }
                    Slider(strength, actions.onStrength, colors = SliderDefaults.colors(thumbColor = OnArt, activeTrackColor = OnArt, inactiveTrackColor = C.Border))
                }
                Text("Progress colour", style = Small, modifier = Modifier.padding(top = 4.dp))
                var trueTone by remember { mutableStateOf(true) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoiceChip("Truer", trueTone, Modifier.weight(1f)) { trueTone = true }
                    ChoiceChip("Softer", !trueTone, Modifier.weight(1f)) { trueTone = false }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ColorDot(autoAccent, selected = accentChoice == null, label = "A") { actions.onAccent(null) }
                    val set = if (trueTone) colors.swatchesVivid else colors.swatchesSoft
                    set.forEach { c ->
                        ColorDot(c, selected = accentChoice?.toArgb() == c.toArgb()) { actions.onAccent(c) }
                    }
                }
                Text("Finished mark", style = Small, modifier = Modifier.padding(top = 8.dp))
                val cream = Color(0xFFEDE8DC)
                val smoke = Color(0xFF26262A)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Auto (A) follows the progress colour; cream/smoke and the cover swatches are fixed picks.
                    ColorDot(accent, selected = dogearChoice == null, label = "A") { actions.onDogear(null) }
                    ColorDot(cream, selected = dogearChoice?.toArgb() == cream.toArgb()) { actions.onDogear(cream) }
                    ColorDot(smoke, selected = dogearChoice?.toArgb() == smoke.toArgb()) { actions.onDogear(smoke) }
                    val set = if (trueTone) colors.swatchesVivid else colors.swatchesSoft
                    set.forEach { c ->
                        ColorDot(c, selected = dogearChoice?.toArgb() == c.toArgb()) { actions.onDogear(c) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Opacity", style = Small)
                    Text("${(dogearOpacity * 100).toInt()}%", style = Small)
                }
                Slider(
                    dogearOpacity, actions.onDogearOpacity, valueRange = 0.2f..1f,
                    colors = SliderDefaults.colors(thumbColor = OnArt, activeTrackColor = OnArt, inactiveTrackColor = C.Border),
                )
            }
            "sleep" -> Sheet("Sleep timer", { sheet = null }) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 45, 60).forEach { m ->
                        ChoiceChip("$m min", state.sleepChoice == m.toString(), Modifier.weight(1f)) { actions.onSleep(m); sheet = null }
                    }
                }
                if (chapter != null && chapterEnd > position) {
                    ChoiceChip("End of chapter", state.sleepChoice == "chapter", Modifier.fillMaxWidth()) { actions.onSleepChapter(); sheet = null }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        sleepCustom, { sleepCustom = it.filter(Char::isDigit).take(3) },
                        singleLine = true, placeholder = { Text("Minutes") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton("Start", Modifier.height(56.dp)) {
                        sleepCustom.toIntOrNull()?.takeIf { it > 0 }?.let { actions.onSleep(it); sleepCustom = ""; sheet = null }
                    }
                }
                if (state.sleepLeftMs != null) SecondaryButton("Turn off", Modifier.fillMaxWidth()) { actions.onSleep(null); sheet = null }
            }
        }
    }
}

/**
 * The chapter title. Starts at 26sp and steps the size down (never below 18sp) until the whole
 * title fits within three lines, so long titles stay readable instead of truncating. Short titles
 * are untouched. The text is drawn only once a size has settled, to avoid a flash while it fits.
 */
@Composable
private fun AutoTitle(text: String) {
    val maxSize = 26f
    val minSize = 18f
    var size by remember(text) { mutableFloatStateOf(maxSize) }
    var settled by remember(text) { mutableStateOf(false) }
    Text(
        text,
        textAlign = TextAlign.Center,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        style = TitleMedium.copy(color = OnArt, fontSize = size.sp, lineHeight = (size * 1.15f).sp),
        onTextLayout = { result ->
            if (result.hasVisualOverflow && size > minSize) size = (size - 1f).coerceAtLeast(minSize)
            else settled = true
        },
        modifier = Modifier.drawWithContent { if (settled) drawContent() },
    )
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, label: String? = null, onClick: () -> Unit) {
    val ink = if (color.luminance() > 0.5f) Color.Black.copy(alpha = 0.6f) else OnArt
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 2.5.dp else 1.dp,
                color = if (selected) OnArt else OnArt.copy(alpha = 0.28f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) Text(label, style = Tiny.copy(color = ink, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun SeekBar(fraction: Float, accent: Color, onScrub: (Float) -> Unit, onCommit: () -> Unit) {
    var width by remember { mutableIntStateOf(1) }
    val thumbPx = with(LocalDensity.current) { 12.dp.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures { offset -> onScrub((offset.x / width).coerceIn(0f, 1f)); onCommit() }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> onScrub((offset.x / width).coerceIn(0f, 1f)) },
                    onHorizontalDrag = { change, _ -> onScrub((change.position.x / width).coerceIn(0f, 1f)) },
                    onDragEnd = { onCommit() },
                    onDragCancel = { onCommit() },
                )
            },
    ) {
        // The line and the thumb are both centred vertically, so the dot always sits on the line.
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(2.dp).background(OnArt.copy(alpha = 0.22f))) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(accent))
        }
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset((fraction.coerceIn(0f, 1f) * width - thumbPx / 2f).toInt().coerceIn(0, (width - thumbPx).toInt().coerceAtLeast(0)), 0) }
                .size(12.dp).clip(CircleShape).background(OnArt),
        )
    }
}

private fun formatLong(ms: Long): String {
    val t = ms.coerceAtLeast(0) / 1000
    return "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
}

@Composable
private fun Backdrop(book: Book, mode: BackgroundMode, strength: Float, colors: ArtColors) {
    val overlay = 0.82f - 0.32f * strength
    val hasArt = book.coverPath != null && File(book.coverPath).exists()
    when {
        mode == BackgroundMode.Art && hasArt -> {
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context).data(File(book.coverPath!!)).size(if (Build.VERSION.SDK_INT >= 31) 256 else 24).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.25f; scaleY = 1.25f }
                    .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(44.dp) else Modifier),
            )
            Box(Modifier.fillMaxSize().background(Color(0xFF050608).copy(alpha = overlay)))
        }
        mode == BackgroundMode.Black -> Unit
        else -> {
            Box(Modifier.fillMaxSize().background(colors.tone))
            Box(Modifier.fillMaxSize().background(Color(0xFF050608).copy(alpha = overlay)))
        }
    }
}

@Composable
private fun SkipButton(label: String, description: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick, Modifier.size(52.dp).describe(description), shape = CircleShape, contentPadding = PaddingValues(0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, OnArt.copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = OnArt),
    ) { Text(label, fontFamily = Sans, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun Tool(label: String, modifier: Modifier, icon: ImageVector? = null, text: String? = null, onClick: () -> Unit) {
    Column(
        modifier.height(56.dp).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Icon(icon, null, tint = OnArt, modifier = Modifier.size(20.dp))
        else Text(text.orEmpty(), style = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = OnArt))
        Spacer(Modifier.height(4.dp))
        Text(label, style = TextStyle(fontFamily = Sans, fontSize = 11.sp, color = OnArt), maxLines = 1)
    }
}

@Composable
private fun BoxScope.Sheet(title: String, onDone: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = onDone))
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .background(C.Surface).navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = TitleMedium.copy(fontSize = 22.sp))
            TextButton(onClick = onDone) { Text("Done", color = C.Text, fontFamily = Sans, fontWeight = FontWeight.SemiBold) }
        }
        content()
    }
}
