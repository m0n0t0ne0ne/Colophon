package io.github.m0n0t0ne0ne.colophon.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.m0n0t0ne0ne.colophon.data.Book
import io.github.m0n0t0ne0ne.colophon.data.Chapter
import io.github.m0n0t0ne0ne.colophon.data.ChapterSet
import kotlinx.coroutines.flow.flowOf

@Composable
fun ColophonApp(vm: AppViewModel) {
    val nav = rememberNavController()
    val books by vm.books.collectAsStateWithLifecycle()
    val player by vm.hub.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.message.value = null }
    }
    LaunchedEffect(player.error) {
        player.error?.let { snackbar.showSnackbar(it); vm.hub.clearError() }
    }

    val openPlayer by vm.openPlayerRequested.collectAsStateWithLifecycle()
    LaunchedEffect(openPlayer, player.bookId, books) {
        if (!openPlayer) return@LaunchedEffect
        val id = player.bookId ?: books.filter { it.lastPlayedAt > 0 }.maxByOrNull { it.lastPlayedAt }?.id
            ?: return@LaunchedEffect
        vm.openPlayerRequested.value = false
        nav.navigate("player/$id") { launchSingleTop = true }
    }

    val current = when {
        route == "library" -> Tab.Library
        route == "seriesList" || route?.startsWith("series/") == true -> Tab.Series
        route == "booksList" -> Tab.Books
        route == "analyzer" -> Tab.Analyzer
        else -> null
    }

    Scaffold(
        containerColor = C.Ground,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (current != null) BottomBar(current) { tab ->
                when (tab) {
                    Tab.Library -> nav.go("library")
                    Tab.Books -> nav.go("booksList")
                    Tab.Series -> nav.go("seriesList")
                    Tab.Analyzer -> nav.go("analyzer")
                    Tab.Playing -> {
                        val id = player.bookId ?: books.filter { it.lastPlayedAt > 0 }.maxByOrNull { it.lastPlayedAt }?.id
                        if (id != null) {
                            vm.play(id, autoplay = false)
                            nav.navigate("player/$id")
                        } else vm.message.value = "Choose a book from your library to start."
                    }
                }
            }
        },
    ) { padding ->
        // The player paints its backdrop edge to edge, including behind the gesture bar, so
        // it doesn't break into a separate strip. Its own controls keep a safe-area inset.
        val fullBleed = route?.startsWith("player/") == true
        Box(Modifier.fillMaxSize().background(C.Ground).padding(bottom = if (fullBleed) 0.dp else padding.calculateBottomPadding())) {
            NavHost(nav, "library") {
                composable("library") { LibraryRoute(vm, nav, 0) }
                composable("booksList") { LibraryRoute(vm, nav, 1) }
                composable("seriesList") { LibraryRoute(vm, nav, 2) }
                composable("series/{name}", listOf(navArgument("name") { type = NavType.StringType })) { e ->
                    val name = Uri.decode(e.arguments?.getString("name").orEmpty())
                    SeriesContent(
                        name, books.filter { it.series == name }, { nav.popBackStack() },
                        { b -> vm.play(b.id); nav.navigate("player/${b.id}") },
                        { b -> nav.navigate("details/${b.id}") },
                        { b -> vm.removeBook(b.id) },
                        { vm.removeSeries(name); nav.popBackStack() },
                    )
                }
                composable("player/{id}", listOf(idArg())) { e -> PlayerRoute(vm, nav, e.id()) }
                composable("chapters/{id}", listOf(idArg())) { e -> ChaptersRoute(vm, nav, e.id()) }
                composable("analyzer") {
                    val runs by vm.analysis.collectAsStateWithLifecycle()
                    val reviews by vm.reviews.collectAsStateWithLifecycle()
                    val charging by vm.onlyCharging.collectAsStateWithLifecycle()
                    val full by vm.fullScan.collectAsStateWithLifecycle()
                    AnalyzerContent(
                        books, runs, reviews, charging, vm::setOnlyCharging, vm::analyze, vm::cancelAnalysis,
                        { nav.navigate("review/$it") }, vm::clearFailedRuns, full, vm::setFullScan, vm::setAnalyzed,
                    )
                }
                composable("review/{id}", listOf(idArg())) { e -> ReviewRoute(vm, nav, e.id()) }
                composable("editor/{id}", listOf(idArg())) { e -> EditorRoute(vm, nav, e.id()) }
                composable("details/{id}", listOf(idArg())) { e -> DetailsRoute(vm, nav, e.id()) }
                composable("tags/{id}", listOf(idArg())) { e -> TagsRoute(vm, nav, e.id()) }
                composable("settings") { SettingsRoute(vm, nav) }
            }
        }
    }
}

