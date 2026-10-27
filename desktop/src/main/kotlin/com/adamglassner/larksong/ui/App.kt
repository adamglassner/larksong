package com.adamglassner.larksong.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglassner.larksong.alarm.AlarmService
import com.adamglassner.larksong.alarm.Scheduler
import com.adamglassner.larksong.data.ClockStyle
import com.adamglassner.larksong.data.Store
import com.adamglassner.larksong.radio.Radio
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Short confirmation messages, shown as a small toast inside the window. */
object Notices {
    data class Notice(val text: String, val at: Long = System.nanoTime())
    private val _current = MutableStateFlow<Notice?>(null)
    val current = _current.asStateFlow()
    fun show(text: String) { _current.value = Notice(text) }
    fun clear(n: Notice) { if (_current.value == n) _current.value = null }
}

enum class Page(val title: String, val icon: ImageVector) {
    CLOCK("Clock", DbIcons.Clock),
    ALARMS("Alarm", DbIcons.Alarm),
    TIMER("Timer", DbIcons.Timer),
    STOPWATCH("Stopwatch", DbIcons.Stopwatch),
    RADIO("Radio", DbIcons.Radio),
    SETTINGS("Settings", DbIcons.Settings),
}

data class AlarmEdit(val id: Int?)

/** Window-level UI state (current page, open dialogs), shared with keyboard shortcuts. */
class AppUi {
    var page by mutableStateOf(Page.CLOCK)
    var navExpanded by mutableStateOf(true)
    var alarmDialog by mutableStateOf<AlarmEdit?>(null)
    var bedtimeDialog by mutableStateOf(false)

    fun mainPages(style: ClockStyle) =
        listOf(Page.CLOCK, Page.ALARMS, Page.TIMER, Page.STOPWATCH) + if (style == ClockStyle.RADIO) listOf(Page.RADIO) else emptyList()

    /** Windows-style keyboard shortcuts. Returns true when handled. */
    fun onKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        if (e.key == Key.Escape && (alarmDialog != null || bedtimeDialog)) {
            alarmDialog = null; bedtimeDialog = false; return true
        }
        if (alarmDialog != null || bedtimeDialog) return false
        if (e.isCtrlPressed || (com.adamglassner.larksong.Platform.isMac && e.isMetaPressed)) {
            val pages = mainPages(Store.settings.value.style)
            val n = when (e.key) {
                Key.One -> 0; Key.Two -> 1; Key.Three -> 2; Key.Four -> 3; Key.Five -> 4
                else -> -1
            }
            if (n in pages.indices) { page = pages[n]; return true }
            if (e.key == Key.N) { page = Page.ALARMS; alarmDialog = AlarmEdit(null); return true }
            if (e.key == Key.Comma) { page = Page.SETTINGS; return true }
            return false
        }
        if (page == Page.STOPWATCH) {
            if (e.key == Key.Spacebar) { stopwatchToggle(); return true }
            if (e.key == Key.L) { stopwatchLap(); return true }
        }
        return false
    }
}

@Composable
fun App(ui: AppUi) {
    val db = LocalDb.current
    val p = db.p
    val settings by Store.settings.collectAsState()
    val notice by Notices.current.collectAsState()

    LaunchedEffect(settings.style) {
        if (settings.style != ClockStyle.RADIO) {
            Radio.stop()
            if (ui.page == Page.RADIO) ui.page = Page.CLOCK
        }
    }

    CompositionLocalProvider(
        LocalScrollbarStyle provides defaultScrollbarStyle().copy(
            unhoverColor = p.muted.copy(alpha = 0.35f), hoverColor = p.muted.copy(alpha = 0.7f), thickness = 6.dp,
        ),
    ) {
        Box(Modifier.fillMaxSize().then(skyBackground(p))) {
            Row(Modifier.fillMaxSize()) {
                NavPane(ui, settings.style)
                // The content "layer", like Windows 11 apps: slightly raised, rounded top-left corner.
                val classic = db.style == ClockStyle.CLASSIC
                Box(
                    if (classic) Modifier.weight(1f).fillMaxHeight().background(p.bg).bevel(p.dark, sunken = true)
                    else Modifier.weight(1f).fillMaxHeight().padding(top = 0.dp)
                        .clip(RoundedCornerShape(topStart = 8.dp))
                        .background(if (p.sky != null) Color.Transparent else if (p.dark) Color.White.copy(alpha = 0.03f) else Color.White.copy(alpha = 0.5f))
                        .border(BorderStroke(1.dp, p.border), RoundedCornerShape(topStart = 8.dp)),
                ) {
                    when (ui.page) {
                        Page.CLOCK -> ClockPage(settings, ui)
                        Page.ALARMS -> AlarmsPage(settings, ui)
                        Page.TIMER -> TimerPage(settings)
                        Page.STOPWATCH -> StopwatchPage()
                        Page.RADIO -> RadioPage()
                        Page.SETTINGS -> SettingsPage(settings)
                    }
                }
            }

            if (db.style == ClockStyle.TERMINAL && p.dark) Scanlines(Modifier.fillMaxSize())
            ui.alarmDialog?.let { edit -> AlarmDialog(edit, settings) { ui.alarmDialog = null } }
            if (ui.bedtimeDialog) BedtimeDialog(settings) { ui.bedtimeDialog = false }

            val n = notice
            if (n != null) {
                LaunchedEffect(n) { delay(3000); Notices.clear(n) }
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)
                        .clip(RoundedCornerShape(if (db.style == ClockStyle.CLASSIC) 0.dp else 8.dp)).background(p.surface)
                        .then(
                            if (db.style == ClockStyle.CLASSIC) Modifier.bevel(p.dark)
                            else Modifier.border(BorderStroke(1.dp, p.border), RoundedCornerShape(8.dp)),
                        )
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    DText(n.text, 14.sp, p.text)
                }
            }
        }
    }
}

