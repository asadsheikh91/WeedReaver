package com.example.andriodfypprototype.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SquareFoot
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.example.andriodfypprototype.data.AppState
import com.example.andriodfypprototype.data.Demo
import com.example.andriodfypprototype.data.Fmt
import com.example.andriodfypprototype.data.LeafScan
import com.example.andriodfypprototype.data.WeedClass
import com.example.andriodfypprototype.ui.Routes
import com.example.andriodfypprototype.ui.StatusBarIcons
import com.example.andriodfypprototype.ui.components.BottomActions
import com.example.andriodfypprototype.ui.components.Card
import com.example.andriodfypprototype.ui.components.CountBadge
import com.example.andriodfypprototype.ui.components.DetailScreen
import com.example.andriodfypprototype.ui.components.EmptyState
import com.example.andriodfypprototype.ui.components.Hairline
import com.example.andriodfypprototype.ui.components.IconAction
import com.example.andriodfypprototype.ui.components.InfoSheet
import com.example.andriodfypprototype.ui.components.KeyValueRow
import com.example.andriodfypprototype.ui.components.LeafImage
import com.example.andriodfypprototype.ui.components.ListCard
import com.example.andriodfypprototype.ui.components.MapButton
import com.example.andriodfypprototype.ui.components.Meter
import com.example.andriodfypprototype.ui.components.Notice
import com.example.andriodfypprototype.ui.components.PrimaryButton
import com.example.andriodfypprototype.ui.components.Ring
import com.example.andriodfypprototype.ui.components.SecondaryButton
import com.example.andriodfypprototype.ui.components.SectionHeader
import com.example.andriodfypprototype.ui.components.Sheet
import com.example.andriodfypprototype.ui.components.Stat
import com.example.andriodfypprototype.ui.components.StatusPill
import com.example.andriodfypprototype.ui.components.TonalButton
import com.example.andriodfypprototype.ui.components.Tone
import com.example.andriodfypprototype.ui.components.pressable
import com.example.andriodfypprototype.ui.components.rememberHaptics
import com.example.andriodfypprototype.ui.components.rowPress
import com.example.andriodfypprototype.ui.theme.Wr
import com.example.andriodfypprototype.ui.theme.WrShape
import com.example.andriodfypprototype.ui.theme.WrType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ================================================================== scanner tab */

private enum class Phase { LIVE, CAPTURING, ANALYSING }