private fun idArg() = navArgument("id") { type = NavType.LongType }
private fun androidx.navigation.NavBackStackEntry.id() = arguments?.getLong("id") ?: 0

private fun NavHostController.go(route: String) = navigate(route) {
    popUpTo("library") { saveState = false }
    launchSingleTop = true
}

@Composable
private fun LibraryRoute(vm: AppViewModel, nav: NavHostController, tab: Int) {
    val books by vm.books.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::importFolder) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.importFiles(uris) }
    LibraryContent(
        books, tab, importing,
        { b -> vm.play(b.id); nav.navigate("player/${b.id}") },
        { s -> nav.navigate("series/${Uri.encode(s)}") },
        { folder.launch(null) },
        { files.launch(arrayOf("audio/*", "video/mp4", "application/octet-stream")) },
        vm::refreshLibrary,
        vm::hideFromContinue,
        { b -> nav.navigate("details/${b.id}") },
        { b -> vm.removeBook(b.id) },
    )
}

@Composable
private fun PlayerRoute(vm: AppViewModel, nav: NavHostController, id: Long) {
    val book by remember(id) { vm.bookFlow(id) }.collectAsStateWithLifecycle(null)
    val chapters by remember(id) { vm.chapters(id, ChapterSet.CURRENT) }.collectAsStateWithLifecycle(emptyList())
    val history by remember(id) { vm.history(id) }.collectAsStateWithLifecycle(emptyList())
    val state by vm.hub.state.collectAsStateWithLifecycle()
    val mode by vm.backgroundMode.collectAsStateWithLifecycle()
    val strength by vm.backgroundStrength.collectAsStateWithLifecycle()
    val b = book ?: return
    var colors by remember { mutableStateOf(ArtColors(C.Raised, C.Accent)) }
    LaunchedEffect(b.coverPath) { colors = vm.artColors(b.coverPath) }
    LaunchedEffect(id) { if (vm.hub.state.value.bookId != id) vm.play(id, autoplay = false) }
    val shown = if (state.bookId == id) state else state.copy(positionMs = b.positionMs, durationMs = b.durationMs, playing = false, speed = b.speed)
    PlayerContent(
        b, shown, chapters, mode, strength, colors, history.map { it.positionMs },
        PlayerActions(
            onBack = { nav.popBackStack() },
            onSeries = { b.series?.let { nav.navigate("series/${Uri.encode(it)}") } },
            onDetails = { nav.navigate("details/$id") },
            onChapters = { nav.navigate("chapters/$id") },
            onToggle = vm.hub::togglePlay,
            onSeekBy = vm.hub::seekBy,
            onSeekTo = vm.hub::seekTo,
            onSpeed = vm.hub::setSpeed,
            onSleep = { vm.setSleep(it) },
            onSleepChapter = { vm.setSleepEndOfChapter() },
            onSettings = { nav.navigate("settings") },
            onBookmark = { vm.addBookmark(id, shown.positionMs) },
            onMode = vm::setBackground,
            onStrength = vm::setStrength,
        ),
    )
}

@Composable
private fun ChaptersRoute(vm: AppViewModel, nav: NavHostController, id: Long) {
    val current by remember(id) { vm.chapters(id, ChapterSet.CURRENT) }.collectAsStateWithLifecycle(emptyList())
    val original by remember(id) { vm.chapters(id, ChapterSet.ORIGINAL) }.collectAsStateWithLifecycle(emptyList())
    val marks by remember(id) { vm.bookmarks(id) }.collectAsStateWithLifecycle(emptyList())
    val reviews by vm.reviews.collectAsStateWithLifecycle()
    val state by vm.hub.state.collectAsStateWithLifecycle()
    ChaptersContent(
        current, original, marks, state.positionMs, id in reviews, { nav.popBackStack() },
        { at -> vm.play(id, true, at); nav.popBackStack() },
        { c -> nav.navigate("editor/${c.id}") },
        { vm.analyze(id); nav.go("analyzer") },
        { nav.navigate("review/$id") },
        { vm.addChapter(id, ChapterSet.CURRENT, state.positionMs) },
        { vm.resetChapters(id) },
        vm::deleteBookmark,
    )
}

