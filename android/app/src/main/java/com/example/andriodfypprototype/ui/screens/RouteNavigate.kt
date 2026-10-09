package com.example.andriodfypprototype.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.example.andriodfypprototype.data.AppState
import com.example.andriodfypprototype.data.Fmt
import com.example.andriodfypprototype.data.Geo
import com.example.andriodfypprototype.data.Noise
import com.example.andriodfypprototype.data.Pt
import com.example.andriodfypprototype.data.ZoneState
import com.example.andriodfypprototype.ui.KeepScreenOn
import com.example.andriodfypprototype.ui.Routes
import com.example.andriodfypprototype.ui.StatusBarIcons
import com.example.andriodfypprototype.ui.components.Hairline
import com.example.andriodfypprototype.ui.components.IconAction
import com.example.andriodfypprototype.ui.components.InfoSheet
import com.example.andriodfypprototype.ui.components.ListCard
import com.example.andriodfypprototype.ui.components.ListRow
import com.example.andriodfypprototype.ui.components.MapButton
import com.example.andriodfypprototype.ui.components.PrimaryButton
import com.example.andriodfypprototype.ui.components.SectionHeader
import com.example.andriodfypprototype.ui.components.Stat
import com.example.andriodfypprototype.ui.components.StatusPill
import com.example.andriodfypprototype.ui.components.SwipeToConfirm
import com.example.andriodfypprototype.ui.components.TonalButton
import com.example.andriodfypprototype.ui.components.Tone
import com.example.andriodfypprototype.ui.components.rememberHaptics
import com.example.andriodfypprototype.ui.map.Aerial
import com.example.andriodfypprototype.ui.map.MapSurface
import com.example.andriodfypprototype.ui.map.ScaleBar
import com.example.andriodfypprototype.ui.map.ZonePin
import com.example.andriodfypprototype.ui.map.dimOutside
import com.example.andriodfypprototype.ui.map.fieldOutline
import com.example.andriodfypprototype.ui.map.fitBounds
import com.example.andriodfypprototype.ui.map.imageryFor
import com.example.andriodfypprototype.ui.map.locationPuck
import com.example.andriodfypprototype.ui.map.mapAnchor
import com.example.andriodfypprototype.ui.map.prescriptionOutline
import com.example.andriodfypprototype.ui.map.rememberDashPhase
import com.example.andriodfypprototype.ui.map.rememberMapCamera
import com.example.andriodfypprototype.ui.map.rememberPulse
import com.example.andriodfypprototype.ui.map.routeLine
import com.example.andriodfypprototype.ui.theme.Wr
import com.example.andriodfypprototype.ui.theme.WrShape
import com.example.andriodfypprototype.ui.theme.WrType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

private const val WALK_MS = 1.25f

