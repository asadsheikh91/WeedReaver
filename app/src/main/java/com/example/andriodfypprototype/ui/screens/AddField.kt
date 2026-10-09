package com.example.andriodfypprototype.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.example.andriodfypprototype.data.AppState
import com.example.andriodfypprototype.data.Demo
import com.example.andriodfypprototype.data.Fmt
import com.example.andriodfypprototype.data.Geo
import com.example.andriodfypprototype.data.Landscape
import com.example.andriodfypprototype.data.Noise
import com.example.andriodfypprototype.data.Pt
import com.example.andriodfypprototype.ui.KeepScreenOn
import com.example.andriodfypprototype.ui.Routes
import com.example.andriodfypprototype.ui.StatusBarIcons
import com.example.andriodfypprototype.ui.components.Card
import com.example.andriodfypprototype.ui.components.ConfirmDialog
import com.example.andriodfypprototype.ui.components.DetailScreen
import com.example.andriodfypprototype.ui.components.IconAction
import com.example.andriodfypprototype.ui.components.IconTile
import com.example.andriodfypprototype.ui.components.MapButton
import com.example.andriodfypprototype.ui.components.PrimaryButton
import com.example.andriodfypprototype.ui.components.SecondaryButton
import com.example.andriodfypprototype.ui.components.Sheet
import com.example.andriodfypprototype.ui.components.Stat
import com.example.andriodfypprototype.ui.components.StatusPill
import com.example.andriodfypprototype.ui.components.TextInput
import com.example.andriodfypprototype.ui.components.TonalButton
import com.example.andriodfypprototype.ui.components.Tone
import com.example.andriodfypprototype.ui.components.enter
import com.example.andriodfypprototype.ui.components.pressable
import com.example.andriodfypprototype.ui.components.rememberHaptics
import com.example.andriodfypprototype.ui.map.Aerial
import com.example.andriodfypprototype.ui.map.MapSurface
import com.example.andriodfypprototype.ui.map.ScaleBar
import com.example.andriodfypprototype.ui.map.fieldOutline
import com.example.andriodfypprototype.ui.map.gnssTrace
import com.example.andriodfypprototype.ui.map.locationPuck
import com.example.andriodfypprototype.ui.map.mapAnchor
import com.example.andriodfypprototype.ui.map.rememberMapCamera
import com.example.andriodfypprototype.ui.map.rememberPulse
import com.example.andriodfypprototype.ui.theme.Wr
import com.example.andriodfypprototype.ui.theme.WrShape
import com.example.andriodfypprototype.ui.theme.WrType
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.max

/* ================================================================== method chooser */

@Composable
fun AddFieldScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    var imported by remember { mutableStateOf<List<Pt>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        val poly = text?.let { BoundaryFile.parse(it) }
        if (poly == null || poly.size < 3) AppState.toast("No polygon found in that file. Use KML or GeoJSON.")
        else imported = poly
    }

    DetailScreen(
        "Add a field",
        onBack = { nav.popBackStack() },
        subtitle = "The boundary is stored as geometry and clipped at analysis time."
    ) {
        item {
            Spacer(Modifier.height(18.dp))
            MethodCard(
                Icons.AutoMirrored.Rounded.DirectionsWalk, "Walk the boundary",
                "Record your GNSS track while walking the bund. Most accurate on small parcels.",
                listOf("Recommended", "5–10 min"), Modifier.enter(0)
            ) { nav.navigate(Routes.WALK) }
            Spacer(Modifier.height(12.dp))
            MethodCard(
                Icons.Rounded.Draw, "Draw on the imagery",
                "Place corners on the cached survey mosaic. Best when the bunds are clearly visible.",
                listOf("2 min"), Modifier.enter(1)
            ) { nav.navigate(Routes.DRAW) }
            Spacer(Modifier.height(12.dp))
            MethodCard(
                Icons.Rounded.UploadFile, "Import a file",
                "KML or GeoJSON from the station's plot register or another mapping app.",
                listOf("KML", "GeoJSON"), Modifier.enter(2)
            ) { picker.launch(arrayOf("application/vnd.google-earth.kml+xml", "application/geo+json", "application/json", "text/xml", "*/*")) }
            Spacer(Modifier.height(20.dp))
            Text("Consumer GNSS is accurate to about 3 to 5 m. Fixes worse than 8 m are dropped before the track is simplified.", style = WrType.Caption)
        }
    }

    imported?.let { poly ->
        SaveFieldSheet(poly, "Imported", onDismiss = { imported = null }) { name, village ->
            val f = AppState.addField(name, village, poly, Landscape(seed = poly.hashCode()), "Imported")
            imported = null
            AppState.toast("${f.name} saved · survey flight to be scheduled")
            nav.navigate(Routes.field(f.id)) { popUpTo(Routes.HOME) }
        }
    }
}