@Composable
private fun ReviewRoute(vm: AppViewModel, nav: NavHostController, id: Long) {
    val chapters by remember(id) { vm.chapters(id, ChapterSet.SUGGESTED) }.collectAsStateWithLifecycle(emptyList())
    val previewing by vm.previewing.collectAsStateWithLifecycle()
    DisposableEffect(id) { onDispose { vm.stopPreview() } }
    ReviewContent(
        chapters, { nav.popBackStack() },
        { at -> vm.startPreview(id, at) },
        { c -> nav.navigate("editor/${c.id}") },
        { c -> vm.saveChapter(c.copy(reason = null)) },
        { vm.acceptSuggestions(id); nav.popBackStack() },
        { vm.discardSuggestions(id); nav.popBackStack() },
        previewing, vm::stopPreview,
    )
}

@Composable
private fun EditorRoute(vm: AppViewModel, nav: NavHostController, id: Long) {
    var chapter by remember { mutableStateOf<Chapter?>(null) }
    LaunchedEffect(id) { chapter = vm.chapter(id) }
    val c = chapter ?: return
    val book by remember(c.bookId) { vm.bookFlow(c.bookId) }.collectAsStateWithLifecycle(null)
    val siblings by remember(c.bookId, c.chapterSet) { vm.chapters(c.bookId, c.chapterSet) }.collectAsStateWithLifecycle(emptyList())
    val previewing by vm.previewing.collectAsStateWithLifecycle()
    val position = siblings.indexOfFirst { it.id == c.id }
    DisposableEffect(id) { onDispose { vm.stopPreview() } }
    EditorContent(
        c, book?.durationMs ?: Long.MAX_VALUE, { nav.popBackStack() },
        { at -> vm.startPreview(c.bookId, at) },
        { vm.saveChapter(it.copy(reason = null)); nav.popBackStack() },
        { vm.mergeWithNext(c); nav.popBackStack() },
        { vm.deleteChapter(c.id); nav.popBackStack() },
        { vm.mergeWithPrevious(c); nav.popBackStack() },
        position > 0,
        position in 0 until siblings.size - 1,
        previewing, vm::stopPreview,
    )
}

@Composable
private fun DetailsRoute(vm: AppViewModel, nav: NavHostController, id: Long) {
    val book by remember(id) { vm.bookFlow(id) }.collectAsStateWithLifecycle(null)
    val b: Book = book ?: return
    var pending by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val done = pending
        if (uri != null && done != null) vm.copyCover(uri, id, done)
    }
    key(b.id) {
        DetailsScreen(
            b, { nav.popBackStack() },
            { done -> pending = done; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            { updated, whole -> vm.saveBook(updated, whole); nav.popBackStack() },
            { vm.removeBook(id); nav.go("library") },
            { nav.navigate("tags/$id") },
        )
    }
}

@Composable
private fun SettingsRoute(vm: AppViewModel, nav: NavHostController) {
    val rewind by vm.resumeRewindSec.collectAsStateWithLifecycle()
    val chime by vm.sleepChime.collectAsStateWithLifecycle()
    val shake by vm.sleepShake.collectAsStateWithLifecycle()
    val shakeMin by vm.sleepShakeMin.collectAsStateWithLifecycle()
    SettingsScreen(
        rewind, vm::setResumeRewind, chime, vm::setSleepChime,
        shake, vm::setSleepShake, shakeMin, vm::setSleepShakeMin,
    ) { nav.popBackStack() }
}

@Composable
private fun TagsRoute(vm: AppViewModel, nav: NavHostController, id: Long) {
    val book by remember(id) { vm.bookFlow(id) }.collectAsStateWithLifecycle(null)
    val tags by produceState<List<FileTags>?>(null, id) { value = vm.fileTags(id) }
    TagsScreen(book?.title.orEmpty(), tags) { nav.popBackStack() }
}
