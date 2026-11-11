package com.couchmpv.android

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val BG = Color(0xFF17101E)
val ACCENT = Color(0xFF8E6BFF)
val TEXT_DIM = Color(0xFFB9B0C8)

// ───────────────────────────── Estado ─────────────────────────────

class BrowserState(private val ctx: Context, private val scope: CoroutineScope) {
    val roots: SnapshotStateList<Entry> = mutableStateListOf()
    var rootSel by mutableIntStateOf(0)
    val levels: SnapshotStateList<Level> = mutableStateListOf()
    var loading by mutableStateOf(false)
    var focusMeta by mutableStateOf(Meta())
    var playRequest by mutableStateOf<PlayRequest?>(null)

    private val metaCache = HashMap<String, Meta>()

    fun reloadRoots() {
        roots.clear()
        roots.addAll(Dirs.list(ctx))
        rootSel = rootSel.coerceIn(0, roots.size + 1)
    }

    fun addRoot(uri: Uri) {
        Dirs.add(ctx, uri)
        reloadRoots()
        rootSel = (roots.size - 1).coerceAtLeast(0)
    }

    fun removeRoot(e: Entry) {
        Dirs.remove(ctx, e.tree)
        reloadRoots()
    }

    fun enter(e: Entry) {
        if (loading) return
        loading = true
        scope.launch {
            val parentMeta = levels.lastOrNull()?.meta
            val level = withContext(Dispatchers.IO) {
                val raw = Library.children(ctx, e.tree, e.docId)
                Level(e, raw, Library.browse(raw), Library.dirMeta(ctx, e, raw, parentMeta))
            }
            levels.add(level)
            loading = false
        }
    }

    /** true se havia um nível para voltar. A seleção do nível anterior foi preservada. */
    fun back(): Boolean {
        if (levels.isEmpty()) return false
        levels.removeAt(levels.lastIndex)
        return true
    }

    fun setSel(i: Int) {
        val l = levels.lastOrNull() ?: return
        levels[levels.lastIndex] = l.copy(selected = i.coerceIn(0, (l.items.size - 1).coerceAtLeast(0)))
    }

    fun activate() {
        val l = levels.lastOrNull() ?: return
        val e = l.items.getOrNull(l.selected) ?: return
        if (e.isDir) enter(e) else play(e, l.raw)
    }

    fun play(e: Entry, raw: List<Entry>) {
        playRequest = PlayRequest(e, Library.sidecarSubs(e, raw))
    }

    /** Carrega pôster/banner/sinopse do item selecionado (com cache). */
    suspend fun refreshFocus() {
        val l = levels.lastOrNull()
        if (l == null) {
            focusMeta = Meta()
            return
        }
        val e = l.items.getOrNull(l.selected)
        if (e == null) {
            focusMeta = l.meta
            return
        }
        metaCache[e.docId]?.let {
            focusMeta = it
            return
        }
        val m = withContext(Dispatchers.IO) {
            if (e.isDir) Library.dirMeta(ctx, e, Library.children(ctx, e.tree, e.docId), l.meta)
            else Library.fileMeta(ctx, e, l.raw, l.meta)
        }
        metaCache[e.docId] = m
        focusMeta = m
    }
}

// ───────────────────────────── Tela ─────────────────────────────