@Composable
private fun MethodCard(icon: ImageVector, title: String, body: String, tags: List<String>, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier, padding = PaddingValues(18.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            IconTile(icon, Tone.Forest, 48.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = WrType.TitleM)
                Spacer(Modifier.height(4.dp))
                Text(body, style = WrType.BodyS)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEachIndexed { i, t -> StatusPill(t, if (i == 0 && t == "Recommended") Tone.Moss else Tone.Neutral) }
                }
            }
        }
    }
}

/** Converts KML or GeoJSON longitude/latitude rings to the local metric frame. */
object BoundaryFile {
    fun parse(text: String): List<Pt>? {
        val pairs = ArrayList<Pair<Double, Double>>()
        val kml = Regex("<coordinates>([\\s\\S]*?)</coordinates>").find(text)
        if (kml != null) {
            kml.groupValues[1].trim().split(Regex("\\s+")).forEach { t ->
                val p = t.split(",")
                if (p.size >= 2) { val lon = p[0].toDoubleOrNull(); val lat = p[1].toDoubleOrNull(); if (lon != null && lat != null) pairs.add(lon to lat) }
            }
        } else {
            Regex("\\[\\s*(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)\\s*]").findAll(text).forEach {
                pairs.add(it.groupValues[1].toDouble() to it.groupValues[2].toDouble())
            }
        }
        if (pairs.size < 3) return null
        val lat0 = pairs.maxOf { it.second }
        val lon0 = pairs.minOf { it.first }
        val pts = pairs.map { (lon, lat) ->
            Pt(((lon - lon0) * 111_320.0 * cos(Math.toRadians(lat0))).toFloat(), ((lat0 - lat) * 111_320.0).toFloat())
        }.let { if (it.first().dist(it.last()) < 0.5f) it.dropLast(1) else it }
        val a = Geo.area(pts)
        return if (a < 200f || a > 2_000_000f) null else pts
    }
}

@Composable
private fun SaveFieldSheet(poly: List<Pt>, method: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var village by remember { mutableStateOf("Pindi Bhattian, Hafizabad") }
    val ac = Geo.area(poly) / 4046.86f
    Sheet(onDismiss, "Name this field", "$method · ${poly.size} vertices") {
        Column(Modifier.padding(horizontal = 20.dp).imePadding()) {
            Card(padding = PaddingValues(vertical = 14.dp)) {
                Row(Modifier.padding(horizontal = 16.dp)) {
                    Stat(Fmt.acres(ac), Fmt.areaAlt(ac, true), Modifier.weight(1f), unit = "ac")
                    Stat(Fmt.meters(Geo.perimeter(poly)), "Perimeter", Modifier.weight(1f))
                    Stat("${poly.size}", "Vertices", Modifier.weight(0.7f))
                }
            }
            Spacer(Modifier.height(16.dp))
            TextInput(name, { name = it.take(32) }, label = "Field name", placeholder = "e.g. Tubewell killa")
            Spacer(Modifier.height(12.dp))
            TextInput(village, { village = it.take(48) }, label = "Village")
            Spacer(Modifier.height(20.dp))
            PrimaryButton("Save field", { onSave(name.trim(), village.trim()) }, enabled = name.isNotBlank(), icon = Icons.Rounded.Check)
        }
    }
}

