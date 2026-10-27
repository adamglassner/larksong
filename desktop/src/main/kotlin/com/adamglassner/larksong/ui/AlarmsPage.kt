package com.adamglassner.larksong.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglassner.larksong.alarm.AlarmService
import com.adamglassner.larksong.alarm.Scheduler
import com.adamglassner.larksong.data.Alarm
import com.adamglassner.larksong.data.ClockStyle
import com.adamglassner.larksong.data.Settings
import com.adamglassner.larksong.data.Store

@Composable
fun AlarmsPage(settings: Settings, ui: AppUi) {
    val p = LocalDb.current.p
    val alarms by Store.alarms.collectAsState()
    val now by rememberNow(30_000L)
    val next = remember(alarms, now) { nextAlarmMillis() }
    val b = settings.bedtime

    ScrollPage {
        PageHeader("Alarm", if (next != null) "Next alarm in ${untilText(next)}" else "No alarms are on") {
            FButton("Bedtime", icon = DbIcons.Moon) { ui.bedtimeDialog = true }
            FButton("Add an alarm", accent = true, icon = DbIcons.Plus) { ui.alarmDialog = AlarmEdit(null) }
        }

        FCard(Modifier.fillMaxWidth(), onClick = { ui.bedtimeDialog = true }, color = p.night) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(p.nightSoft), contentAlignment = Alignment.Center) {
                    Icon(DbIcons.Moon, null, tint = p.text, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    DText(if (b.enabled) "Bedtime ${formatTime(b.bedHour, b.bedMinute, settings.use24h)}" else "Bedtime reminder", 15.sp, p.text, FontWeight.SemiBold)
                    DText(
                        if (b.enabled) "A reminder ${b.remindMinutes} min before · ${sleepLength(b.bedHour * 60 + b.bedMinute, b.wakeHour * 60 + b.wakeMinute)} of sleep · ${describeDays(b.days)}"
                        else "Off. Click to set a bedtime and get a reminder to wind down.",
                        13.sp, p.muted,
                    )
                }
                Icon(DbIcons.Chevron, null, tint = p.muted, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(20.dp))

        if (alarms.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(DbIcons.Alarm, null, tint = p.muted, modifier = Modifier.size(48.dp))
                DText("No alarms yet", 18.sp, p.text, FontWeight.SemiBold)
                DText("Add one with the button above, or press ${if (com.adamglassner.larksong.Platform.isMac) "⌘N" else "Ctrl+N"}.", 14.sp, p.muted)
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gap = 16.dp
                val cols = maxOf(1, ((maxWidth + gap) / (300.dp + gap)).toInt())
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    alarms.chunked(cols).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            row.forEach { a -> AlarmTile(a, settings.use24h, Modifier.weight(1f)) { ui.alarmDialog = AlarmEdit(a.id) } }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            DText("Tip: right-click an alarm for more options.", 12.sp, p.muted, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun AlarmTile(a: Alarm, use24: Boolean, modifier: Modifier, onEdit: () -> Unit) {
    val db = LocalDb.current
    val p = db.p
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    ContextMenuArea(items = {
        listOf(
            ContextMenuItem("Edit") { onEdit() },
            ContextMenuItem(if (a.enabled) "Turn off" else "Turn on") { saveAndSchedule(a.copy(enabled = !a.enabled), !a.enabled) },
        ) + (
            if (!a.enabled) emptyList()
            else if (a.skipUntil > System.currentTimeMillis()) listOf(ContextMenuItem("Undo dismiss") { Scheduler.undoDismiss(a) })
            else listOf(ContextMenuItem("Dismiss next ring (${ringText(Scheduler.nextTrigger(a), use24)})") { dismissNextAndTell(a, use24) })
        ) + listOf(
            ContextMenuItem("Delete") {
                Scheduler.cancel(a.id)
                Store.deleteAlarm(a.id)
                Notices.show("Alarm deleted")
            },
        )
    }) {
        Box(modifier.hoverable(src)) {
            FCard(Modifier.fillMaxWidth(), onClick = onEdit, padding = 18.dp) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            DText(
                                clockDigits(a.hour, a.minute, use24), 40.sp,
                                when {
                                    !a.enabled -> p.muted
                                    db.style == ClockStyle.RADIO -> p.led
                                    db.style == ClockStyle.RETRO -> p.accent
                                    else -> p.text
                                },
                                if (db.style == ClockStyle.RETRO) FontWeight.Normal else FontWeight.SemiBold,
                                family = db.f.display, italic = db.style == ClockStyle.RADIO,
                            )
                            if (!use24) DText(" " + ampm(a.hour), 16.sp, p.muted, modifier = Modifier.padding(bottom = 7.dp))
                        }
                        if (a.enabled) {
                            val now = System.currentTimeMillis()
                            val next = Scheduler.nextTrigger(a)
                            if (a.skipUntil > now) {
                                DText("Dismissed ${ringText(a.skipUntil, use24)}", 12.sp, p.muted)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    DText("Next ${ringText(next, use24)}", 12.sp, p.muted)
                                    SmallLink("Undo") { Scheduler.undoDismiss(a) }
                                }
                            } else {
                                DText("in ${untilText(next)}", 12.sp, p.muted)
                                if (next - now < 24 * 60 * 60 * 1000L) SmallLink("Dismiss ${ringText(next, use24)}") { dismissNextAndTell(a, use24) }
                            }
                        }
                    }
                    FToggle(a.enabled, { on -> saveAndSchedule(a.copy(enabled = on), on) }, "Alarm ${formatTime(a.hour, a.minute, use24)}")
                }
                Spacer(Modifier.height(10.dp))
                DText(a.label.ifBlank { "Alarm" }, 15.sp, p.text, FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAY_LETTERS.forEach { (d, letter) ->
                        val on = d in a.days
                        DText(letter, 13.sp, if (on) p.link else p.muted.copy(alpha = 0.6f), if (on) FontWeight.Bold else FontWeight.Normal)
                    }
                    if (a.days.isEmpty()) DText("  Once", 13.sp, p.muted)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(DbIcons.Music, null, tint = p.muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    DText(a.soundName, 12.sp, p.muted, maxLines = 1, modifier = Modifier.weight(1f))
                    if (a.gradual) {
                        Icon(DbIcons.Ramp, null, tint = p.link, modifier = Modifier.size(14.dp))
                        DText(" Gradual", 12.sp, p.link)
                    }
                    Box(Modifier.size(32.dp)) {
                        if (hovered) FIconButton(DbIcons.Trash, "Delete alarm", size = 32.dp) {
                            Scheduler.cancel(a.id)
                            Store.deleteAlarm(a.id)
                            Notices.show("Alarm deleted")
                        }
                    }
                }
            }
        }
    }
}