/* ================================================================== spray route */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteScreen(nav: NavHostController, fieldId: String) {
    val f = AppState.field(fieldId)
    val zones = AppState.zones(fieldId)
    val next = zones.firstOrNull { !it.state.done }
    val done = zones.count { it.state.done }
    val operator = AppState.operatorAt[fieldId] ?: f.gate
    val stops = listOf(f.gate) + zones.map { it.center }
    val totalWalk = zones.sumOf { it.distanceM }
    val leftWalk = zones.filter { !it.state.done }.sumOf { it.distanceM }
    val camera = rememberMapCamera()
    val sheet = rememberBottomSheetScaffoldState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val phase = rememberDashPhase()
    val pulse = rememberPulse()
    val grid = remember { AppState.grid(fieldId) }
    val edges = remember { Aerial.prescriptionEdges(grid) }
    var exportInfo by remember { mutableStateOf(false) }
    StatusBarIcons(light = true)

    val peek = 232.dp
    MapSheetScaffold(
        state = sheet,
        peek = peek + with(density) { WindowInsets.navigationBars.getBottom(density).toDp() },
        sheet = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    if (next != null) {
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            ZoneBadge(next, 46.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (done == 0) "First stop" else "Next stop", style = WrType.Overline)
                                Text(next.label, style = WrType.DisplayS)
                                Text("${next.severity.label} · ${next.dominantClass.label.lowercase()} · ${Fmt.sqm(next.areaSqm)}", style = WrType.BodyS)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                val d = operator.dist(next.center)
                                Text(Fmt.meters(d), style = WrType.NumM)
                                Text("${max(1, (d / WALK_MS / 60f).roundToInt())} min walk", style = WrType.Caption)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton("Start navigation", { nav.navigate(Routes.navigate(fieldId, next.id)) }, icon = Icons.Rounded.Navigation, height = 52.dp)
                    } else {
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(46.dp).clip(CircleShape).background(Wr.Sage), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.TaskAlt, null, tint = Wr.Forest)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Route complete", style = WrType.DisplayS)
                                Text("All ${zones.size} zones marked treated", style = WrType.BodyS)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton("Record treatment", { nav.navigate(Routes.treatment(fieldId)) }, icon = Icons.Rounded.EditNote, height = 52.dp)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row {
                        Stat("$done/${zones.size}", "Zones treated", Modifier.weight(1f))
                        Stat(Fmt.meters(leftWalk.toFloat()), "Walk left", Modifier.weight(1f))
                        Stat(Fmt.sqm(zones.sumOf { it.areaSqm }), "Spray area", Modifier.weight(1f))
                    }
                }
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                    SectionHeader("Stops · ${zones.size}", info = { exportInfo = true })
                    ListCard {
                        ListRow(
                            "Field gate", "Start · ${f.name} west bund",
                            leading = { Box(Modifier.size(38.dp).clip(CircleShape).background(Wr.CreamDeep), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, tint = Wr.Ink2, modifier = Modifier.size(20.dp)) } },
                            chevron = false
                        )
                        zones.forEachIndexed { i, z ->
                            Hairline(inset = 70.dp)
                            ListRow(
                                z.label,
                                "${z.dominantClass.short} · ${Fmt.sqm(z.areaSqm)} · ${z.distanceM} m from ${if (i == 0) "gate" else zones[i - 1].label}",
                                leading = { ZoneBadge(z) },
                                trailing = {
                                    if (z.state.done) StatusPill("Treated", Tone.Forest)
                                    else StatusPill("Stop ${i + 1}", Tone.Neutral)
                                },
                                chevron = false,
                                onClick = { nav.navigate(Routes.navigate(fieldId, z.id)) }
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Nearest-first from the gate · ${Fmt.meters(totalWalk.toFloat())} in total · works offline on cached imagery",
                        style = WrType.Caption
                    )
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    ) {
        Box(Modifier.fillMaxSize()) {
            MapSurface(
                spec = imageryFor(f),
                camera = camera,
                fit = f.fitBounds(),
                modifier = Modifier.fillMaxSize(),
                fitPadding = 24.dp,
                insetTop = 76.dp,
                insetBottom = peek,
                overlay = { c ->
                    dimOutside(c, f.boundary, 0.3f)
                    prescriptionOutline(c, edges, alpha = 0.55f)
                    fieldOutline(c, f.boundary)
                    val doneLegs = zones.indexOfFirst { !it.state.done }.let { if (it < 0) zones.size else it }
                    routeLine(c, stops, phase, doneLegs)
                    locationPuck(c, operator, 3.4f, null, pulse)
                },
                markers = {
                    zones.forEachIndexed { i, z ->
                        ZonePin(z, Modifier.mapAnchor(camera, z.center), size = 28.dp, active = z.id == next?.id, index = i + 1)
                    }
                }
            )
            MapTopBar("Spray route · ${f.name}", "${Fmt.plural(zones.size, "stop")} · nearest first", onBack = { nav.popBackStack() }) {
                MapButton(Icons.Rounded.CropFree, "Fit route", {
                    scope.launch { camera.animateFit(f.fitBounds(), with(density) { 24.dp.toPx() }) }
                })
            }
            ScaleBar(camera, Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = peek + 18.dp), dark = true)
        }
    }

    if (exportInfo) {
        InfoSheet(
            "Why nearest first",
            listOf(
                "Each leg is the shortest walk from where you are standing, which is what a person with a knapsack needs. It is not the globally shortest tour, and on five or six zones the difference is a few metres.",
                "Tractor operators get the same prescription as ISO 11783 TASKDATA, GeoJSON or Shapefile, generated on the dashboard for section control."
            ),
            onDismiss = { exportInfo = false }
        )
    }
}

/* ================================================================== walk to a zone */

