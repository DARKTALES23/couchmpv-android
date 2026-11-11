package com.couchmpv.android

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// ───────────────────────────── Modelos ─────────────────────────────

/** Um arquivo ou pasta dentro de uma árvore liberada via Storage Access Framework. */
data class Entry(
    val tree: Uri,
    val docId: String,
    val name: String,
    val isDir: Boolean,
) {
    val uri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
    val stem: String get() = name.substringBeforeLast('.', name)
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
}

data class Meta(
    val title: String = "",
    val plot: String = "",
    val year: String = "",
    val poster: Uri? = null,
    val banner: Uri? = null,
)

data class Level(
    val entry: Entry,
    val raw: List<Entry>,     // tudo que existe na pasta (inclui nfo, imagens, legendas)
    val items: List<Entry>,   // só pastas + vídeos, já ordenados
    val meta: Meta,
    val selected: Int = 0,
)

data class PlayRequest(val video: Entry, val subs: List<Entry>)

// ───────────────────────────── Biblioteca ─────────────────────────────

object Library {
    val VIDEO_EXT = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts",
        "wmv", "flv", "mpg", "mpeg", "3gp",
    )
    val IMAGE_EXT = linkedSetOf("png", "jpg", "jpeg", "webp", "gif")
    val SUB_EXT = setOf("srt", "vtt", "ass", "ssa")

    const val SKIP_SECONDS = 20
    const val WATCHED_THRESHOLD = 0.90
    const val RESUME_MIN_SECONDS = 5

    /** Lista bruta dos filhos de uma pasta (consulta direta ao provider = bem mais rápido que DocumentFile). */
    fun children(ctx: Context, tree: Uri, docId: String): List<Entry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val proj = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
        )
        val out = ArrayList<Entry>()
        try {
            ctx.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    out += Entry(tree, id, name, mime == Document.MIME_TYPE_DIR)
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Só pastas (sem as ocultas) e vídeos; pastas primeiro, ordem natural (Ep2 < Ep10). */
    fun browse(raw: List<Entry>): List<Entry> =
        raw.filter { if (it.isDir) !it.name.startsWith(".") else it.ext in VIDEO_EXT }
            .sortedWith(
                Comparator<Entry> { a, b ->
                    if (a.isDir != b.isDir) (if (a.isDir) -1 else 1)
                    else naturalCompare(a.name, b.name)
                }
            )

    fun sidecarSubs(video: Entry, raw: List<Entry>): List<Entry> =
        raw.filter { !it.isDir && it.ext in SUB_EXT }
            .sortedBy { if (it.stem.equals(video.stem, true)) 0 else 1 }
            .take(6)

    fun subMime(ext: String): String = when (ext) {
        "vtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        else -> "application/x-subrip"
    }

    // ── Metadados (NFO / pôster / banner) ──

    private fun img(byName: Map<String, Entry>, base: String): Entry? =
        IMAGE_EXT.firstNotNullOfOrNull { byName["$base.$it".lowercase()] }

    fun dirMeta(ctx: Context, dir: Entry, raw: List<Entry>, parent: Meta?): Meta {
        val byName = raw.filter { !it.isDir }.associateBy { it.name.lowercase() }
        val nfo = byName["${dir.name}.nfo".lowercase()]
            ?: listOf("tvshow.nfo", "movie.nfo", "season.nfo").firstNotNullOfOrNull { byName[it] }
        val parsed = parseNfo(ctx, nfo)

        val poster = img(byName, "${dir.name}_Poster")
            ?: img(byName, dir.name)
            ?: listOf("poster", "folder", "cover", "coverart").firstNotNullOfOrNull { img(byName, it) }
        val banner = img(byName, "${dir.name}_Banner")
            ?: img(byName, "banner")
            ?: img(byName, "fanart")

        return Meta(
            title = parsed.title.ifBlank { dir.name },
            plot = parsed.plot.ifBlank { parent?.plot ?: "" },
            year = parsed.year.ifBlank { parent?.year ?: "" },
            poster = poster?.uri ?: parent?.poster,
            // subdirectory-banner=yes: temporadas herdam o banner da série
            banner = banner?.uri ?: parent?.banner,
        )
    }

    fun fileMeta(ctx: Context, video: Entry, raw: List<Entry>, parent: Meta?): Meta {
        val byName = raw.filter { !it.isDir }.associateBy { it.name.lowercase() }
        val parsed = parseNfo(ctx, byName["${video.stem}.nfo".lowercase()])
        val poster = img(byName, "${video.stem}_Poster") ?: img(byName, video.stem)
        val banner = img(byName, "${video.stem}_Banner")
        return Meta(
            title = parsed.title,
            plot = parsed.plot,
            year = parsed.year,
            poster = poster?.uri ?: parent?.poster,
            banner = banner?.uri ?: parent?.banner,
        )
    }

    private val YEAR = Regex("(19|20)\\d{2}")

    /** NFO estilo Kodi (XML) ou texto puro: 1ª linha = título, resto = sinopse. */
    fun parseNfo(ctx: Context, nfo: Entry?): Meta {
        if (nfo == null) return Meta()
        val raw = try {
            ctx.contentResolver.openInputStream(nfo.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) {
            null
        }?.trim()?.removePrefix("\uFEFF")?.trim()
        if (raw.isNullOrEmpty()) return Meta()

        if (raw.startsWith("<")) {
            try {
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(raw.byteInputStream())
                fun first(vararg tags: String): String {
                    for (t in tags) {
                        val nodes = doc.getElementsByTagName(t)
                        for (i in 0 until nodes.length) {
                            val s = nodes.item(i).textContent?.trim().orEmpty()
                            if (s.isNotEmpty()) return s
                        }
                    }
                    return ""
                }
                return Meta(
                    title = first("title", "name", "showtitle"),
                    plot = first("plot", "description", "outline", "summary"),
                    year = YEAR.find(first("year", "premiered", "releasedate", "date"))?.value ?: "",
                )
            } catch (_: Exception) {
                // cai para texto puro
            }
        }
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            lines.size == 1 -> Meta(plot = lines[0])
            lines.size > 1 -> Meta(title = lines[0], plot = lines.drop(1).joinToString("\n"))
            else -> Meta()
        }
    }

    private val TAGS = Regex(
        "\\b(1080p|720p|480p|2160p|4k|uhd|hdr|dv|bluray|blu ray|web[- ]?dl|webrip|hdtv|" +
            "x264|x265|h264|h265|hevc|av1|aac|dts|truehd|remux|proper|repack|extended|directors cut|dc)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun prettify(stem: String): String {
        if (stem.isBlank()) return ""
        var s = stem.replace('_', ' ').replace('.', ' ')
        s = TAGS.replace(s, " ")
        return s.replace(Regex("\\s+"), " ").trim(' ', '-', '.', '_')
    }

    fun formatTime(ms: Long): String {
        if (ms < 0) return "00:00"
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var si = i
                while (si < a.length && a[si].isDigit()) si++
                var sj = j
                while (sj < b.length && b[sj].isDigit()) sj++
                val na = a.substring(i, si).trimStart('0')
                val nb = b.substring(j, sj).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = si
                j = sj
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

// ───────────────────────────── Histórico ─────────────────────────────

/** Mesmo formato do history.json do app original (posição/duração em segundos). */
object History {
    private var data: JSONObject? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "history.json")

    @Synchronized
    private fun load(ctx: Context): JSONObject {
        data?.let { return it }
        val d = try {
            JSONObject(file(ctx).readText())
        } catch (_: Exception) {
            JSONObject()
        }
        data = d
        return d
    }

    @Synchronized
    fun update(ctx: Context, key: String, posMs: Long, durMs: Long) {
        val d = load(ctx)
        var pos = (posMs / 1000.0).coerceAtLeast(0.0)
        val dur = (durMs / 1000.0).coerceAtLeast(0.0)
        val finished = dur > 0 && pos >= dur * Library.WATCHED_THRESHOLD
        if (finished) pos = 0.0 // terminou → próxima vez começa do início
        val old = d.optJSONObject(key) ?: JSONObject()
        val e = JSONObject()
            .put("position", pos)
            .put("duration", dur)
            .put("watched", finished || old.optBoolean("watched", false))
            .put("updated", System.currentTimeMillis() / 1000)
        d.put(key, e)
        try {
            file(ctx).writeText(d.toString())
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun resumeMs(ctx: Context, key: String): Long {
        val e = load(ctx).optJSONObject(key) ?: return 0
        if (e.optBoolean("watched", false)) return 0
        val pos = e.optDouble("position", 0.0)
        return if (pos < Library.RESUME_MIN_SECONDS) 0 else (pos * 1000).toLong()
    }

    @Synchronized
    fun badge(ctx: Context, key: String): String {
        val e = load(ctx).optJSONObject(key) ?: return ""
        if (e.optBoolean("watched", false)) return "✓"
        val pos = e.optDouble("position", 0.0)
        return if (pos >= Library.RESUME_MIN_SECONDS) "▶ " + Library.formatTime((pos * 1000).toLong()) else ""
    }
}

// ───────────────────────────── Pastas do usuário ─────────────────────────────

/** Substitui a seção DIRECTORIES do config original. No Android as pastas são escolhidas pelo seletor do sistema. */
object Dirs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("couchmpv", Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Entry> {
        val arr = try {
            JSONArray(prefs(ctx).getString("dirs", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val out = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val uri = Uri.parse(o.optString("uri"))
            out += Entry(uri, DocumentsContract.getTreeDocumentId(uri), o.optString("name", "Pasta"), true)
        }
        return out
    }

    fun add(ctx: Context, uri: Uri) {
        val cur = list(ctx)
        if (cur.any { it.tree == uri }) return
        val name = DocumentFile.fromTreeUri(ctx, uri)?.name ?: "Pasta"
        val arr = JSONArray()
        cur.forEach { arr.put(JSONObject().put("uri", it.tree.toString()).put("name", it.name)) }
        arr.put(JSONObject().put("uri", uri.toString()).put("name", name))
        prefs(ctx).edit().putString("dirs", arr.toString()).apply()
    }

    fun remove(ctx: Context, tree: Uri) {
        val arr = JSONArray()
        list(ctx).filter { it.tree != tree }
            .forEach { arr.put(JSONObject().put("uri", it.tree.toString()).put("name", it.name)) }
        prefs(ctx).edit().putString("dirs", arr.toString()).apply()
        try {
            ctx.contentResolver.releasePersistableUriPermission(
                tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }
}
