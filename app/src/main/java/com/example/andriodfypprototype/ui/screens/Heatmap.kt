package com.example.andriodfypprototype.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LayersClear
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.example.andriodfypprototype.data.AppState
import com.example.andriodfypprototype.data.Demo
import com.example.andriodfypprototype.data.Fmt
import com.example.andriodfypprototype.data.GridCell
import com.example.andriodfypprototype.data.GridSize
import com.example.andriodfypprototype.data.SurveyRole
import com.example.andriodfypprototype.ui.Routes
import com.example.andriodfypprototype.ui.StatusBarIcons
import com.example.andriodfypprototype.ui.components.Card
import com.example.andriodfypprototype.ui.components.Elevated
import com.example.andriodfypprototype.ui.components.Hairline
import com.example.andriodfypprototype.ui.components.IconAction
import com.example.andriodfypprototype.ui.components.InfoSheet
import com.example.andriodfypprototype.ui.components.KeyValueRow
import com.example.andriodfypprototype.ui.components.LegendSwatch
import com.example.andriodfypprototype.ui.components.MapButton
import com.example.andriodfypprototype.ui.components.Meter
import com.example.andriodfypprototype.ui.components.Notice
import com.example.andriodfypprototype.ui.components.PrimaryButton
import com.example.andriodfypprototype.ui.components.SectionHeader
import com.example.andriodfypprototype.ui.components.Segmented
import com.example.andriodfypprototype.ui.components.Stat
import com.example.andriodfypprototype.ui.components.StatusPill
import com.example.andriodfypprototype.ui.components.Tone
import com.example.andriodfypprototype.ui.components.rememberHaptics
import com.example.andriodfypprototype.ui.map.Aerial
import com.example.andriodfypprototype.ui.map.MapSurface
import com.example.andriodfypprototype.ui.map.ScaleBar
import com.example.andriodfypprototype.ui.map.ZonePin
import com.example.andriodfypprototype.ui.map.dimOutside
import com.example.andriodfypprototype.ui.map.fieldOutline
import com.example.andriodfypprototype.ui.map.fitBounds
import com.example.andriodfypprototype.ui.map.heatLayer
import com.example.andriodfypprototype.ui.map.imageryFor
import com.example.andriodfypprototype.ui.map.mapAnchor
import com.example.andriodfypprototype.ui.map.prescriptionOutline
import com.example.andriodfypprototype.ui.map.rememberMapCamera
import com.example.andriodfypprototype.ui.theme.Wr
import com.example.andriodfypprototype.ui.theme.WrShape
import com.example.andriodfypprototype.ui.theme.WrType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeatmapScreen(nav: NavHostController, fieldId: String) {
    val f = AppState.field(fieldId)
    val zones = AppState.zones(fieldId)
    var size by remember { mutableStateOf(AppState.gridSize) }
    val grid = remember(size) { AppState.grid(fieldId, size) }
    val heat = remember(grid) { Aerial.heatBitmap(grid) }
    val edges = remember(grid) { Aerial.prescriptionEdges(grid) }
    var selected by remember { mutableStateOf<GridCell?>(null) }
    var layers by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf<String?>(null) }
    val camera = rememberMapCamera()
    val sheet = rememberBottomSheetScaffoldState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val pre = AppState.surveysOf(fieldId).firstOrNull { it.role == SurveyRole.PRE }
    StatusBarIcons(light = true)

    val peek = 212.dp
    MapSheetScaffold(
        state = sheet,
        peek = peek + with(density) { WindowInsets.navigationBars.getBottom(density).toDp() },
        sheet = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Stat(Fmt.sqm(grid.treatedAreaSqm), "To spray", Modifier.weight(1f))
                        Stat(Fmt.pct(grid.treatedAreaFraction), "Of field", Modifier.weight(0.7f))
                        Stat("${zones.size}", "Zones", Modifier.weight(0.6f))
                        Stat("${grid.abstained}", "Abstained", Modifier.weight(0.8f), valueColor = if (grid.abstained > 0) Wr.WheatInk else Wr.Ink)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Segmented(
                            GridSize.entries.map { it.label }, size.ordinal,
                            { size = GridSize.entries[it]; selected = null },
                            Modifier.weight(1f), height = 38.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        IconAction(Icons.Rounded.HelpOutline, "About spray grids", { info = "grid" }, tint = Wr.Ink2)
                    }
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton(
                        "Create spray route", {
                            AppState.gridSize = size
                            AppState.routeAllZones(fieldId)
                            nav.navigate(Routes.route(fieldId))
                        },
                        trailingIcon = Icons.AutoMirrored.Rounded.ArrowForward, height = 52.dp, enabled = zones.isNotEmpty()
                    )
                }
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
                ) {
                    SectionHeader("Legend")
                    Card(padding = PaddingValues(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            LegendSwatch(Wr.HeatMid, "10–30% cover")
                            LegendSwatch(Wr.HeatHigh, "Over 30%")
                            LegendSwatch(Wr.HeatAbstain, "Abstained")
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "White outline: cells in the prescription. A cell is sprayed if any part of it crosses the threshold.",
                            style = WrType.Caption
                        )
                    }

                    SectionHeader("Grid resolution", info = { info = "grid" })
                    Card(padding = PaddingValues(16.dp)) {
                        GridSize.entries.forEach { g ->
                            val frac = AppState.grid(fieldId, g).treatedAreaFraction
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(g.label, style = WrType.Label.copy(fontWeight = if (g == size) FontWeight.SemiBold else FontWeight.Medium), modifier = Modifier.width(40.dp))
                                Meter(frac * 3f, Modifier.weight(1f), color = if (g == size) Wr.Forest else Wr.LineStrong)
                                Spacer(Modifier.width(12.dp))
                                Text(Fmt.pct(frac), style = WrType.Label.copy(fontFeatureSettings = "tnum"), modifier = Modifier.width(38.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(size.actuator, style = WrType.Caption)
                    }

                    SectionHeader("Prescription")
                    Card(padding = PaddingValues(0.dp)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Threshold ${AppState.prescriptionThresholdPct.toInt()}% cover", style = WrType.TitleS)
                                Text("Set by the agronomist on the dashboard", style = WrType.Caption)
                            }
                            StatusPill("Read-only", Tone.Neutral, icon = Icons.Rounded.Lock)
                        }
                        Hairline()
                        KeyValueRow("Survey", pre?.let { "${it.role.label} · ${Fmt.date(it.flownAt)}" } ?: "—")
                        KeyValueRow("Ground sample distance", pre?.let { "${it.gsdCm} cm / px" } ?: "—")
                        KeyValueRow("Model", AppState.modelAerial, mono = true)
                    }

                    if (grid.abstained > 0) {
                        Spacer(Modifier.height(16.dp))
                        Notice(
                            "${Fmt.plural(grid.abstained, "cell")} need a person to look",
                            "The model was not confident enough to call these. Walk past them and scan a plant.",
                            tone = Tone.Wheat,
                            action = "Open review queue", onAction = { nav.navigate(Routes.QUEUE) }
                        )
                    }
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
                fitPadding = 20.dp,
                insetTop = 76.dp,
                insetBottom = peek,
                onTap = { pt ->
                    val c = grid.atMeters(pt.x, pt.y)
                    selected = if (c != null && c.inside && c != selected) { haptics.tick(); c } else null
                },
                overlay = { c ->
                    dimOutside(c, f.boundary, 0.3f)
                    if (layers) {
                        heatLayer(c, heat, grid, f.boundary)
                        prescriptionOutline(c, edges)
                    }
                    fieldOutline(c, f.boundary)
                    selected?.let { cell ->
                        val tl = c.project(grid.originX + cell.col * grid.cellMeters, grid.originY + cell.row * grid.cellMeters)
                        val s = grid.cellMeters * c.zoom
                        val pad = 3.dp.toPx()
                        drawRect(Color.Black.copy(alpha = 0.4f), tl - Offset(pad, pad), Size(s + pad * 2, s + pad * 2), style = Stroke(5.dp.toPx()))
                        drawRect(Color.White, tl - Offset(pad, pad), Size(s + pad * 2, s + pad * 2), style = Stroke(2.5.dp.toPx()))
                    }
                },
                markers = {
                    if (layers) zones.forEach { z -> ZonePin(z, Modifier.mapAnchor(camera, z.center), size = 24.dp) }
                }
            )

            MapTopBar(
                "Weed map · ${f.name}",
                pre?.let { "Pre-treatment flight · ${Fmt.dayMonth(it.flownAt)}" },
                onBack = { nav.popBackStack() }
            ) {
                MapButton(if (layers) Icons.Rounded.Layers else Icons.Rounded.LayersClear, "Toggle weed layer", { layers = !layers }, active = !layers)
            }

            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = peek + 14.dp),
                horizontalAlignment = Alignment.End
            ) {
                MapButton(Icons.Rounded.CropFree, "Fit field", {
                    scope.launch { camera.animateFit(f.fitBounds(), with(density) { 20.dp.toPx() }); camera.userMoved = false }
                })
            }
            ScaleBar(camera, Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = peek + 18.dp), dark = true)

            AnimatedVisibility(
                selected != null,
                modifier = Modifier.statusBarsPadding().padding(top = 70.dp, start = 12.dp, end = 12.dp),
                enter = fadeIn() + slideInVertically { -it / 3 },
                exit = fadeOut() + slideOutVertically { -it / 3 }
            ) {
                val cell = selected ?: return@AnimatedVisibility
                CellCallout(cell, size) { selected = null }
            }
        }
    }

    when (info) {
        "grid" -> InfoSheet(
            "Spray grid",
            listOf(
                "The map is cut into square cells because that is what a sprayer can act on. 1 m suits a knapsack operator following this phone; 2 m matches boom section control; 5 m absorbs the 3 to 5 m drift of a phone's GNSS.",
                "A cell is sprayed if any part of it is infested, so a coarser grid always sprays more area. Both numbers are reported so the trade is a stated result, not a hidden one."
            ),
            onDismiss = { info = null }
        )
    }
}