@Composable
fun ScanTab(nav: NavHostController) {
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val useCamera = AppState.useDeviceCamera && granted
    var phase by remember { mutableStateOf(Phase.LIVE) }
    var preview by remember { mutableStateOf<PreviewView?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf<Bitmap?>(null) }
    var picker by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    val flash = remember { Animatable(0f) }
    val demoSeed = Demo.scanOutcomes[AppState.scans.size % Demo.scanOutcomes.size].leafSeed

    val field = AppState.field(AppState.activeFieldId)
    val zone = AppState.activeZoneId?.let { AppState.zone(field.id, it) }

    Box(Modifier.fillMaxSize().background(Wr.Night)) {
        // ---- feed
        if (useCamera) {
            CameraFeed(Modifier.fillMaxSize(), onPreview = { preview = it }, onCamera = { camera = it })
        } else {
            DemoFeed(demoSeed, Modifier.fillMaxSize())
        }
        frozen?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x99000000), 0.22f to Color.Transparent, 0.72f to Color.Transparent, 1f to Color(0xCC0F1D15))))

        // ---- reticle
        Reticle(phase != Phase.LIVE, Modifier.align(Alignment.Center).offset(y = (-30).dp).size(264.dp))

        // ---- top controls
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.clip(WrShape.pill).background(Color.Black.copy(alpha = 0.38f))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), WrShape.pill)
                    .pressable { picker = true }
                    .padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (zone != null) zone.severity.fill else Wr.HeatLow))
                Spacer(Modifier.width(8.dp))
                Text(
                    field.name + (zone?.let { " · ${it.label}" } ?: ""),
                    style = WrType.Label.copy(color = Color.White, fontWeight = FontWeight.SemiBold)
                )
                Icon(Icons.Rounded.ExpandMore, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.weight(1f))
            if (useCamera) {
                IconAction(if (torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, "Torch", {
                    torch = !torch
                    camera?.cameraControl?.enableTorch(torch)
                }, tint = Color.White, background = Color.Black.copy(alpha = 0.38f))
                Spacer(Modifier.width(8.dp))
            }
            IconAction(Icons.Rounded.HelpOutline, "How to scan", { help = true }, tint = Color.White, background = Color.Black.copy(alpha = 0.38f))
        }

        // ---- status hint
        Column(Modifier.align(Alignment.Center).offset(y = 138.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val label = when (phase) {
                Phase.LIVE -> "Fill the frame with one plant, about 30 cm away"
                Phase.CAPTURING -> "Hold still"
                Phase.ANALYSING -> "Identifying on this phone…"
            }
            Text(
                label,
                style = WrType.Label.copy(color = Color.White),
                modifier = Modifier.clip(WrShape.pill).background(Color.Black.copy(alpha = 0.42f)).padding(horizontal = 14.dp, vertical = 8.dp)
            )
            if (AppState.useDeviceCamera && !granted) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.clip(WrShape.pill).background(Wr.Wheat).pressable { launcher.launch(Manifest.permission.CAMERA) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.NoPhotography, null, tint = Wr.Ink, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Demo feed · Allow camera", style = WrType.Label.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }

        // ---- shutter row
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                val last = AppState.scans.firstOrNull()
                Box(
                    Modifier.size(52.dp).clip(WrShape.sm).border(2.dp, Color.White.copy(alpha = 0.8f), WrShape.sm)
                        .background(Color.White.copy(alpha = 0.1f))
                        .pressable(enabled = last != null) { last?.let { nav.navigate(Routes.scanResult(it.id)) } },
                    contentAlignment = Alignment.Center
                ) {
                    if (last != null) LeafImage(last.leafSeed, Modifier.fillMaxSize(), last.captured)
                    else Icon(Icons.Rounded.PhotoCamera, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.weight(1f))
                Shutter(enabled = phase == Phase.LIVE) {
                    haptics.confirm()
                    scope.launch {
                        phase = Phase.CAPTURING
                        frozen = if (useCamera) preview?.bitmap else null
                        flash.snapTo(0.85f)
                        flash.animateTo(0f, tween(260))
                        phase = Phase.ANALYSING
                        delay(1250)
                        val outcome = AppState.nextScanOutcome()
                        val scan = AppState.recordScan(outcome, field.id, zone?.label, frozen)
                        if (outcome.abstain) haptics.reject() else haptics.confirm()
                        nav.navigate(Routes.scanResult(scan.id))
                        delay(400)
                        frozen = null
                        phase = Phase.LIVE
                    }
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconAction(Icons.Rounded.Inbox, "Review queue", { nav.navigate(Routes.QUEUE) }, tint = Color.White, background = Color.White.copy(alpha = 0.12f), size = 52.dp)
                    CountBadge(AppState.abstentionQueue.size, Modifier.align(Alignment.TopEnd), color = Wr.Wheat)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "On-device model · no network used · ${Fmt.plural(AppState.scans.size, "scan")} today",
                style = WrType.Caption.copy(color = Color.White.copy(alpha = 0.55f))
            )
        }

        Box(Modifier.fillMaxSize().graphicsLayer { alpha = flash.value }.background(Color.White))
    }

    if (picker) {
        Sheet({ picker = false }, "Where are you scanning?", "Scans are stored with this field and zone.") {
            Column(Modifier.padding(horizontal = 20.dp)) {
                ListCard {
                    AppState.fields.forEachIndexed { i, f ->
                        FieldListRow(f) {
                            AppState.activeFieldId = f.id
                            AppState.activeZoneId = null
                        }
                        if (i != AppState.fields.lastIndex) Hairline(inset = 86.dp)
                    }
                }
                val zones = AppState.zones(AppState.activeFieldId)
                if (zones.isNotEmpty()) {
                    SectionHeader("Zone in ${AppState.field(AppState.activeFieldId).name}")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZoneChip("Not in a zone", AppState.activeZoneId == null) { AppState.activeZoneId = null }
                        zones.forEach { z -> ZoneChip(z.label, AppState.activeZoneId == z.id) { AppState.activeZoneId = z.id } }
                    }
                }
                Spacer(Modifier.height(20.dp))
                PrimaryButton("Done", { picker = false })
            }
        }
    }
    if (help) {
        InfoSheet(
            "Getting a clean scan",
            listOf(
                "Hold the phone at arm's length, about 30 cm above one plant, with the sun behind you. The model looks for the ligule, auricle and leaf margin, which disappear if the plant is blurred or shaded.",
                "If the model is not confident it says so instead of guessing. Those scans go to the review queue with their photo and location, and an agronomist resolves them."
            ),
            onDismiss = { help = false },
            footer = "Model ${AppState.modelLeaf} · INT8 · runs on this phone"
        )
    }
}

