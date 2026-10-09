package com.example.andriodfypprototype.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.ScreenLockPortrait
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SquareFoot
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.example.andriodfypprototype.data.AppState
import com.example.andriodfypprototype.data.Demo
import com.example.andriodfypprototype.data.Fmt
import com.example.andriodfypprototype.data.GridSize
import com.example.andriodfypprototype.data.LogKind
import com.example.andriodfypprototype.data.Store
import com.example.andriodfypprototype.data.SurveyStatus
import com.example.andriodfypprototype.data.net.ApiException
import com.example.andriodfypprototype.data.net.OfflineException
import com.example.andriodfypprototype.data.TreatmentRecord
import com.example.andriodfypprototype.data.WeedClass
import com.example.andriodfypprototype.ui.Routes
import com.example.andriodfypprototype.ui.components.Avatar
import com.example.andriodfypprototype.ui.components.BrandMark
import com.example.andriodfypprototype.ui.components.Card
import com.example.andriodfypprototype.ui.components.ChipRow
import com.example.andriodfypprototype.ui.components.ConfirmDialog
import com.example.andriodfypprototype.ui.components.CountBadge
import com.example.andriodfypprototype.ui.components.DetailScreen
import com.example.andriodfypprototype.ui.components.EmptyState
import com.example.andriodfypprototype.ui.components.Expandable
import com.example.andriodfypprototype.ui.components.Hairline
import com.example.andriodfypprototype.ui.components.IconTile
import com.example.andriodfypprototype.ui.components.InfoSheet
import com.example.andriodfypprototype.ui.components.KeyValueRow
import com.example.andriodfypprototype.ui.components.ListCard
import com.example.andriodfypprototype.ui.components.ListRow
import com.example.andriodfypprototype.ui.components.Meter
import com.example.andriodfypprototype.ui.components.Notice
import com.example.andriodfypprototype.ui.components.PrimaryButton
import com.example.andriodfypprototype.ui.components.SecondaryButton
import com.example.andriodfypprototype.ui.components.SectionHeader
import com.example.andriodfypprototype.ui.components.Segmented
import com.example.andriodfypprototype.ui.components.Sheet
import com.example.andriodfypprototype.ui.components.Stat
import com.example.andriodfypprototype.ui.components.StatusPill
import com.example.andriodfypprototype.ui.components.SwitchRow
import com.example.andriodfypprototype.ui.components.TabHeader
import com.example.andriodfypprototype.ui.components.TonalButton
import com.example.andriodfypprototype.ui.components.Tone
import com.example.andriodfypprototype.ui.components.enter
import com.example.andriodfypprototype.ui.components.pressable
import com.example.andriodfypprototype.ui.components.rememberHaptics
import com.example.andriodfypprototype.ui.map.FieldThumbnail
import com.example.andriodfypprototype.ui.theme.Wr
import com.example.andriodfypprototype.ui.theme.WrShape
import com.example.andriodfypprototype.ui.theme.WrType
import kotlinx.coroutines.delay