/* ================================================================== boundary walk */

/** Where the operator actually walks: along the bund, with GNSS wander, one fix per second. */
private fun walkTrack(): List<Pt> {
    val poly = Demo.walkParcel
    val out = ArrayList<Pt>()
    var s = 0f
    for (i in poly.indices) {
        val a = poly[i]
        val b = poly[(i + 1) % poly.size]
        val len = a.dist(b)
        var t = 0f
        while (t < len) {
            val k = t / len
            val nx = (Noise.value(s * 0.05f, 1f, 71) - 0.5f) * 3.2f
            val ny = (Noise.value(s * 0.05f, 9f, 73) - 0.5f) * 3.2f
            out.add(Pt(a.x + (b.x - a.x) * k + nx, a.y + (b.y - a.y) * k + ny))
            t += 1.35f
            s += 1.35f
        }
    }
    return out
}

@Composable
fun WalkBoundaryScreen(nav: NavHostController) {
    val spec = remember { Aerial.spec("walk", Demo.walkParcel, Demo.walkLandscape) }
    val track = remember { walkTrack() }
    val fixes = remember { mutableStateListOf<Pt>() }
    var recording by remember { mutableStateOf(false) }
    var closed by remember { mutableStateOf<List<Pt>?>(null) }
    var discard by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var seconds by remember { mutableFloatStateOf(0f) }
    val camera = rememberMapCamera()
    val pulse = rememberPulse()
    val haptics = rememberHaptics()
    StatusBarIcons(light = true)
    KeepScreenOn()

    val accuracy = 2.9f + Noise.value(fixes.size * 0.1f, 2f, 5) * 1.6f
    val sats = 14 + (Noise.value(fixes.size * 0.03f, 4f, 6) * 6).toInt()

    fun finish() {
        recording = false
        val simplified = Geo.simplifyRing(fixes.toList(), 2f)
        closed = simplified
        haptics.confirm()
    }

    LaunchedEffect(recording) {
        while (recording && fixes.size < track.size) {
            delay(55)
            fixes.add(track[fixes.size])
            seconds += 1f
            camera.center = Pt(camera.center.x + (fixes.last().x - camera.center.x) * 0.1f, camera.center.y + (fixes.last().y - camera.center.y) * 0.1f)
            if (fixes.size > track.size * 0.9f && fixes.last().dist(fixes.first()) < 4f) { finish(); break }
        }
        if (recording && fixes.size >= track.size) finish()
    }

    BackHandler(fixes.isNotEmpty() && closed == null) { recording = false; discard = true }

    Box(Modifier.fillMaxSize().background(Wr.Night)) {
        MapSurface(
            spec = spec, camera = camera,
            fit = floatArrayOf(-30f, -30f, 160f, 136f),
            modifier = Modifier.fillMaxSize(),
            insetTop = 76.dp, insetBottom = 250.dp,
            overlay = { c ->
                val shape = closed
                if (shape != null) fieldOutline(c, shape, fill = Wr.Wheat.copy(alpha = 0.22f))
                else gnssTrace(c, fixes, false)
                val at = fixes.lastOrNull() ?: Demo.walkParcel.first()
                val prev = fixes.getOrNull(fixes.size - 4)
                if (shape == null) locationPuck(c, at, accuracy, prev?.let { Geo.bearing(it, at) }, pulse)
            },
            markers = {
                fixes.firstOrNull()?.let { start ->
                    Box(Modifier.mapAnchor(camera, start, 0.2f, 1f).size(30.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Flag, "Start", tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                }
            }
        )
        MapTopBar(
            if (closed != null) "Boundary captured" else "Walk the boundary",
            "GNSS ±${"%.1f".format(accuracy)} m · $sats satellites",
            onBack = { if (fixes.isNotEmpty() && closed == null) { recording = false; discard = true } else nav.popBackStack() }
        ) {
            MapButton(Icons.Rounded.MyLocation, "Follow", { camera.userMoved = false })
        }
        ScaleBar(camera, Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 266.dp), dark = true)

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .shadow(20.dp, WrShape.sheet).clip(WrShape.sheet).background(Wr.Cream)
                .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp)
        ) {
            val live = closed ?: fixes
            val ac = Geo.area(live) / 4046.86f
            Row {
                Stat(Fmt.acres(ac), if (closed != null) Fmt.areaAlt(ac, true) else "Area so far", Modifier.weight(1f), unit = "ac")
                Stat(Fmt.meters(Geo.perimeter(live, closed != null)), "Perimeter", Modifier.weight(1f))
                Stat(if (closed != null) "${closed!!.size}" else "${fixes.size}", if (closed != null) "Vertices" else "Fixes", Modifier.weight(0.8f))
                Stat(Fmt.duration(seconds.toInt()), "Walked", Modifier.weight(0.9f))
            }
            Spacer(Modifier.height(18.dp))
            AnimatedContent(closed != null, transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(120)) }, label = "walk") { done ->
                if (done) {
                    Column {
                        Text("Simplified from ${fixes.size} fixes at 2 m tolerance. No self-intersection.", style = WrType.Caption)
                        Spacer(Modifier.height(12.dp))
                        Row {
                            SecondaryButton("Re-walk", { closed = null; fixes.clear(); seconds = 0f }, Modifier.weight(1f))
                            Spacer(Modifier.width(10.dp))
                            PrimaryButton("Save field", { naming = true }, Modifier.weight(1.4f), height = 52.dp)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (fixes.size > 5) TonalButton("Undo", { repeat(max(1, minOf(8, fixes.size - 1))) { fixes.removeAt(fixes.lastIndex) } }, icon = Icons.AutoMirrored.Rounded.Undo, tone = Tone.Neutral)
                            else Text(if (fixes.isEmpty()) "Stand at a corner, then record" else "Keep to the bund", style = WrType.Caption)
                        }
                        RecordButton(recording) { haptics.heavy(); recording = !recording }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            if (fixes.size >= 30) TonalButton("Close ring", { finish() }, icon = Icons.Rounded.Check)
                        }
                    }
                }
            }
        }
    }

    if (discard) {
        ConfirmDialog(
            "Discard this walk?", "${fixes.size} GNSS fixes will be lost.", "Discard",
            onConfirm = { discard = false; nav.popBackStack() }, onDismiss = { discard = false }, destructive = true, dismiss = "Keep walking"
        )
    }
    if (naming) {
        closed?.let { poly ->
            SaveFieldSheet(poly, "Walked boundary", onDismiss = { naming = false }) { name, village ->
                val f = AppState.addField(name, village, poly, Demo.walkLandscape, "Walked")
                naming = false
                AppState.toast("${f.name} saved · survey flight to be scheduled")
                nav.navigate(Routes.field(f.id)) { popUpTo(Routes.HOME) }
            }
        }
    }
}