@Composable
private fun ZoneChip(label: String, on: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) Wr.Forest else Wr.Ivory, label = "zc")
    Text(
        label,
        style = WrType.Label.copy(color = if (on) Color.White else Wr.Ink2),
        modifier = Modifier.clip(WrShape.pill).background(bg).border(1.dp, if (on) Wr.Forest else Wr.Line, WrShape.pill)
            .pressable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp)
    )
}

@Composable
private fun CameraFeed(modifier: Modifier, onPreview: (PreviewView) -> Unit, onCamera: (Camera) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            try {
                provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                provider?.unbindAll()
                provider?.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview)?.let(onCamera)
            } catch (e: Exception) {
                AppState.toast("Camera unavailable · using demo feed")
                AppState.useDeviceCamera = false
            }
        }, ContextCompat.getMainExecutor(ctx))
        onPreview(view)
        onDispose { provider?.unbindAll() }
    }
    AndroidView({ view }, modifier)
}

/** Simulated live feed: the frame breathes and drifts the way a hand-held phone does. */
@Composable
private fun DemoFeed(seed: Int, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "hand")
    val a by t.animateFloat(0f, 6.283f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "a")
    Box(modifier.graphicsLayer {
        scaleX = 1.1f + kotlin.math.sin(a * 2) * 0.012f
        scaleY = scaleX
        translationX = kotlin.math.sin(a) * 10f
        translationY = kotlin.math.cos(a * 1.3f) * 8f
        rotationZ = kotlin.math.sin(a * 0.7f) * 0.6f
    }) {
        LeafImage(seed, Modifier.fillMaxSize())
    }
}

@Composable
private fun Reticle(busy: Boolean, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "reticle")
    val breathe by t.animateFloat(0.97f, 1.0f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "b")
    val sweep by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse), label = "s")
    val color by animateColorAsState(if (busy) Color(0xFFB9DDA3) else Color.White, label = "rc")
    Canvas(modifier.graphicsLayer { scaleX = if (busy) 1f else breathe; scaleY = scaleX }) {
        val len = size.minDimension * 0.16f
        val w = 3.5.dp.toPx()
        val r = 18.dp.toPx()
        fun corner(x: Float, y: Float, sx: Float, sy: Float) {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(x, y + sy * len)
                lineTo(x, y + sy * r)
                quadraticTo(x, y, x + sx * r, y)
                lineTo(x + sx * len, y)
            }
            drawPath(p, color, style = Stroke(w, cap = StrokeCap.Round))
        }
        corner(0f, 0f, 1f, 1f)
        corner(size.width, 0f, -1f, 1f)
        corner(0f, size.height, 1f, -1f)
        corner(size.width, size.height, -1f, -1f)
        if (busy) {
            val y = size.height * sweep
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, color.copy(alpha = 0.28f)), y - 60f, y), Offset(0f, y - 60f), Size(size.width, 60f))
            drawLine(color, Offset(8f, y), Offset(size.width - 8f, y), 2.dp.toPx())
        }
    }
}