/* ================================================================== activity tab */

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActivityTab(nav: NavHostController) {
    var filter by remember { mutableStateOf("All") }
    var treatment by remember { mutableStateOf<TreatmentRecord?>(null) }
    val all = AppState.timeline()
    val kinds = mapOf(
        "Scans" to listOf(LogKind.SCAN),
        "Treatments" to listOf(LogKind.TREATMENT, LogKind.ZONE),
        "Surveys" to listOf(LogKind.SURVEY, LogKind.VERIFY)
    )
    val shown = if (filter == "All") all else all.filter { it.kind in kinds.getValue(filter) }
    val groups = shown.groupBy { Fmt.dayGroup(it.at) }
    val pending = AppState.pendingSync

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            TabHeader("Activity", "${Fmt.plural(all.size, "record")} on this phone") { SyncStatusButton(nav) }
        }
        if (pending > 0) {
            item {
                Notice(
                    "${Fmt.plural(pending, "change")} saved on this phone",
                    "They upload the next time the phone has signal. Nothing is lost if the app closes.",
                    icon = Icons.Rounded.CloudUpload, tone = Tone.Wheat,
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 8.dp).enter(0),
                    action = "Open sync", onAction = { nav.navigate(Routes.SYNC) }
                )
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            ChipRow(
                listOf("All", "Scans", "Treatments", "Surveys"), filter, { filter = it },
                counts = mapOf("All" to all.size) + kinds.mapValues { (_, k) -> all.count { it.kind in k } }
            )
        }
        if (shown.isEmpty()) {
            item { EmptyState(Icons.Rounded.History, "Nothing here yet", "Scans, treatments and surveys appear as they are recorded.") }
        }
        groups.forEach { (day, entries) ->
            stickyHeader(key = "h-$day-$filter") {
                Text(
                    day.uppercase(), style = WrType.Overline,
                    modifier = Modifier.fillMaxWidth().background(Wr.Cream).padding(start = 24.dp, top = 18.dp, bottom = 8.dp)
                )
            }
            item(key = "g-$day-$filter") {
                ListCard(Modifier.padding(horizontal = 20.dp)) {
                    entries.forEachIndexed { i, e ->
                        ActivityRow(e) {
                            if (e.kind == LogKind.TREATMENT) {
                                treatment = (AppState.treatments + AppState.priorTreatments).firstOrNull { it.id == e.refId }
                            } else openLogEntry(nav, e)
                        }
                        if (i != entries.lastIndex) Hairline(inset = 70.dp)
                    }
                }
            }
        }
    }

    treatment?.let { TreatmentSheet(it) { treatment = null } }
}

@Composable
fun TreatmentSheet(t: TreatmentRecord, onDismiss: () -> Unit) {
    Sheet(onDismiss, t.product, "${t.fieldName} · ${Fmt.date(t.appliedAt)}") {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill("HRAC ${t.hracGroup.code} · ${t.hracGroup.moa}", Tone.Neutral)
                StatusPill(if (t.synced) "Synced" else "Waiting to sync", if (t.synced) Tone.Forest else Tone.Wheat)
            }
            Spacer(Modifier.height(14.dp))
            ListCard {
                KeyValueRow("Active ingredient", t.activeIngredient)
                Hairline(inset = 16.dp)
                KeyValueRow("Dose recorded", "${t.doseRecorded} ${t.doseUnit}")
                Hairline(inset = 16.dp)
                KeyValueRow("Spray volume", if (t.waterLitres.isNotBlank()) "${t.waterLitres} L / acre" else "—")
                Hairline(inset = 16.dp)
                KeyValueRow("Zones", t.zoneLabels.joinToString())
                Hairline(inset = 16.dp)
                KeyValueRow("Area", Fmt.area(t.areaAcres))
                Hairline(inset = 16.dp)
                KeyValueRow("Method", t.applicationMode)
                Hairline(inset = 16.dp)
                KeyValueRow("Crop stage", t.growthStage)
                Hairline(inset = 16.dp)
                KeyValueRow("Operator", t.operator)
                if (t.notes.isNotBlank()) {
                    Hairline(inset = 16.dp)
                    KeyValueRow("Notes", t.notes)
                }
            }
            Spacer(Modifier.height(16.dp))
            SecondaryButton("Close", onDismiss)
        }
    }
}

/* ================================================================== sync */

