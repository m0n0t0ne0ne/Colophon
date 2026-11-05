package io.github.m0n0t0ne0ne.colophon

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import io.github.m0n0t0ne0ne.colophon.ui.AppViewModel
import io.github.m0n0t0ne0ne.colophon.ui.ColophonApp
import io.github.m0n0t0ne0ne.colophon.ui.ColophonTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { ColophonTheme { ColophonApp(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_PLAYER) vm.openPlayerRequested.value = true
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "io.github.m0n0t0ne0ne.colophon.OPEN_PLAYER"
    }
}