@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    val pulse = rememberPulse(1400)
    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        if (recording) Box(Modifier.size(84.dp).graphicsLayer { scaleX = 0.8f + pulse * 0.25f; scaleY = scaleX; alpha = 1f - pulse }.clip(CircleShape).background(Wr.Clay.copy(alpha = 0.35f)))
        Box(
            Modifier.size(70.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(Wr.Ivory)
                .border(3.dp, if (recording) Wr.Clay else Wr.LineStrong, CircleShape)
                .pressable(scaleTo = 0.9f, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(if (recording) Icons.Rounded.Pause else Icons.Rounded.FiberManualRecord, if (recording) "Pause" else "Record", tint = Wr.Clay, modifier = Modifier.size(34.dp))
        }
    }
}

/* ================================================================== draw on imagery */

@Composable
fun DrawBoundaryScreen(nav: NavHostController) {
    val spec = remember { Aerial.spec("draw", Demo.drawParcel, Demo.drawLandscape) }
    val pts = remember { mutableStateListOf<Pt>() }
    var naming by remember { mutableStateOf(false) }
    val camera = rememberMapCamera()
    val haptics = rememberHaptics()
    StatusBarIcons(light = true)

    fun add(p: Pt) {
        pts.add(p)
        haptics.tick()
    }

    Box(Modifier.fillMaxSize().background(Wr.Night)) {
        MapSurface(
            spec = spec, camera = camera,
            fit = floatArrayOf(-20f, -20f, 142f, 154f),
            modifier = Modifier.fillMaxSize(),
            insetTop = 76.dp, insetBottom = 210.dp,
            onTap = { add(it) },
            overlay = { c ->
                if (pts.size >= 3) fieldOutline(c, pts, Color(0xFFFFD66B), fill = Wr.Wheat.copy(alpha = 0.22f))
                else if (pts.size == 2) fieldOutline(c, pts, Color(0xFFFFD66B), closed = false)
                if (pts.isNotEmpty()) {
                    val last = c.project(pts.last())
                    drawLine(Color.White.copy(alpha = 0.7f), last, c.project(camera.center), 1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                }
            },
            markers = {
                pts.forEachIndexed { i, p ->
                    Box(
                        Modifier.mapAnchor(camera, p).size(if (i == 0) 18.dp else 14.dp).shadow(3.dp, CircleShape).clip(CircleShape)
                            .background(Color.White).padding(3.dp).clip(CircleShape).background(if (i == 0) Wr.Forest else Wr.Wheat)
                    )
                }
            }
        )
        // crosshair at the centre of the visible map
        Canvas(Modifier.align(Alignment.Center).offset(y = (76.dp - 210.dp) / 2).size(44.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            listOf(Offset(0f, 1f), Offset(1f, 0f)).forEach { d ->
                val a = c + Offset(d.x * size.width * 0.5f, d.y * size.height * 0.5f)
                val b = c - Offset(d.x * size.width * 0.5f, d.y * size.height * 0.5f)
                drawLine(Color.Black.copy(alpha = 0.4f), a, b, 4.dp.toPx())
                drawLine(Color.White, a, b, 2.dp.toPx())
            }
            drawCircle(Color.White, 4.dp.toPx(), c)
        }
        MapTopBar("Draw boundary", "Tap corners, or place the crosshair", onBack = { nav.popBackStack() })
        ScaleBar(camera, Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 226.dp), dark = true)

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .shadow(20.dp, WrShape.sheet).clip(WrShape.sheet).background(Wr.Cream)
                .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp)
        ) {
            val ac = Geo.area(pts) / 4046.86f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Stat(if (pts.size >= 3) Fmt.acres(ac) else "—", if (pts.size >= 3) Fmt.areaAlt(ac, true) else "Area", Modifier.weight(1f), unit = if (pts.size >= 3) "ac" else null)
                Stat("${pts.size}", "Corners", Modifier.weight(0.7f))
                IconAction(Icons.AutoMirrored.Rounded.Undo, "Undo corner", { if (pts.isNotEmpty()) pts.removeAt(pts.lastIndex) }, background = Wr.Ivory, border = true)
            }
            Spacer(Modifier.height(16.dp))
            Row {
                SecondaryButton("Add corner", { add(camera.center) }, Modifier.weight(1f), icon = Icons.Rounded.AddLocationAlt)
                Spacer(Modifier.width(10.dp))
                PrimaryButton("Finish", { naming = true }, Modifier.weight(1f), enabled = pts.size >= 3 && Geo.area(pts) > 200f, height = 52.dp)
            }
        }
    }

    if (naming) {
        SaveFieldSheet(pts.toList(), "Drawn on imagery", onDismiss = { naming = false }) { name, village ->
            val f = AppState.addField(name, village, pts.toList(), Demo.drawLandscape, "Drawn")
            naming = false
            AppState.toast("${f.name} saved · survey flight to be scheduled")
            nav.navigate(Routes.field(f.id)) { popUpTo(Routes.HOME) }
        }
    }
}