@Composable
fun SyncScreen(nav: NavHostController) {
    var pushing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var justPushed by remember { mutableIntStateOf(0) }
    var rules by remember { mutableStateOf(false) }
    val pending = AppState.changeLog.size
    val haptics = rememberHaptics()

    // The meter eases towards the end while the station answers; it never claims more than it knows.
    LaunchedEffect(pushing) {
        if (!pushing) return@LaunchedEffect
        progress = 0f
        while (pushing) {
            delay(60)
            progress += (0.92f - progress) * 0.04f
        }
    }
    LaunchedEffect(pushing) {
        if (!pushing) return@LaunchedEffect
        try {
            val r = Store.sync()
            justPushed = r.applied + r.duplicates
            progress = 1f
            haptics.confirm()
            AppState.toast(
                when {
                    r.rejected > 0 -> "${Fmt.plural(justPushed, "change")} uploaded · ${r.rejected} rejected by the station"
                    justPushed == 0 -> "Up to date with the station"
                    else -> "${Fmt.plural(justPushed, "change")} uploaded and reconciled"
                }
            )
        } catch (e: OfflineException) {
            AppState.toast("The station could not be reached · changes stay on this phone")
        } catch (e: ApiException) {
            AppState.toast(e.message)
        } finally {
            pushing = false
        }
    }

    val state = when {
        pushing || AppState.syncing -> 2
        pending == 0 -> 3
        AppState.offline -> 0
        else -> 1
    }

    DetailScreen(
        "Sync",
        onBack = { nav.popBackStack() },
        subtitle = "The phone works offline. Records upload when there is signal.",
        bottomBar = {
            PrimaryButton(
                when (state) {
                    0 -> "Waiting for signal"
                    1 -> "Upload ${Fmt.plural(pending, "change")}"
                    2 -> "Uploading"
                    else -> if (AppState.offline) "Everything is up to date" else "Check for updates"
                },
                { pushing = true },
                enabled = state == 1 || (state == 3 && !AppState.offline), loading = pushing || AppState.syncing, icon = Icons.Rounded.CloudUpload
            )
        }
    ) {
        item {
            Card(Modifier.padding(top = 16.dp), padding = PaddingValues(20.dp)) {
                AnimatedContent(state, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "sync") { st ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (icon, tone) = when (st) {
                            0 -> Icons.Rounded.CloudOff to Tone.Wheat
                            1 -> Icons.Rounded.CloudUpload to Tone.Slate
                            2 -> Icons.Rounded.CloudSync to Tone.Slate
                            else -> Icons.Rounded.CloudDone to Tone.Forest
                        }
                        Box(Modifier.size(56.dp).clip(CircleShape).background(tone.bg), contentAlignment = Alignment.Center) {
                            Icon(icon, null, tint = tone.ink, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when (st) { 0 -> "No signal"; 1 -> "Ready to upload"; 2 -> "Uploading…"; else -> "Up to date" },
                                style = WrType.DisplayS
                            )
                            Text(
                                when (st) {
                                    0 -> "${Fmt.plural(pending, "change")} held safely on this phone"
                                    1 -> "Connected · ${Fmt.plural(pending, "change")} to send"
                                    2 -> "${(progress * pending.coerceAtLeast(1)).toInt().coerceAtLeast(1)} of ${pending.coerceAtLeast(1)} · reconciling with the dashboard"
                                    else -> "Last synced ${Fmt.relative(AppState.lastSyncAt)}"
                                },
                                style = WrType.BodyS
                            )
                        }
                    }
                }
                if (pushing) {
                    Spacer(Modifier.height(16.dp))
                    Meter(progress, color = Wr.Slate)
                }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            ListCard {
                SwitchRow(
                    "Network available",
                    if (AppState.networkUp) "Turn off to hold every change on this phone" else "No signal right now · changes wait here",
                    !AppState.workOffline, { Store.setWorkOffline(!it) }, icon = Icons.Rounded.CloudSync
                )
            }
        }
        item { SectionHeader(if (pending > 0) "Waiting to upload · $pending" else "Waiting to upload", info = { rules = true }) }
        item {
            ListCard {
                if (AppState.changeLog.isEmpty()) {
                    EmptyState(Icons.Rounded.CloudDone, "Nothing waiting", "Every scan, zone and treatment is written here first, then uploaded in order.")
                }
                val rows = AppState.changeLog.reversed()
                rows.forEachIndexed { i, c ->
                    ListRow(
                        c.summary, "${c.entity} · ${Fmt.relative(c.at)} · ${if (c.bytes > 10_000) "${c.bytes / 1000} KB" else "${c.bytes} B"}",
                        leading = {
                            Box(Modifier.size(38.dp).clip(CircleShape).background(Wr.CreamDeep), contentAlignment = Alignment.Center) {
                                Text("#${c.seq}", style = WrType.Caption.copy(color = Wr.Ink2, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
                            }
                        },
                        trailing = { StatusPill(if (c.ownedByMobile) "Phone wins" else "Dashboard wins", Tone.Neutral) },
                        chevron = false
                    )
                    if (i != rows.lastIndex) Hairline(inset = 70.dp)
                }
            }
        }
        if (AppState.rejected.isNotEmpty()) {
            item { SectionHeader("Rejected by the station · ${AppState.rejected.size}") }
            item {
                ListCard {
                    AppState.rejected.forEachIndexed { i, r ->
                        ListRow(
                            r.summary, "${r.entity} · ${Fmt.relative(r.at)} · ${r.reason}",
                            leading = {
                                Box(Modifier.size(38.dp).clip(CircleShape).background(Wr.ClayBg), contentAlignment = Alignment.Center) {
                                    Text("#${r.seq}", style = WrType.Caption.copy(color = Wr.ClayInk, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
                                }
                            },
                            trailing = { StatusPill("Rejected", Tone.Clay) },
                            chevron = false
                        )
                        Hairline(inset = 70.dp)
                    }
                    Text(
                        "Dismiss",
                        style = WrType.Label.copy(color = Wr.ClayInk, fontWeight = FontWeight.SemiBold),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clip(WrShape.pill).pressable { Store.dismissRejected() }.padding(14.dp)
                    )
                }
            }
        }
        item { SectionHeader("On this phone") }
        item {
            ListCard {
                KeyValueRow("Offline imagery", if (AppState.tilesCached) "${AppState.fields.size} fields · 84 MB" else "Not cached")
                Hairline(inset = 16.dp)
                KeyValueRow("Leaf model", "v0.3.2 · INT8 · 12.4 MB")
                Hairline(inset = 16.dp)
                KeyValueRow("Local records", "${AppState.scans.size + AppState.treatments.size + AppState.fields.size} rows · SQLite")
                Hairline(inset = 16.dp)
                KeyValueRow("App version", Demo.APP_VERSION)
            }
        }
    }

    if (rules) {
        InfoSheet(
            "Who wins a conflict",
            listOf(
                "Observations belong to the phone, because the operator was physically there: a scan, a zone marked treated, a product applied.",
                "Definitions belong to the dashboard, because it is the deciding surface: field boundaries, thresholds, zone geometry.",
                "It is never last-write-wins. Each change carries a sequence number from this phone so the server can replay them in order."
            ),
            onDismiss = { rules = false }
        )
    }
}

/* ================================================================== more tab */

@Composable
fun MoreTab(nav: NavHostController) {
    var signOut by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    var handover by remember { mutableStateOf(false) }
    val treatedAc = AppState.fields.sumOf { f -> AppState.zones(f.id).filter { it.state.done }.sumOf { it.areaSqm } } / 4046.86f

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item { TabHeader("More", AppState.orgName) }
        item {
            Card(Modifier.padding(horizontal = 20.dp).padding(top = 10.dp).enter(0), padding = PaddingValues(0.dp)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(if (AppState.trainee) "TR" else Fmt.initials(AppState.operatorName), size = 56.dp, color = if (AppState.trainee) Wr.Wheat else Wr.Forest)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(AppState.operatorName, style = WrType.DisplayS)
                        Text(listOf(if (AppState.trainee) "Trainee" else AppState.operatorRole, AppState.operatorCode).filter { it.isNotBlank() }.joinToString(" · "), style = WrType.BodyS)
                    }
                    StatusPill("Tier 2", Tone.Forest)
                }
                Hairline()
                Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Stat("${AppState.scans.size}", "Scans today", Modifier.weight(1f))
                    Stat("${AppState.treatments.size}", "Treatments", Modifier.weight(1f))
                    Stat(Fmt.acres(treatedAc), "Acres treated", Modifier.weight(1f))
                }
            }
        }
        item { SectionHeader("Field work", Modifier.padding(horizontal = 20.dp)) }
        item {
            ListCard(Modifier.padding(horizontal = 20.dp).enter(1)) {
                ListRow("Season", "${AppState.seasonLabel} · ${Fmt.plural(AppState.fields.size, "field")}", icon = Icons.Rounded.CalendarMonth) { nav.navigate(Routes.SEASON) }
                Hairline(inset = 70.dp)
                ListRow(
                    "Review queue", "Scans the model would not name", icon = Icons.Rounded.Inbox, tone = Tone.Wheat,
                    trailing = { CountBadge(AppState.abstentionQueue.size, color = Wr.Wheat) }
                ) { nav.navigate(Routes.QUEUE) }
                Hairline(inset = 70.dp)
                ListRow("Quadrat records", "${AppState.quadrats.size} frames verified", icon = Icons.Rounded.SquareFoot) { nav.navigate(Routes.QUADRAT) }
                Hairline(inset = 70.dp)
                ListRow("Farmer handover", "Voice summary and printed map", icon = Icons.Rounded.Handshake) { handover = true }
            }
        }
        item { SectionHeader("This phone", Modifier.padding(horizontal = 20.dp)) }
        item {
            ListCard(Modifier.padding(horizontal = 20.dp).enter(2)) {
                ListRow(
                    "Sync", if (AppState.pendingSync > 0) "${Fmt.plural(AppState.pendingSync, "change")} waiting" else "Up to date",
                    icon = Icons.Rounded.CloudSync, tone = Tone.Slate,
                    trailing = { CountBadge(AppState.pendingSync, color = Wr.Wheat) }
                ) { nav.navigate(Routes.SYNC) }
                Hairline(inset = 70.dp)
                ListRow("Settings", "Language, units, prompts, camera", icon = Icons.Rounded.Settings, tone = Tone.Neutral) { nav.navigate(Routes.SETTINGS) }
                Hairline(inset = 70.dp)
                ListRow("About this build", "Version ${Demo.APP_VERSION}", icon = Icons.Rounded.Info, tone = Tone.Neutral) { nav.navigate(Routes.ABOUT) }
            }
        }
        item {
            Spacer(Modifier.height(24.dp))
            Column(Modifier.padding(horizontal = 20.dp)) {
                SecondaryButton("Sign out", { signOut = true }, icon = Icons.AutoMirrored.Rounded.Logout, ink = Wr.ClayInk)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Reset demonstration data",
                    style = WrType.Label.copy(color = Wr.Ink3),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(WrShape.pill).pressable { reset = true }.padding(12.dp)
                )
            }
        }
    }

    if (signOut) {
        ConfirmDialog(
            "Sign out?",
            if (AppState.pendingSync > 0) "${Fmt.plural(AppState.pendingSync, "change")} have not been uploaded. They stay on this phone and upload after the next sign-in."
            else "Your records stay on this phone.",
            "Sign out",
            onConfirm = {
                signOut = false
                Store.signOut()
                AppState.homeTab = 0
                nav.navigate(Routes.WELCOME) { popUpTo(Routes.HOME) { inclusive = true } }
            },
            onDismiss = { signOut = false }, destructive = true
        )
    }
    if (reset) {
        ConfirmDialog(
            "Reset demonstration data?",
            "Scans, treatments and new fields not yet uploaded are deleted, and the station's copy is downloaded again.",
            "Reset",
            onConfirm = { reset = false; AppState.reset() },
            onDismiss = { reset = false }, destructive = true
        )
    }
    if (handover) HandoverSheet { handover = false }
}

