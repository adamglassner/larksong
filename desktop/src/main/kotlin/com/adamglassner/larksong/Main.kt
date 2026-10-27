package com.adamglassner.larksong

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.adamglassner.larksong.alarm.AlarmService
import com.adamglassner.larksong.alarm.Scheduler
import com.adamglassner.larksong.data.Store
import com.adamglassner.larksong.radio.Radio
import com.adamglassner.larksong.radio.RadioBrowser
import com.adamglassner.larksong.radio.StreamRadioEngine
import com.adamglassner.larksong.ui.AlarmEdit
import com.adamglassner.larksong.ui.App
import com.adamglassner.larksong.ui.AppUi
import com.adamglassner.larksong.ui.LarksongTheme
import com.adamglassner.larksong.ui.DesktopApp
import com.adamglassner.larksong.ui.Page
import com.adamglassner.larksong.ui.RingToast
import com.adamglassner.larksong.ui.checkForUpdate
import com.adamglassner.larksong.update.Updater
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.prefs.Preferences
import kotlin.concurrent.thread

private const val INSTANCE_PORT = 47833

/** Remembers where the window was and how big, like other Windows apps. */
private object WindowMemory {
    private val prefs = Preferences.userRoot().node("com/adamglassner/larksong")

    data class Saved(val size: DpSize, val position: WindowPosition, val maximized: Boolean)

    fun load(): Saved {
        val w = prefs.getFloat("w", 1100f)
        val h = prefs.getFloat("h", 740f)
        val x = prefs.getFloat("x", Float.NaN)
        val y = prefs.getFloat("y", Float.NaN)
        val pos = if (x.isNaN() || y.isNaN()) WindowPosition(Alignment.Center) else WindowPosition(x.dp, y.dp)
        return Saved(DpSize(w.dp, h.dp), pos, prefs.getBoolean("max", false))
    }

    fun save(size: DpSize, pos: WindowPosition, max: Boolean) {
        runCatching {
            prefs.putBoolean("max", max)
            if (max) return
            if (size.width.value > 0) { prefs.putFloat("w", size.width.value); prefs.putFloat("h", size.height.value) }
            if (pos is WindowPosition.Absolute) { prefs.putFloat("x", pos.x.value); prefs.putFloat("y", pos.y.value) }
        }
    }
}