@Composable
fun NavigateScreen(nav: NavHostController, fieldId: String, zoneId: String) {
    val f = AppState.field(fieldId)
    val zones = AppState.zones(fieldId)
    val zone = AppState.zone(fieldId, zoneId) ?: return
    val start = remember { AppState.operatorAt[fieldId] ?: f.gate }
    var pos by remember { mutableStateOf(start) }
    var heading by remember { mutableFloatStateOf(Geo.bearing(start, zone.center)) }
    var walking by remember { mutableStateOf(!zone.state.done) }
    var voice by remember { mutableStateOf(AppState.voicePrompts) }
    val haptics = rememberHaptics()
    val camera = rememberMapCamera()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val pulse = rememberPulse()
    val phase = rememberDashPhase()
    val grid = remember { AppState.grid(fieldId) }
    val edges = remember { Aerial.prescriptionEdges(grid) }
    StatusBarIcons(light = true)
    KeepScreenOn()

    val distance = pos.dist(zone.center)
    val arriveRadius = max(3f, zone.radiusM * 0.45f)
    val arrived = distance <= arriveRadius
    val bearing = Geo.bearing(pos, zone.center)
    val gnss = 3.1f + Noise.value(pos.x * 0.05f, pos.y * 0.05f, 9) * 1.4f

    // Simulated walk: 1.25 m/s shown in time-lapse, with the sway of a person on a bund.
    LaunchedEffect(walking, zoneId) {
        var t = 0f
        var lastBand = ((pos.dist(zone.center)) / 10f).toInt()
        while (walking) {
            delay(60)
            val d = pos.dist(zone.center)
            if (d <= arriveRadius * 0.6f) {
                walking = false
                haptics.confirm()
                break
            }
            t += 0.06f
            val step = WALK_MS * 3.2f * 0.06f
            val b = Math.toRadians(Geo.bearing(pos, zone.center).toDouble()).toFloat()
            val sway = (Noise.value(t * 0.9f, 3f, 7) - 0.5f) * 0.5f
            pos = Pt(
                pos.x + kotlin.math.sin(b) * step + kotlin.math.cos(b) * sway * 0.12f,
                pos.y - kotlin.math.cos(b) * step + kotlin.math.sin(b) * sway * 0.12f
            )
            heading += ((Geo.bearing(pos, zone.center) + sway * 18f) - heading) * 0.2f
            AppState.operatorAt[fieldId] = pos
            val band = (pos.dist(zone.center) / 10f).toInt()
            if (band < lastBand && band <= 2) haptics.tick()
            lastBand = band
        }
    }

    // Camera keeps the operator and the target both in view, tightening as they close.
    LaunchedEffect(Unit) {
        while (true) {
            if (camera.ready && !camera.userMoved) {
                val span = max(28f, pos.dist(zone.center) * 1.25f)
                val cx = (pos.x + zone.cx) / 2f
                val cy = (pos.y + zone.cy) / 2f
                val z = camera.fitZoom(floatArrayOf(cx - span / 2, cy - span / 2, cx + span / 2, cy + span / 2), with(density) { 30.dp.toPx() })
                camera.center = Pt(camera.center.x + (cx - camera.center.x) * 0.12f, camera.center.y + (cy - camera.center.y) * 0.12f)
                camera.zoom += (z - camera.zoom) * 0.08f
            }
            delay(16)
        }
    }

    val next = zones.firstOrNull { !it.state.done && it.id != zone.id }
    val headerBg by animateColorAsState(if (arrived || zone.state.done) Wr.Forest2 else Wr.Night2, tween(400), label = "hdr")

    Box(Modifier.fillMaxSize().background(Wr.Night)) {
        MapSurface(
            spec = imageryFor(f),
            camera = camera,
            fit = floatArrayOf(zone.cx - 40f, zone.cy - 40f, zone.cx + 40f, zone.cy + 40f),
            modifier = Modifier.fillMaxSize(),
            insetTop = 150.dp,
            insetBottom = 250.dp,
            overlay = { c ->
                dimOutside(c, f.boundary, 0.32f)
                prescriptionOutline(c, edges, alpha = 0.5f)
                fieldOutline(c, f.boundary, width = 1.6f)
                val r = arriveRadius * c.zoom
                val ctr = c.project(zone.center)
                drawCircle((if (arrived) Wr.Moss else zone.severity.fill).copy(alpha = 0.22f), r, ctr)
                drawCircle(Color.White, r, ctr, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
                if (!arrived && !zone.state.done) routeLine(c, listOf(pos, zone.center), phase)
                locationPuck(c, pos, gnss, heading, pulse)
            },
            markers = {
                zones.forEach { z ->
                    ZonePin(z, Modifier.mapAnchor(camera, z.center), size = if (z.id == zone.id) 30.dp else 22.dp, active = z.id == zone.id && !arrived)
                }
            }
        )

        // ---- instruction header
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth()
                    .shadow(14.dp, WrShape.lg, ambientColor = Color.Black, spotColor = Color(0x88000000))
                    .clip(WrShape.lg).background(headerBg)
                    .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", { nav.popBackStack() }, tint = Color.White)
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(48.dp).clip(WrShape.md).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    if (arrived || zone.state.done) Icon(Icons.Rounded.TaskAlt, null, tint = Color.White, modifier = Modifier.size(28.dp))
                    else Icon(Icons.Rounded.Navigation, null, tint = Color.White, modifier = Modifier.size(28.dp).rotate(bearing))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    AnimatedContent(
                        when {
                            zone.state.done -> "${zone.label} treated"
                            arrived -> "You're in ${zone.label}"
                            else -> "Head ${Geo.compass(bearing)}"
                        },
                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "instr"
                    ) { Text(it, style = WrType.TitleL.copy(color = Color.White)) }
                    Text(
                        when {
                            zone.state.done -> "Logged ${zone.treatedAt?.let { Fmt.time(it) } ?: ""} · queued for sync"
                            arrived -> "Spray the marked cells, then confirm below"
                            else -> "To ${zone.label} · ${zone.severity.label.lowercase()} ${zone.dominantClass.short.lowercase()}"
                        },
                        style = WrType.BodyS.copy(color = Color.White.copy(alpha = 0.72f))
                    )
                }
                if (!arrived && !zone.state.done) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${distance.roundToInt()}", style = WrType.NumL.copy(color = Color.White))
                        Text("metres", style = WrType.Caption.copy(color = Color.White.copy(alpha = 0.6f)))
                    }
                }
            }
        }

        // ---- right-hand controls
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            horizontalAlignment = Alignment.End
        ) {
            MapButton(if (voice) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, "Voice prompts", { voice = !voice; AppState.voicePrompts = voice })
            Spacer(Modifier.height(10.dp))
            MapButton(Icons.Rounded.MyLocation, "Follow me", { camera.userMoved = false }, active = !camera.userMoved)
        }

        // ---- bottom panel
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .shadow(20.dp, WrShape.sheet, ambientColor = Color.Black, spotColor = Color(0x66000000))
                .clip(WrShape.sheet).background(Wr.Cream)
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZoneBadge(zone, 42.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(zone.label, style = WrType.TitleM)
                    Text("${Fmt.sqm(zone.areaSqm)} · ${zone.cellCount} cells at ${AppState.gridSize.label} · ${zone.meanInfestPct.toInt()}% cover", style = WrType.Caption)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SatelliteAlt, null, tint = if (gnss < 4f) Wr.Moss else Wr.Wheat, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("±${"%.1f".format(gnss)} m", style = WrType.Caption.copy(color = Wr.Ink2, fontFeatureSettings = "tnum"))
                }
            }
            Spacer(Modifier.height(16.dp))
            AnimatedContent(
                when { zone.state.done -> 2; arrived -> 1; else -> 0 },
                transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(120)) }, label = "panel"
            ) { stage ->
                Column {
                    when (stage) {
                        0 -> Row {
                            TonalButton(if (walking) "Pause" else "Resume", { walking = !walking }, Modifier.weight(1f), icon = if (walking) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, tone = Tone.Neutral, height = 52.dp, fill = true)
                            Spacer(Modifier.width(10.dp))
                            TonalButton("Scan a plant", {
                                AppState.activeFieldId = fieldId
                                AppState.activeZoneId = zone.id
                                AppState.homeTab = 1
                                nav.popBackStack(Routes.HOME, false)
                            }, Modifier.weight(1f), icon = Icons.Rounded.CenterFocusStrong, height = 52.dp, fill = true)
                        }
                        1 -> SwipeToConfirm("Slide to mark ${zone.label} treated", onConfirmed = {
                            val previous = zone
                            AppState.markZone(fieldId, zone.id, ZoneState.TREATED)
                            AppState.toast("${zone.label} marked treated", "Undo") { AppState.undoZone(fieldId, previous) }
                        })
                        else -> {
                            if (next != null) {
                                PrimaryButton(
                                    "Next: ${next.label} · ${Fmt.meters(pos.dist(next.center))}",
                                    { nav.navigate(Routes.navigate(fieldId, next.id)) { popUpTo(Routes.route(fieldId)) } },
                                    trailingIcon = Icons.AutoMirrored.Rounded.ArrowForward
                                )
                            } else {
                                PrimaryButton(
                                    "Record treatment",
                                    { nav.navigate(Routes.treatment(fieldId)) { popUpTo(Routes.field(fieldId)) } },
                                    icon = Icons.Rounded.EditNote
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