@Composable
private fun HandoverSheet(onDismiss: () -> Unit) {
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(playing) { if (playing) { delay(6000); playing = false } }
    Sheet(onDismiss, "Farmer handover", "The farmer never needs to read the app.") {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Card(padding = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.RecordVoiceOver, Tone.Forest, 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Findings in Punjabi", style = WrType.TitleS)
                        Text("Recorded by a person, not text-to-speech · 0:48", style = WrType.Caption)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Waveform(playing)
                Spacer(Modifier.height(14.dp))
                TonalButton(if (playing) "Playing" else "Play", { playing = !playing }, icon = Icons.Rounded.VolumeUp, fill = true)
            }
            Spacer(Modifier.height(12.dp))
            Card(padding = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.Map, Tone.Wheat, 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Printed colour map", style = WrType.TitleS)
                        Text("A4, no text the map depends on", style = WrType.Caption)
                    }
                }
                Spacer(Modifier.height(14.dp))
                TonalButton("Queue for printing at the station", { AppState.toast("Map queued · prints when the phone syncs"); onDismiss() }, icon = Icons.Rounded.Print, tone = Tone.Neutral, fill = true)
            }
        }
    }
}

@Composable
private fun Waveform(playing: Boolean) {
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(0f, 6.28f, infiniteRepeatable(tween(900), RepeatMode.Restart), label = "p")
    Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        repeat(38) { i ->
            val base = 0.25f + 0.6f * kotlin.math.abs(kotlin.math.sin(i * 0.9f) * kotlin.math.cos(i * 0.37f))
            val live = if (playing) 0.3f + 0.7f * kotlin.math.abs(kotlin.math.sin(phase + i * 0.5f)) else 1f
            Box(
                Modifier.width(3.dp).height(36.dp).graphicsLayer { scaleY = base * live }
                    .clip(WrShape.pill).background(if (playing) Wr.Forest else Wr.LineStrong)
            )
        }
    }
}