@OptIn(FlowPreview::class)
fun main(args: Array<String>) {
    // Only one Larksong at a time (two would ring every alarm twice).
    // A second launch asks the running one to show its window, then exits.
    val server = try {
        ServerSocket(INSTANCE_PORT, 4, InetAddress.getLoopbackAddress())
    } catch (e: Exception) {
        runCatching { Socket(InetAddress.getLoopbackAddress(), INSTANCE_PORT).use { it.getOutputStream().write(1) } }
        return
    }
    val showRequests = MutableStateFlow(0)
    thread(isDaemon = true, name = "larksong-instance") {
        while (true) runCatching { server.accept().use { showRequests.value += 1 } }
    }

    Store.init(Platform.dataDir())
    RadioBrowser.codecFilter = "MP3" // the Windows player handles MP3 streams
    Radio.engine = StreamRadioEngine()
    com.adamglassner.larksong.sleep.SleepSounds.output = com.adamglassner.larksong.sleep.DesktopSleepOutput()
    com.adamglassner.larksong.sleep.SleepSounds.restore()
    Radio.restoreLastStation()
    if (Platform.isMac) {
        // Clicking Larksong in the Dock brings the window back after it was closed to the menu bar.
        runCatching {
            java.awt.Desktop.getDesktop().addAppEventListener(object : java.awt.desktop.AppReopenedListener {
                override fun appReopened(e: java.awt.desktop.AppReopenedEvent?) { showRequests.value += 1 }
            })
        }
    }
    val startInTray = "--tray" in args
    val ui = AppUi()

    application {
        DesktopApp.exit = {
            AlarmService.stop()
            Radio.stop()
            exitApplication()
        }
        var visible by remember { mutableStateOf(!startInTray) }
        val trayState = rememberTrayState()
        val settings by Store.settings.collectAsState()
        val ringing by AlarmService.ringing.collectAsState()
        val requests by showRequests.collectAsState()
        val radio by Radio.state.collectAsState()
        val icon = painterResource("icon.png")

        LaunchedEffect(requests) { if (requests > 0) visible = true }
        val notify: (String, String) -> Unit = { title, text ->
            if (Platform.prefersSystemNotifications || !Platform.hasTray) Platform.notifyDesktop(title, text)
            else trayState.sendNotification(Notification(title, text, Notification.Type.Info))
        }
        LaunchedEffect(Unit) {
            Scheduler.loop(notify) { a, at ->
                val use24 = Store.settings.value.use24h
                val what = com.adamglassner.larksong.ui.ringText(at, use24) + if (a.label.isNotBlank()) " · ${a.label}" else ""
                notify("Upcoming alarm: $what", "Up already? Right-click the Larksong tray icon and choose Dismiss, or use the Alarms page.")
            }
        }
        val alarmsNow by Store.alarms.collectAsState()
        val sleepNow by com.adamglassner.larksong.sleep.SleepSounds.state.collectAsState()
        val trayTick by com.adamglassner.larksong.ui.rememberNow(30_000L)
        LaunchedEffect(Unit) {
            val st = checkForUpdate()
            if (st is Updater.Status.Available) {
                notify("Larksong ${st.release.version} is available", "Open Settings in Larksong to download and install it.")
            }
        }

        if (Platform.hasTray) Tray(
            icon = icon,
            state = trayState,
            tooltip = "Larksong",
            onAction = { visible = true },
            menu = {
                Item("Open Larksong", onClick = { visible = true })
                Item("New alarm", onClick = { visible = true; ui.page = Page.ALARMS; ui.alarmDialog = AlarmEdit(null) })
                // Reading alarmsNow and trayTick keeps this item up to date.
                Scheduler.soonest()?.let { (a, at) ->
                    if (alarmsNow.isNotEmpty() && at - trayTick < 24 * 60 * 60 * 1000L) {
                        Item("Dismiss next alarm (${com.adamglassner.larksong.ui.ringText(at, settings.use24h)})", onClick = {
                            com.adamglassner.larksong.ui.dismissNextAndTell(a, settings.use24h)
                            notify("Alarm dismissed", "Larksong won't ring at ${com.adamglassner.larksong.ui.ringText(at, settings.use24h)}.")
                        })
                    }
                }
                if (sleepNow.playing) {
                    Item("Stop sleep sounds (${sleepNow.sound.label})", onClick = { com.adamglassner.larksong.sleep.SleepSounds.stop() })
                }
                if (radio.playing || radio.buffering) {
                    Item("Stop radio (${radio.station?.name ?: ""})", onClick = { Radio.stop() })
                }
                Separator()
                Item("Quit Larksong", onClick = { DesktopApp.exit() })
            },
        )

        val saved = remember { WindowMemory.load() }
        val mainState = rememberWindowState(
            size = saved.size,
            position = saved.position,
            placement = if (saved.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
        )
        LaunchedEffect(mainState) {
            snapshotFlow { Triple(mainState.size, mainState.position, mainState.placement) }
                .debounce(500)
                .collect { (s, pos, pl) -> WindowMemory.save(s, pos, pl == WindowPlacement.Maximized) }
        }

        if (visible) {
            Window(
                // With a tray icon, closing hides to the tray; without one (some Linux desktops), it minimizes.
                onCloseRequest = { if (Platform.hasTray) visible = false else mainState.isMinimized = true },
                state = mainState,
                title = "Larksong",
                icon = icon,
                onPreviewKeyEvent = { ui.onKey(it) },
            ) {
                LaunchedEffect(Unit) { window.minimumSize = Dimension(720, 540) }
                LaunchedEffect(requests) { mainState.isMinimized = false; window.toFront() }
                LarksongTheme(settings.style, settings.mode) { App(ui) }
            }
        }

        val r = ringing
        if (r != null) {
            // A toast-like window in the bottom-right corner, above the taskbar.
            val bounds = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
            val w = 380
            val h = if (r.gradual) 250 else 226
            val ringState = rememberWindowState(
                size = DpSize(w.dp, h.dp),
                position = WindowPosition((bounds.x + bounds.width - w - 16).dp, (bounds.y + bounds.height - h - 16).dp),
            )
            Window(
                onCloseRequest = { AlarmService.stop() },
                state = ringState,
                title = r.title,
                icon = icon,
                undecorated = true,
                resizable = false,
                alwaysOnTop = true,
            ) {
                LaunchedEffect(r.startedAt) {
                    window.toFront()
                    window.requestFocus()
                }
                LarksongTheme(settings.style, settings.mode) { RingToast(r, settings.use24h) }
            }
        }
    }
}
