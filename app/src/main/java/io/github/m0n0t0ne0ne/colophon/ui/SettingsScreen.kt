package io.github.m0n0t0ne0ne.colophon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(
    resumeRewindSec: Int,
    onResumeRewind: (Int) -> Unit,
    sleepChime: Boolean,
    onSleepChime: (Boolean) -> Unit,
    sleepShake: Boolean,
    onSleepShake: (Boolean) -> Unit,
    sleepShakeMin: Int,
    onSleepShakeMin: (Int) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(8.dp))
        HeaderRow("Settings", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("WHEN YOU RESUME")
            Text("Skip back after a long pause", style = Body)
            Text("Rewinds this much when you come back after 5 minutes or more away.", style = Tiny)
            ChipRow(listOf(0 to "Off", 10 to "10s", 15 to "15s", 30 to "30s", 60 to "60s"), resumeRewindSec, onResumeRewind)

            HorizontalDivider(color = C.Line, modifier = Modifier.padding(vertical = 10.dp))

            SectionLabel("SLEEP TIMER")
            ToggleRow("Chime at 1 minute left", "A soft beep warns you before the timer ends.", sleepChime, onSleepChime)
            ToggleRow("Shake to extend", "Shake the phone while the timer runs to add more time.", sleepShake, onSleepShake)
            if (sleepShake) {
                Text("Each shake adds", style = Body, modifier = Modifier.padding(top = 4.dp))
                ChipRow(listOf(1 to "1 min", 5 to "5 min", 10 to "10 min", 15 to "15 min"), sleepShakeMin, onSleepShakeMin)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = Tiny.copy(color = C.Muted, letterSpacing = 1.6.sp), modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

@Composable
private fun ToggleRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Body)
            Text(subtitle, style = Tiny)
        }
        Spacer(Modifier.width(12.dp))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedThumbColor = C.Ground, checkedTrackColor = C.Text))
    }
}

@Composable
private fun ChipRow(options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            ChoiceChip(label, selected == value, Modifier.weight(1f)) { onSelect(value) }
        }
    }
}