/* ================================================================== settings */

@Composable
fun SettingsScreen(nav: NavHostController) {
    var language by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    DetailScreen("Settings", onBack = { nav.popBackStack() }) {
        item { SectionHeader("General") }
        item {
            ListCard {
                ListRow("Language", AppState.language, icon = Icons.Rounded.Language, tone = Tone.Neutral) { language = true }
                Hairline(inset = 70.dp)
                Column(Modifier.padding(16.dp)) {
                    Text("Area units", style = WrType.TitleS)
                    Spacer(Modifier.height(10.dp))
                    Segmented(listOf("Acre · kanal", "Hectare"), if (AppState.useLocalUnits) 0 else 1, { AppState.useLocalUnits = it == 0 })
                }
            }
        }
        item { SectionHeader("In the field") }
        item {
            ListCard {
                SwitchRow("Voice prompts", "Recorded Punjabi and Urdu instructions", AppState.voicePrompts, { AppState.voicePrompts = it }, icon = Icons.Rounded.VolumeUp)
                Hairline(inset = 66.dp)
                SwitchRow("Haptics", "Buzz as you close on a zone, and on confirmations", AppState.hapticProximity, { AppState.hapticProximity = it }, icon = Icons.Rounded.Vibration)
                Hairline(inset = 66.dp)
                SwitchRow("Keep screen on", "While navigating or walking a boundary", AppState.keepScreenOn, { AppState.keepScreenOn = it }, icon = Icons.Rounded.ScreenLockPortrait)
                Hairline(inset = 66.dp)
                SwitchRow("Use phone camera", "Off uses the built-in demonstration feed", AppState.useDeviceCamera, { AppState.useDeviceCamera = it }, icon = Icons.Rounded.PhotoCamera)
            }
        }
        item { SectionHeader("Maps") }
        item {
            ListCard {
                Column(Modifier.padding(16.dp)) {
                    Text("Default spray grid", style = WrType.TitleS)
                    Text(AppState.gridSize.actuator, style = WrType.Caption)
                    Spacer(Modifier.height(10.dp))
                    Segmented(GridSize.entries.map { it.label }, AppState.gridSize.ordinal, { AppState.gridSize = GridSize.entries[it] })
                }
                Hairline()
                SwitchRow("Keep imagery offline", "Download each field's mosaic before leaving signal", AppState.tilesCached, { AppState.tilesCached = it }, icon = Icons.Rounded.Map)
                Hairline(inset = 66.dp)
                ListRow("Clear offline imagery", "84 MB · downloads again on the next sync", icon = Icons.Rounded.DeleteSweep, tone = Tone.Neutral, chevron = false) { clear = true }
            }
        }
        item { SectionHeader("On-device models") }
        item {
            ListCard {
                ListRow("Leaf scanner", "${AppState.modelLeaf} · 12.4 MB", icon = Icons.Rounded.Memory, tone = Tone.Neutral, trailing = { StatusPill("On phone", Tone.Forest) }, chevron = false)
                Hairline(inset = 70.dp)
                ListRow("Aerial segmentation", AppState.modelAerial, icon = Icons.Rounded.FlightTakeoff, tone = Tone.Neutral, trailing = { StatusPill("Server", Tone.Neutral) }, chevron = false)
            }
            Spacer(Modifier.height(12.dp))
            Text("Model updates download with sync. The prescription threshold is set on the dashboard and cannot be changed here.", style = WrType.Caption)
        }
    }
    if (language) {
        OptionSheet("Language", listOf("English", "اردو", "ਪੰਜਾਬੀ"), AppState.language, {
            AppState.language = it
            language = false
            if (it != "English") AppState.toast("Voice prompts switched. Screen text follows in the field release.")
        }) { language = false }
    }
    if (clear) {
        ConfirmDialog(
            "Clear offline imagery?", "Maps show a grey grid until the imagery downloads again. Do this only where there is signal.", "Clear",
            onConfirm = { clear = false; AppState.toast("Offline imagery kept · demo build does not delete tiles") },
            onDismiss = { clear = false }, destructive = true
        )
    }
}

