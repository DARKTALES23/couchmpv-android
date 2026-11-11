package com.couchmpv.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Tela cheia imersiva (como o fullscreen do mpv)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = ACCENT, background = BG, surface = BG)) {
                App(onExit = { finish() })
            }
        }
    }
}

@Composable
private fun App(onExit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { BrowserState(ctx.applicationContext, scope) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            state.addRoot(uri)
        }
    }

    LaunchedEffect(Unit) { state.reloadRoots() }

    val req = state.playRequest
    if (req != null) {
        key(req) { PlayerScreen(req) { state.playRequest = null } }
    } else {
        BrowserScreen(
            state = state,
            onAddDir = {
                try {
                    picker.launch(null)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(
                        ctx,
                        "Este aparelho não tem o seletor de pastas do sistema (DocumentsUI).",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
            onExit = onExit,
        )
    }
}