@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    val inner by animateFloatAsState(if (enabled) 1f else 0.72f, label = "shutter")
    Box(
        Modifier.size(80.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape)
            .pressable(enabled = enabled, scaleTo = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(64.dp).graphicsLayer { scaleX = inner; scaleY = inner }.clip(CircleShape).background(if (enabled) Color.White else Color.White.copy(alpha = 0.5f)))
    }
}

/* ================================================================== scan result */

@Composable
fun ScanResultScreen(nav: NavHostController, scanId: String) {
    val s = AppState.scan(scanId) ?: run { nav.popBackStack(); return }
    val entry = AppState.species.firstOrNull { it.latin == s.speciesLatin }
    val list = rememberLazyListState()
    val tone = when {
        s.abstained -> Tone.Wheat
        s.weedClass == WeedClass.CROP -> Tone.Moss
        s.weedClass == WeedClass.BROADLEAF -> Tone.Clay
        else -> Tone.Wheat
    }
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    StatusBarIcons(light = !scrolled)

    Box(Modifier.fillMaxSize().background(Wr.Cream)) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                item("hero") {
                    Box(
                        Modifier.fillMaxWidth().height(400.dp).graphicsLayer {
                            if (list.firstVisibleItemIndex == 0) translationY = list.firstVisibleItemScrollOffset * 0.4f
                        }
                    ) {
                        LeafImage(s.leafSeed, Modifier.fillMaxSize(), s.captured)
                        DetectionBox(s, Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x88000000), 0.3f to Color.Transparent)))
                    }
                }
                item("body") {
                    Column(
                        Modifier.fillMaxWidth().offset(y = (-28).dp).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(Wr.Cream).padding(horizontal = 20.dp, vertical = 22.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Row {
                                    if (s.abstained) StatusPill("Needs review", Tone.Wheat, icon = Icons.Rounded.QuestionMark)
                                    else StatusPill(s.weedClass.label, tone, dot = true)
                                    Spacer(Modifier.width(6.dp))
                                    StatusPill(if (s.synced) "Synced" else "Saved on phone", Tone.Neutral)
                                }
                                Spacer(Modifier.height(12.dp))
                                if (s.abstained) {
                                    Text("Not confident enough", style = WrType.DisplayM)
                                    Spacer(Modifier.height(4.dp))
                                    Text("Closest match ${s.speciesLatin}, below the calibrated threshold", style = WrType.BodyS)
                                } else {
                                    Text(s.speciesLatin, style = WrType.DisplayM.copy(fontStyle = FontStyle.Italic))
                                    Spacer(Modifier.height(4.dp))
                                    Text("${entry?.common ?: ""} · ${s.speciesLocal}", style = WrType.BodyS)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Ring(s.confidence, Modifier.size(72.dp), color = if (s.abstained) Wr.Wheat else Wr.Moss, stroke = 6.dp) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(Fmt.pct(s.confidence), style = WrType.TitleM.copy(fontFeatureSettings = "tnum"))
                                    Text("sure", style = WrType.Caption.copy(fontSize = 10.sp))
                                }
                            }
                        }

                        if (s.abstained) {
                            Spacer(Modifier.height(18.dp))
                            Notice(
                                if (s.resolved) "Annotated as ${s.annotation}" else "Sent to the review queue",
                                if (s.resolved) "An analyst confirms the label on the dashboard."
                                else "A wrong answer here means a wrong spray. The photo and location go to an agronomist instead.",
                                icon = if (s.resolved) Icons.Rounded.TaskAlt else Icons.Rounded.Inbox,
                                tone = if (s.resolved) Tone.Forest else Tone.Wheat
                            )
                        } else if (s.weedClass != WeedClass.CROP) {
                            Spacer(Modifier.height(18.dp))
                            Notice(
                                if (s.zoneLabel != null) "Confirms the flag in ${s.zoneLabel}" else "Not inside a flagged zone",
                                s.weedClass.chemistryHint,
                                icon = Icons.Rounded.CenterFocusStrong,
                                tone = if (s.zoneLabel != null) Tone.Forest else Tone.Slate
                            )
                        }

                        SectionHeader("Candidates")
                        Card(padding = PaddingValues(16.dp)) {
                            val all = listOf(s.speciesLatin to s.confidence) + s.runnerUp
                            all.forEachIndexed { i, (name, p) ->
                                if (i > 0) Spacer(Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, style = WrType.BodyS.copy(color = Wr.Ink, fontStyle = FontStyle.Italic), modifier = Modifier.weight(1f))
                                    Text(Fmt.pct(p), style = WrType.Label.copy(fontFeatureSettings = "tnum"))
                                }
                                Spacer(Modifier.height(6.dp))
                                Meter(p, color = if (i == 0) (if (s.abstained) Wr.Wheat else Wr.Moss) else Wr.LineStrong, height = 5.dp)
                            }
                        }

                        if (entry != null) {
                            SectionHeader("What to look for")
                            Card(padding = PaddingValues(16.dp)) {
                                Row {
                                    Icon(Icons.Rounded.Lightbulb, null, tint = Wr.Wheat, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text(entry.note, style = WrType.Body.copy(color = Wr.Ink2))
                                }
                            }
                        }

                        SectionHeader("Record")
                        ListCard {
                            KeyValueRow("Field", s.fieldName + (s.zoneLabel?.let { " · $it" } ?: ""))
                            Hairline(inset = 16.dp)
                            KeyValueRow("Captured", "${Fmt.relative(s.at)} · ${s.id}")
                            Hairline(inset = 16.dp)
                            KeyValueRow("Location", Fmt.coord(s.lat, s.lon), mono = true)
                            Hairline(inset = 16.dp)
                            KeyValueRow("GNSS accuracy", "±${"%.1f".format(s.gnssAccuracyM)} m")
                            Hairline(inset = 16.dp)
                            KeyValueRow("Quadrat frame", s.frameId ?: "None in shot")
                            Hairline(inset = 16.dp)
                            KeyValueRow("Model", s.modelVersion)
                            Hairline(inset = 16.dp)
                            KeyValueRow("Inference", "${s.inferenceMs} ms on this phone")
                        }
                        if (s.frameId != null) {
                            Spacer(Modifier.height(12.dp))
                            TonalButton("Open quadrat ${s.frameId}", { nav.navigate(Routes.QUADRAT) }, icon = Icons.Rounded.SquareFoot, tone = Tone.Neutral, fill = true)
                        }
                    }
                }
            }
            BottomActions {
                if (s.abstained) {
                    PrimaryButton("Rescan from another angle", { AppState.homeTab = 1; nav.popBackStack() }, icon = Icons.Rounded.Refresh)
                    SecondaryButton("Open review queue", { nav.navigate(Routes.QUEUE) }, icon = Icons.Rounded.Inbox)
                } else {
                    if (s.weedClass != WeedClass.CROP) PrimaryButton("Record treatment", { nav.navigate(Routes.treatment(s.fieldId)) }, icon = Icons.Rounded.EditNote)
                    SecondaryButton("Scan another plant", { AppState.homeTab = 1; nav.popBackStack() }, icon = Icons.Rounded.CenterFocusStrong)
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(if (scrolled) Wr.Cream else Color.Transparent).statusBarsPadding()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                MapButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", { nav.popBackStack() }, size = 42.dp)
                if (scrolled) {
                    Spacer(Modifier.width(12.dp))
                    Text(if (s.abstained) "Needs review" else s.speciesLatin, style = WrType.TitleM.copy(fontStyle = if (s.abstained) FontStyle.Normal else FontStyle.Italic))
                }
            }
            if (scrolled) Hairline()
        }
    }
}