// ---------- dialogs ----------

private val RAMP_OPTIONS = listOf(30 to "30 seconds", 60 to "1 minute", 180 to "3 minutes", 300 to "5 minutes")
private val SNOOZE_OPTIONS = listOf(5, 10, 15, 20, 30)

@Composable
fun AlarmDialog(edit: AlarmEdit, settings: Settings, onClose: () -> Unit) {
    val p = LocalDb.current.p
    val existing = edit.id?.let { Store.alarm(it) }
    var a by remember(edit) {
        mutableStateOf(existing ?: Alarm(id = Store.nextAlarmId(), hour = 7, minute = 0, days = setOf(1, 2, 3, 4, 5)))
    }
    ContentDialog(
        title = if (existing == null) "Add new alarm" else "Edit alarm",
        onDismiss = onClose,
        primary = "Save",
        onPrimary = { saveAndSchedule(a.copy(enabled = true), true); onClose() },
        footerStart = {
            if (existing != null) {
                FButton("Delete", icon = DbIcons.Trash) {
                    Scheduler.cancel(a.id); Store.deleteAlarm(a.id); Notices.show("Alarm deleted"); onClose()
                }
            }
            FButton("Preview", icon = DbIcons.Play) {
                AlarmService.ring(Scheduler.KIND_PREVIEW, a.id, AlarmService.Preview(a.soundUri, a.soundName, a.gradual, a.rampSeconds, a.label))
            }
        },
    ) {
        TimeSpinner(a.hour, a.minute, settings.use24h) { h, m -> a = a.copy(hour = h, minute = m) }
        OutlinedTextField(
            value = a.label,
            onValueChange = { a = a.copy(label = it.take(40)) },
            label = { DText("Alarm name", 13.sp, p.muted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = p.text, unfocusedTextColor = p.text,
                focusedBorderColor = p.accent, unfocusedBorderColor = p.border, cursorColor = p.accent,
            ),
        )
        DText("Repeat · ${describeDays(a.days)}", 14.sp, p.text)
        DayToggles(a.days) { d -> a = a.copy(days = if (d in a.days) a.days - d else a.days + d) }
        FCard(Modifier.fillMaxWidth(), padding = 0.dp) {
            SoundPicker("Sound", a.soundName, allowRadio = true) { uri, name -> a = a.copy(soundUri = uri, soundName = name) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                DText("Gradually increase volume", 14.sp, p.text)
                DText(if (a.gradual) "Starts quiet and reaches full volume" else "Rings at full volume right away", 12.sp, p.muted)
            }
            FToggle(a.gradual, { a = a.copy(gradual = it) }, "Gradually increase volume")
        }
        if (a.gradual) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DText("Ramp up over", 14.sp, p.text, modifier = Modifier.weight(1f))
                ComboBox(RAMP_OPTIONS.firstOrNull { it.first == a.rampSeconds }?.second ?: "${a.rampSeconds} seconds", RAMP_OPTIONS.map { it.second }) { i ->
                    a = a.copy(rampSeconds = RAMP_OPTIONS[i].first)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            DText("Snooze time", 14.sp, p.text, modifier = Modifier.weight(1f))
            ComboBox("${a.snoozeMinutes} minutes", SNOOZE_OPTIONS.map { "$it minutes" }) { i -> a = a.copy(snoozeMinutes = SNOOZE_OPTIONS[i]) }
        }
    }
}

@Composable
fun BedtimeDialog(settings: Settings, onClose: () -> Unit) {
    val p = LocalDb.current.p
    var b by remember { mutableStateOf(settings.bedtime) }
    ContentDialog(
        title = "Bedtime",
        onDismiss = onClose,
        primary = "Save",
        width = 560.dp,
        onPrimary = {
            var nb = b
            if (nb.linkAlarm) {
                val crosses = nb.wakeHour * 60 + nb.wakeMinute <= nb.bedHour * 60 + nb.bedMinute
                val wakeDays = if (crosses) nb.days.map { it % 7 + 1 }.toSet() else nb.days
                val id = if (nb.linkedAlarmId != 0 && Store.alarm(nb.linkedAlarmId) != null) nb.linkedAlarmId else Store.nextAlarmId()
                val base = Store.alarm(id) ?: Alarm(id = id, label = "Wake up")
                saveAndSchedule(base.copy(hour = nb.wakeHour, minute = nb.wakeMinute, days = wakeDays, enabled = nb.enabled), false)
                nb = nb.copy(linkedAlarmId = id)
            }
            Store.updateSettings { it.copy(bedtime = nb) }
            Notices.show(if (nb.enabled) "Bedtime reminder saved" else "Bedtime saved (reminder off)")
            onClose()
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                DText("Bedtime reminder", 14.sp, p.text)
                DText("A notification when it's time to wind down", 12.sp, p.muted)
            }
            FToggle(b.enabled, { b = b.copy(enabled = it) }, "Bedtime reminder")
        }
        DText("Bedtime", 14.sp, p.text, FontWeight.SemiBold)
        TimeSpinner(b.bedHour, b.bedMinute, settings.use24h) { h, m -> b = b.copy(bedHour = h, bedMinute = m) }
        DText("Wake up", 14.sp, p.text, FontWeight.SemiBold)
        TimeSpinner(b.wakeHour, b.wakeMinute, settings.use24h) { h, m -> b = b.copy(wakeHour = h, wakeMinute = m) }
        DText("That's ${sleepLength(b.bedHour * 60 + b.bedMinute, b.wakeHour * 60 + b.wakeMinute)} of sleep.", 13.sp, p.muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            DText("Remind me", 14.sp, p.text, modifier = Modifier.weight(1f))
            ComboBox("${b.remindMinutes} minutes before", listOf(15, 30, 45, 60).map { "$it minutes before" }) { i ->
                b = b.copy(remindMinutes = listOf(15, 30, 45, 60)[i])
            }
        }
        DText("Nights · ${describeDays(b.days)}", 14.sp, p.text)
        DayToggles(b.days) { d -> b = b.copy(days = if (d in b.days) b.days - d else b.days + d) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                DText("Set a matching wake-up alarm", 14.sp, p.text)
                DText("Creates a \"Wake up\" alarm at ${formatTime(b.wakeHour, b.wakeMinute, settings.use24h)}", 12.sp, p.muted)
            }
            FToggle(b.linkAlarm, { b = b.copy(linkAlarm = it) }, "Set a matching wake-up alarm")
        }
    }
}

/** A small text link, for actions inside an alarm tile. */
@Composable
private fun SmallLink(text: String, onClick: () -> Unit) {
    val p = LocalDb.current.p
    DText(
        text, 12.sp, p.link, FontWeight.SemiBold,
        modifier = Modifier.padding(top = 2.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand),
    )
}
