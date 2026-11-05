package io.github.m0n0t0ne0ne.colophon

import android.Manifest
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
        setContent { ColophonTheme { ColophonApp(vm) } }
    }
}