@Composable
private fun DetectionBox(s: LeafScan, modifier: Modifier) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(250); reveal.animateTo(1f, tween(500)) }
    val color = if (s.abstained) Wr.Wheat else Color(0xFFB9DDA3)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val a = reveal.value
            val tl = Offset(size.width * 0.2f, size.height * 0.24f)
            val sz = Size(size.width * 0.6f, size.height * 0.6f)
            drawRoundRect(color.copy(alpha = 0.12f * a), tl, sz, CornerRadius(14.dp.toPx()))
            drawRoundRect(color.copy(alpha = a), tl, sz, CornerRadius(14.dp.toPx()), style = Stroke(2.5.dp.toPx()))
        }
        Text(
            if (s.abstained) "Uncertain · ${Fmt.pct(s.confidence)}" else "${s.speciesLatin} · ${Fmt.pct(s.confidence)}",
            style = WrType.Label.copy(color = Wr.Ink, fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .offset(x = maxWidth * 0.2f, y = maxHeight * 0.24f - 32.dp)
                .graphicsLayer { alpha = reveal.value }
                .clip(WrShape.xs).background(color).padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/* ================================================================== review queue */

@Composable
fun QueueScreen(nav: NavHostController) {
    val queue = AppState.abstentionQueue
    val resolved = AppState.scans.filter { it.resolved }
    DetailScreen(
        "Review queue",
        onBack = { nav.popBackStack() },
        subtitle = "Scans the model would not name. Annotate what you can see; the agronomist has the final word."
    ) {
        item {
            Card(Modifier.padding(top = 14.dp), padding = PaddingValues(vertical = 14.dp)) {
                Row(Modifier.padding(horizontal = 16.dp)) {
                    Stat("${queue.size}", "Waiting", Modifier.weight(1f), valueColor = if (queue.isNotEmpty()) Wr.WheatInk else Wr.Ink)
                    Stat("${resolved.size}", "Annotated", Modifier.weight(1f))
                    Stat("≤15%", "Target rate", Modifier.weight(1f))
                }
            }
        }
        if (queue.isEmpty()) {
            item {
                EmptyState(
                    Icons.Rounded.TaskAlt, "Nothing waiting",
                    "Scans land here when the model's confidence is below its threshold.",
                    Modifier.padding(top = 20.dp)
                ) { TonalButton("Open scanner", { AppState.homeTab = 1; nav.popBackStack(Routes.HOME, false) }, icon = Icons.Rounded.CenterFocusStrong) }
            }
        } else {
            item { SectionHeader("Waiting · ${queue.size}") }
            queue.forEach { s ->
                item(key = s.id) {
                    Card(Modifier.padding(bottom = 12.dp), padding = PaddingValues(0.dp)) {
                        Row(Modifier.rowPress { nav.navigate(Routes.scanResult(s.id)) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            LeafImage(s.leafSeed, Modifier.size(72.dp).clip(WrShape.sm), s.captured)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Scan ${s.id}", style = WrType.TitleS)
                                Text("${s.fieldName}${s.zoneLabel?.let { " · $it" } ?: ""}", style = WrType.BodyS)
                                Text("${Fmt.relative(s.at)} · ${Fmt.pct(s.confidence)} top match", style = WrType.Caption)
                            }
                        }
                        Hairline()
                        Column(Modifier.padding(14.dp)) {
                            Text("Looks like", style = WrType.Caption)
                            Spacer(Modifier.height(8.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                (listOf(s.speciesLatin) + s.runnerUp.map { it.first }).distinct().forEach { name ->
                                    Text(
                                        name,
                                        style = WrType.Label.copy(fontStyle = FontStyle.Italic),
                                        modifier = Modifier.clip(WrShape.pill).background(Wr.CreamDeep)
                                            .pressable {
                                                AppState.resolveAbstention(s.id, name)
                                                AppState.toast("Annotated as $name · sent to the agronomist")
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (resolved.isNotEmpty()) {
            item { SectionHeader("Annotated · ${resolved.size}") }
            item {
                ListCard {
                    resolved.forEachIndexed { i, s ->
                        Row(Modifier.rowPress { nav.navigate(Routes.scanResult(s.id)) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            LeafImage(s.leafSeed, Modifier.size(44.dp).clip(WrShape.xs), s.captured)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.annotation ?: "", style = WrType.TitleS.copy(fontStyle = FontStyle.Italic))
                                Text("${s.id} · ${Fmt.relative(s.at)}", style = WrType.Caption)
                            }
                            StatusPill("With analyst", Tone.Slate)
                        }
                        if (i != resolved.lastIndex) Hairline(inset = 68.dp)
                    }
                }
            }
        }
    }
}

/* ================================================================== quadrat records */

@Composable
fun QuadratScreen(nav: NavHostController) {
    DetailScreen(
        "Quadrat records",
        onBack = { nav.popBackStack() },
        subtitle = "Plant counts inside a painted 1 × 1 m frame, verified by the station agronomist."
    ) {
        AppState.quadrats.forEach { q ->
            item(key = q.id) {
                Card(Modifier.padding(top = 16.dp), padding = PaddingValues(0.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1.9f).clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))) {
                        LeafImage(q.seed, Modifier.fillMaxSize())
                        QuadratFrame(Modifier.fillMaxSize())
                        StatusPill(q.frameId, Tone.Neutral, Modifier.align(Alignment.TopStart).padding(12.dp), solid = false)
                    }
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${q.fieldName} · ${q.zoneLabel}", style = WrType.TitleM)
                                Text("${Fmt.date(q.recordedAt)} · ${q.verifiedBy}", style = WrType.Caption)
                            }
                            val total = q.speciesCounts.sumOf { it.second }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("$total", style = WrType.NumM)
                                Text("plants / m²", style = WrType.Caption)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        val max = q.speciesCounts.maxOf { it.second }.toFloat()
                        q.speciesCounts.forEach { (sp, n) ->
                            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(sp, style = WrType.BodyS.copy(color = Wr.Ink, fontStyle = FontStyle.Italic), modifier = Modifier.width(150.dp))
                                Meter(n / max, Modifier.weight(1f), color = Wr.Moss, height = 6.dp)
                                Text("$n", style = WrType.Label.copy(fontFeatureSettings = "tnum"), textAlign = TextAlign.End, modifier = Modifier.width(34.dp))
                            }
                        }
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            Text(
                "The frame is visible from the drone at 15 m and from the phone at 30 cm, which is what ties a scan to the same ground the survey saw. Counts are a label reference, never training input.",
                style = WrType.Caption
            )
        }
    }
}

@Composable
private fun QuadratFrame(modifier: Modifier) {
    Canvas(modifier) {
        val s = size.height * 0.78f
        val l = (size.width - s) / 2
        val t = (size.height - s) / 2
        drawRect(Color.White.copy(alpha = 0.92f), Offset(l, t), Size(s, s), style = Stroke(6.dp.toPx()))
        // ArUco markers at the corners
        listOf(Offset(l, t), Offset(l + s, t), Offset(l, t + s), Offset(l + s, t + s)).forEachIndexed { i, c ->
            val m = 18.dp.toPx()
            drawRect(Color.Black, c - Offset(m / 2, m / 2), Size(m, m))
            drawRect(Color.White, c - Offset(m / 2 - 3f, m / 2 - 3f), Size(m / 3, m / 3))
            drawRect(Color.White, c + Offset((i % 2) * 3f, 2f), Size(m / 4, m / 4))
        }
    }
}