/* ================================================================== about */

@Composable
fun AboutScreen(nav: NavHostController) {
    DetailScreen("About this build", onBack = { nav.popBackStack() }) {
        item {
            Card(Modifier.padding(top = 16.dp), padding = PaddingValues(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(size = 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("WeedReaver", style = WrType.DisplayS)
                        Text("Field application · ${Demo.APP_VERSION}", style = WrType.BodyS)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Maps where weed control failed in a Punjab wheat field, verifies the survivor on the ground without a network, directs treatment to the cells that need it, and measures whether it worked.",
                    style = WrType.Body.copy(color = Wr.Ink2)
                )
            }
        }
        item { SectionHeader("Two surfaces") }
        item {
            ListCard {
                KeyValueRow("This phone does", "Navigate, verify, mark treated, record product")
                Hairline(inset = 16.dp)
                KeyValueRow("The dashboard decides", "Flight upload, labelling, thresholds, exports")
                Hairline(inset = 16.dp)
                KeyValueRow("Default mode", "Offline")
            }
        }
        item { SectionHeader("What the aerial model predicts") }
        item {
            ListCard {
                WeedClass.entries.forEachIndexed { i, c ->
                    ListRow(
                        c.label, c.chemistryHint,
                        leading = { Box(Modifier.size(38.dp).clip(WrShape.sm).background(c.color), contentAlignment = Alignment.Center) { Text("$i", style = WrType.TitleS.copy(color = if (i == 1) Wr.Ink else Color.White)) } },
                        chevron = false
                    )
                    if (i != 2) Hairline(inset = 68.dp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Three functional classes, not species: at 0.4 cm per pixel the ligule is gone, and the class is what decides the chemistry.", style = WrType.Caption)
        }
        item { SectionHeader("Simulated in this build") }
        item {
            ListCard {
                listOf(
                    "Leaf inference" to "Replays a fixed outcome sequence, including abstentions",
                    "Survey imagery" to "Rendered on the phone from field geometry",
                    "Segmentation" to "Infestation surface is generated, not predicted",
                    "GNSS and walking" to "Positions advance on a timer",
                    "Upload" to "The change log is real; the server is not"
                ).forEachIndexed { i, (k, v) ->
                    KeyValueRow(k, v)
                    if (i < 4) Hairline(inset = 16.dp)
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            Text("Scope: Punjab wheat, ${AppState.seasonLabel}. Not a dose calculator, a diagnostic authority or a general weed detector.", style = WrType.Caption)
        }
    }
}

/* ================================================================== season */

@Composable
fun SeasonScreen(nav: NavHostController) {
    val sown = Demo.date(12, 11, 2026)
    val harvest = Demo.date(15, 4, 2027)
    val day = Fmt.daysBetween(sown, Demo.now())
    val total = Fmt.daysBetween(sown, harvest)
    DetailScreen(AppState.seasonLabel, onBack = { nav.popBackStack() }, eyebrow = "Season", subtitle = "Wheat · ${Fmt.area(AppState.seasonAcres)} across ${Fmt.plural(AppState.fields.size, "field")}") {
        item {
            Card(Modifier.padding(top = 16.dp), padding = PaddingValues(18.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Day $day", style = WrType.NumL)
                    Spacer(Modifier.width(8.dp))
                    Text("of about $total", style = WrType.BodyS, modifier = Modifier.padding(bottom = 4.dp))
                    Spacer(Modifier.weight(1f))
                    StatusPill("Tillering", Tone.Moss)
                }
                Spacer(Modifier.height(12.dp))
                Meter(day / total.toFloat(), color = Wr.Moss, height = 8.dp, marker = 0.36f)
                Spacer(Modifier.height(8.dp))
                Row {
                    Text("Sown ${Fmt.dayMonth(sown)}", style = WrType.Caption, modifier = Modifier.weight(1f))
                    Text("Harvest ~${Fmt.dayMonth(harvest)}", style = WrType.Caption)
                }
            }
        }
        item { SectionHeader("Capture calendar") }
        item {
            val rows = listOf(
                Triple("Pre-treatment flight", Demo.date(7, 1, 2027), 2),
                Triple("Spray window", Demo.date(22, 1, 2027), if (AppState.fields.all { f -> AppState.zones(f.id).all { it.state.done } }) 2 else 1),
                Triple("Follow-up +14 d", Demo.date(21, 1, 2027), 2),
                Triple("Follow-up +28 d", Demo.date(4, 2, 2027), 0)
            )
            ListCard {
                rows.forEachIndexed { i, (title, at, st) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(26.dp).clip(CircleShape)
                                .background(if (st == 2) Wr.Forest else Wr.Ivory)
                                .border(2.dp, when (st) { 2 -> Wr.Forest; 1 -> Wr.Wheat; else -> Wr.LineStrong }, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (st == 2) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            else if (st == 1) Box(Modifier.size(8.dp).clip(CircleShape).background(Wr.Wheat))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, style = WrType.TitleS)
                            Text("${Fmt.weekdayShort(at)} · ${Fmt.inDays(at)}", style = WrType.Caption)
                        }
                        StatusPill(when (st) { 2 -> "Done"; 1 -> "Now"; else -> "Scheduled" }, when (st) { 2 -> Tone.Forest; 1 -> Tone.Wheat; else -> Tone.Neutral })
                    }
                    if (i != rows.lastIndex) Hairline(inset = 56.dp)
                }
            }
        }
        item { SectionHeader("Fields", action = "Add field", onAction = { nav.navigate(Routes.ADD_FIELD) }) }
        item {
            ListCard {
                AppState.fields.forEachIndexed { i, f ->
                    FieldListRow(f) { nav.navigate(Routes.field(f.id)) }
                    if (i != AppState.fields.lastIndex) Hairline(inset = 86.dp)
                }
            }
        }
    }
}

/* ================================================================== surveys */

@Composable
fun SurveysScreen(nav: NavHostController, fieldId: String) {
    val f = AppState.field(fieldId)
    val surveys = AppState.surveysOf(fieldId)
    var open by remember { mutableStateOf(surveys.firstOrNull { it.status == SurveyStatus.READY }?.id) }
    DetailScreen("Flights", onBack = { nav.popBackStack() }, eyebrow = f.name, subtitle = "Survey flights for this field-season and how they were processed.") {
        if (surveys.isEmpty()) item { EmptyState(Icons.Rounded.FlightTakeoff, "No flights", "Flights are planned and uploaded on the dashboard.") }
        surveys.forEach { s ->
            item(key = s.id) {
                Card(Modifier.padding(top = 14.dp), padding = PaddingValues(0.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        FieldThumbnail(f, size = 52.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.role.label, style = WrType.TitleS)
                            Text("${Fmt.weekdayShort(s.flownAt)} · ${Fmt.time(s.flownAt)}", style = WrType.Caption)
                        }
                        StatusPill(
                            s.status.label,
                            when (s.status) { SurveyStatus.READY -> Tone.Forest; SurveyStatus.PROCESSING -> Tone.Slate; SurveyStatus.FAILED -> Tone.Clay; else -> Tone.Neutral },
                            dot = s.status == SurveyStatus.PROCESSING
                        )
                    }
                    if (s.status == SurveyStatus.PROCESSING) {
                        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                            Meter(s.progress, color = Wr.Slate)
                            Spacer(Modifier.height(6.dp))
                            Text("Photogrammetry ${Fmt.pct(s.progress)} · OpenDroneMap on the station server", style = WrType.Caption)
                        }
                    }
                    if (s.status == SurveyStatus.SCHEDULED) {
                        Text("Planned ${Fmt.inDays(s.flownAt)} · 15 m AGL · 75/70 overlap", style = WrType.Caption, modifier = Modifier.padding(start = 14.dp, bottom = 14.dp))
                    } else {
                        Hairline()
                        Expandable("Flight parameters", open == s.id, { open = if (open == s.id) null else s.id }) {
                            KeyValueRow("Images", "${s.images}")
                            KeyValueRow("Altitude", "${s.altitudeM} m above ground")
                            KeyValueRow("Camera", s.sensor)
                            KeyValueRow("Ground sample distance", "${s.gsdCm} cm / px")
                            KeyValueRow("Overlap", "75% front · 70% side")
                            KeyValueRow("Model", AppState.modelAerial)
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            Notice(
                "Painted quadrat frames tie the tiers together",
                "Each frame is visible from 15 m and from 30 cm, so a leaf scan registers to the same ground the drone saw.",
                tone = Tone.Slate, action = "Open quadrat records", onAction = { nav.navigate(Routes.QUADRAT) }
            )
        }
    }
}