@Composable
fun BrowserScreen(state: BrowserState, onAddDir: () -> Unit, onExit: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }

    val level = state.levels.lastOrNull()
    LaunchedEffect(state.levels.size, level?.selected) { state.refreshFocus() }

    var confirmRemove by remember { mutableStateOf<Entry?>(null) }

    BackHandler(enabled = state.levels.isNotEmpty()) { state.back() }

    fun activateRoot(i: Int) {
        when {
            i < state.roots.size -> state.enter(state.roots[i])
            i == state.roots.size -> onAddDir()
            else -> onExit()
        }
    }

    fun handleKey(key: Key): Boolean {
        val lvl = state.levels.lastOrNull()
        val isOk = key == Key.Enter || key == Key.NumPadEnter || key == Key.DirectionCenter || key == Key.Spacebar
        if (lvl == null) {
            val last = state.roots.size + 1
            return when {
                key == Key.DirectionLeft -> { state.rootSel = (state.rootSel - 1).coerceAtLeast(0); true }
                key == Key.DirectionRight -> { state.rootSel = (state.rootSel + 1).coerceAtMost(last); true }
                isOk -> { activateRoot(state.rootSel); true }
                key == Key.Menu || key == Key.Delete -> {
                    state.roots.getOrNull(state.rootSel)?.let { confirmRemove = it }
                    true
                }
                else -> false
            }
        }
        return when {
            key == Key.DirectionUp -> { state.setSel(lvl.selected - 1); true }
            key == Key.DirectionDown -> { state.setSel(lvl.selected + 1); true }
            key == Key.PageUp -> { state.setSel(lvl.selected - 8); true }
            key == Key.PageDown -> { state.setSel(lvl.selected + 8); true }
            isOk -> { state.activate(); true }
            key == Key.Escape || key == Key.Backspace -> { state.back(); true }
            else -> false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BG)
            .focusRequester(fr)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) false else handleKey(ev.key)
            }
    ) {
        if (level == null) {
            RootView(state, onAddDir, onExit, onLongPress = { confirmRemove = it })
        } else {
            FolderView(state, level)
        }

        if (state.loading) {
            Text(
                "Carregando…",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }

    confirmRemove?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remover pasta?") },
            text = { Text("“${e.name}” sai da lista do CouchMPV. Seus arquivos não são apagados.") },
            confirmButton = {
                TextButton(onClick = { state.removeRoot(e); confirmRemove = null }) { Text("Remover") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancelar") } },
        )
    }
}

// ── Tela raiz: cartões horizontais (esq/dir) ──

@Composable
private fun RootView(
    state: BrowserState,
    onAddDir: () -> Unit,
    onExit: () -> Unit,
    onLongPress: (Entry) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.rootSel) { listState.animateScrollToItem((state.rootSel - 1).coerceAtLeast(0)) }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Diretórios", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            itemsIndexed(state.roots) { i, e ->
                DirCard(
                    title = e.name, subtitle = "Pasta", icon = "📁",
                    selected = state.rootSel == i,
                    onClick = { state.rootSel = i; state.enter(e) },
                    onLongClick = { onLongPress(e) },
                )
            }
            item {
                DirCard(
                    title = "Adicionar pasta", subtitle = "Escolher no armazenamento", icon = "➕",
                    selected = state.rootSel == state.roots.size,
                    onClick = { state.rootSel = state.roots.size; onAddDir() },
                )
            }
            item {
                DirCard(
                    title = "Sair", subtitle = "Fechar o player", icon = "⏻",
                    selected = state.rootSel == state.roots.size + 1,
                    onClick = onExit,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            if (state.roots.isEmpty()) "Nenhuma pasta ainda — escolha “Adicionar pasta” para começar."
            else "◀ ▶ navegar   ·   OK abrir   ·   Menu / segurar: remover pasta",
            color = TEXT_DIM, fontSize = 14.sp,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DirCard(
    title: String,
    subtitle: String,
    icon: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .width(230.dp)
            .height(140.dp)
            .clip(shape)
            .background(if (selected) Color(0xFF2B2040) else Color(0xFF211930))
            .border(if (selected) 3.dp else 1.dp, if (selected) ACCENT else Color(0x33FFFFFF), shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(icon, fontSize = 30.sp)
        Column {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = TEXT_DIM, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ── Dentro de uma pasta: lista vertical (cima/baixo) + pôster, banner e sinopse ──

@Composable
private fun Backdrop(uri: Uri?) {
    Box(Modifier.fillMaxSize()) {
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(28.dp) else Modifier),
            )
        }
        Box(Modifier.fillMaxSize().background(BG.copy(alpha = if (uri != null) 0.80f else 1f)))
    }
}

@Composable
private fun FolderView(state: BrowserState, level: Level) {
    val ctx = LocalContext.current
    val meta = state.focusMeta
    val selected = level.items.getOrNull(level.selected)
    val listState = rememberLazyListState()
    LaunchedEffect(level.selected) { listState.animateScrollToItem((level.selected - 4).coerceAtLeast(0)) }

    Box(Modifier.fillMaxSize()) {
        Backdrop(meta.poster ?: meta.banner)

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 640.dp // em telas estreitas (celular) esconde a coluna do pôster

            Row(
                Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                if (wide) {
                    Column(Modifier.width(250.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x22FFFFFF))
                        ) {
                            if (meta.poster != null) {
                                AsyncImage(
                                    model = meta.poster,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (meta.plot.isNotBlank()) {
                            Text(
                                meta.plot, color = Color(0xFFE6E0F0), fontSize = 13.sp,
                                maxLines = 9, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Column(Modifier.weight(1f)) {
                    if (meta.banner != null) {
                        AsyncImage(
                            model = meta.banner,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(379f / 70f)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    val heading = (meta.title.ifBlank { Library.prettify(selected?.stem ?: level.entry.name) })
                    Text(
                        heading + if (meta.year.isNotBlank()) "  (${meta.year})" else "",
                        color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(level.entry.name, color = TEXT_DIM, fontSize = 12.sp, maxLines = 1)
                    Spacer(Modifier.height(10.dp))

                    if (level.items.isEmpty()) {
                        Text("Nenhum vídeo ou subpasta aqui.", color = TEXT_DIM)
                    }
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(level.items) { i, e ->
                            ItemRow(
                                e = e,
                                selected = i == level.selected,
                                badge = if (e.isDir) "" else History.badge(ctx, e.uri.toString()),
                                onClick = { state.setSel(i); state.activate() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemRow(e: Entry, selected: Boolean, badge: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Color(0xCC3A2B5C) else Color(0x55000000))
            .border(if (selected) 2.dp else 0.dp, if (selected) ACCENT else Color.Transparent, shape)
            .combinedClickableCompat(onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (e.isDir) "📁" else "🎬", fontSize = 18.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            if (e.isDir) e.name else Library.prettify(e.stem).ifBlank { e.name },
            color = Color.White, fontSize = 16.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (badge.isNotEmpty()) {
            Spacer(Modifier.width(12.dp))
            Text(
                badge,
                color = if (badge == "✓") Color(0xFF7BE0A0) else ACCENT,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick)