@Composable
private fun CellCallout(cell: GridCell, size: GridSize, onClose: () -> Unit) {
    Elevated(Modifier.fillMaxWidth(), shape = WrShape.lg) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(WrShape.sm).background(
                        when {
                            cell.abstained -> Wr.HeatAbstain
                            cell.infestPct < 10f -> Wr.Sage
                            else -> cell.severity.fill
                        }
                    ),
                    contentAlignment = Alignment.Center
                ) { Text(cell.ref, style = WrType.Label.copy(fontWeight = FontWeight.Bold, color = if (cell.infestPct > 30f && !cell.abstained) Color.White else Wr.Ink)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Cell ${cell.ref}", style = WrType.TitleM)
                    Text("${size.label} × ${size.label} · ${if (cell.treated) "in prescription" else "not sprayed"}", style = WrType.Caption)
                }
                when {
                    cell.abstained -> StatusPill("Abstained", Tone.Wheat)
                    cell.infestPct < 4f -> StatusPill("Crop", Tone.Moss)
                    else -> StatusPill(cell.severity.label, when (cell.severity.name) { "HEAVY" -> Tone.Clay; "MODERATE" -> Tone.Wheat; else -> Tone.Moss })
                }
                Spacer(Modifier.width(4.dp))
                IconAction(Icons.Rounded.Close, "Close", onClose, size = 36.dp, tint = Wr.Ink3)
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Stat("${cell.infestPct.toInt()}%", "Weed cover", Modifier.weight(1f))
                Stat(if (cell.infestPct < 4f) "—" else cell.weedClass.short, "Class", Modifier.weight(1f))
                Stat(Fmt.pct(cell.confidence), "Confidence", Modifier.weight(1f), valueColor = if (cell.abstained) Wr.WheatInk else Wr.Ink)
            }
            if (cell.infestPct >= 4f) {
                Spacer(Modifier.height(10.dp))
                Text(if (cell.abstained) "Send a person to look before spraying this cell." else cell.weedClass.chemistryHint, style = WrType.Caption)
            }
        }
    }
}