@Composable
private fun NavPane(ui: AppUi, style: ClockStyle) {
    val db = LocalDb.current
    val p = db.p
    val width by animateDpAsState(if (ui.navExpanded) 240.dp else 56.dp, label = "nav")
    Column(Modifier.width(width).fillMaxHeight().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            FIconButton(DbIcons.Menu, if (ui.navExpanded) "Collapse navigation" else "Expand navigation", size = 40.dp) {
                ui.navExpanded = !ui.navExpanded
            }
            if (ui.navExpanded) {
                Spacer(Modifier.width(8.dp))
                androidx.compose.foundation.Image(painterResource("icon.png"), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                DText("Larksong", 13.sp, p.text, maxLines = 1)
            }
        }
        ui.mainPages(style).forEach { page -> NavItem(page, ui) }
        Spacer(Modifier.weight(1f))
        NavItem(Page.SETTINGS, ui)
    }
}

@Composable
private fun NavItem(page: Page, ui: AppUi) {
    val db = LocalDb.current
    val p = db.p
    val selected = ui.page == page
    val src = androidx.compose.runtime.remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val content: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp).height(38.dp)
                .clip(RoundedCornerShape(if (db.style == ClockStyle.CLASSIC) 0.dp else 4.dp))
                .then(
                    if (db.style == ClockStyle.CLASSIC) {
                        if (selected) Modifier.background(p.surface2).bevel(p.dark, sunken = true)
                        else if (hovered) Modifier.bevel(p.dark) else Modifier
                    } else Modifier.background(if (selected || hovered) hoverTint() else Color.Transparent),
                )
                .hoverable(src)
                .clickable(interactionSource = src, indication = null, role = Role.Tab) { ui.page = page }
                .pointerHoverIcon(PointerIcon.Hand)
                .semantics { this.selected = selected; role = Role.Tab },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) p.accent else Color.Transparent))
            Spacer(Modifier.width(13.dp))
            Icon(page.icon, null, tint = if (selected && db.style != ClockStyle.STOCK) p.accent else p.text, modifier = Modifier.size(18.dp))
            if (ui.navExpanded) {
                Spacer(Modifier.width(16.dp))
                DText(page.title, 14.sp, p.text, if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
    if (ui.navExpanded) content() else Tooltip(page.title) { content() }
}

// ---------- ringing: a Windows-style toast window ----------

@Composable
fun RingToast(r: AlarmService.Ringing, use24: Boolean) {
    val db = LocalDb.current
    val p = db.p
    val now by rememberNow(1000L)
    val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
    val isTimer = r.kind == Scheduler.KIND_TIMER
    Column(
        Modifier.fillMaxSize().background(p.surface).border(BorderStroke(1.dp, p.border)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Image(painterResource("icon.png"), null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            DText("Larksong", 12.sp, p.muted, modifier = Modifier.weight(1f))
            FIconButton(DbIcons.Close, if (isTimer) "Stop" else "Dismiss", size = 28.dp) { AlarmService.stop() }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(p.accentSoft), contentAlignment = Alignment.Center) {
                Icon(if (isTimer) DbIcons.Timer else DbIcons.Alarm, null, tint = p.accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                DText(r.title, 16.sp, p.text, FontWeight.SemiBold, maxLines = 1)
                if (db.style == ClockStyle.RADIO) {
                    SevenSeg(clockDigits(t.hour, t.minute, use24), 30.dp, p.led, p.ledOff)
                } else {
                    DText(formatTime(t.hour, t.minute, use24), 26.sp, p.text, FontWeight.SemiBold, family = db.f.display)
                }
            }
        }
        if (r.gradual) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DText("Volume rising", 12.sp, p.muted, modifier = Modifier.weight(1f))
                DText("${(r.volume * 100).toInt()}%", 12.sp, p.muted)
            }
            ProgressBar(r.volume)
        } else {
            DText(r.soundName, 12.sp, p.muted, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.canSnooze) FButton("Snooze ${r.snoozeMinutes} min", Modifier.weight(1f)) { AlarmService.snooze() }
            FButton(if (isTimer) "Stop" else "Dismiss", Modifier.weight(1f), accent = true) { AlarmService.stop() }
        }
    }
}
