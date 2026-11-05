package io.github.m0n0t0ne0ne.colophon

import android.app.Application
import io.github.m0n0t0ne0ne.colophon.playback.PlayerHub

class ColophonApplication : Application() {
    val hub by lazy { PlayerHub(this) }
}
