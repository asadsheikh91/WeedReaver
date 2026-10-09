package com.example.andriodfypprototype.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.SquareFoot
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.example.andriodfypprototype.data.AppState
import com.example.andriodfypprototype.data.Demo
import com.example.andriodfypprototype.data.Fmt
import com.example.andriodfypprototype.data.Severity
import com.example.andriodfypprototype.data.SurveyRole
import com.example.andriodfypprototype.data.SurveyStatus
import com.example.andriodfypprototype.data.TreatmentZone
import com.example.andriodfypprototype.data.ZoneState
import com.example.andriodfypprototype.ui.Routes
import com.example.andriodfypprototype.ui.StatusBarIcons
import com.example.andriodfypprototype.ui.components.BottomActions
import com.example.andriodfypprototype.ui.components.Card
import com.example.andriodfypprototype.ui.components.EmptyState
import com.example.andriodfypprototype.ui.components.Hairline
import com.example.andriodfypprototype.ui.components.IconAction
import com.example.andriodfypprototype.ui.components.ListCard
import com.example.andriodfypprototype.ui.components.ListRow
import com.example.andriodfypprototype.ui.components.MapButton
import com.example.andriodfypprototype.ui.components.PrimaryButton
import com.example.andriodfypprototype.ui.components.SectionHeader
import com.example.andriodfypprototype.ui.components.Stat
import com.example.andriodfypprototype.ui.components.StatusPill
import com.example.andriodfypprototype.ui.components.Tone
import com.example.andriodfypprototype.ui.components.pressable
import com.example.andriodfypprototype.ui.components.rowPress
import com.example.andriodfypprototype.ui.map.FieldMap
import com.example.andriodfypprototype.ui.map.rememberPulse
import com.example.andriodfypprototype.ui.theme.Wr
import com.example.andriodfypprototype.ui.theme.WrShape
import com.example.andriodfypprototype.ui.theme.WrType

@Composable
fun FieldDetailScreen(nav: NavHostController, fieldId: String) {
    val f = AppState.field(fieldId)
    val season = AppState.seasonOf(fieldId)
    val zones = AppState.zones(fieldId)
    val surveyed = AppState.hasSurvey(fieldId)
    val done = zones.count { it.state.done }
    val recorded = AppState.treatments.any { it.fieldId == fieldId }
    val verified = AppState.verifications.containsKey(fieldId)
    val list = rememberLazyListState()
    val heroPx = with(androidx.compose.ui.platform.LocalDensity.current) { 300.dp.toPx() }
    val solid by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > heroPx * 0.72f } }
    val barBg by animateColorAsState(if (solid) Wr.Cream else Color.Transparent, label = "bar")
    StatusBarIcons(light = !solid)

    Box(Modifier.fillMaxSize().background(Wr.Cream)) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "hero") {
                    Box(
                        Modifier.fillMaxWidth().height(300.dp).clip(androidx.compose.ui.graphics.RectangleShape)
                            .graphicsLayer {
                                if (list.firstVisibleItemIndex == 0) translationY = list.firstVisibleItemScrollOffset * 0.45f
                            }
                            .pressable(scaleTo = 1f) { if (surveyed) nav.navigate(Routes.heatmap(fieldId)) }
                    ) {
                        FieldMap(f, Modifier.fillMaxSize(), zones = zones, padding = 34.dp, insetTop = 40.dp, pinSize = 24.dp)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x77000000), 0.3f to Color.Transparent, 0.8f to Color.Transparent, 1f to Color(0x33000000))))
                        if (surveyed) {
                            Row(
                                Modifier.align(Alignment.BottomEnd).padding(14.dp).clip(WrShape.pill).background(Wr.Ivory)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Layers, null, tint = Wr.Ink, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Weed map", style = WrType.Label.copy(fontWeight = FontWeight.SemiBold))
                            }
                        }
                    }
                }
                item(key = "title") {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.id, style = WrType.Overline)
                            Spacer(Modifier.width(8.dp))
                            FieldStatus(f)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(f.name, style = WrType.DisplayL)
                        Spacer(Modifier.height(4.dp))
                        Text("${f.village} · ${f.captureMethod.lowercase().replaceFirstChar { it.uppercase() }} boundary", style = WrType.BodyS)
                    }
                }
                item(key = "facts") {
                    Card(Modifier.padding(horizontal = 20.dp).padding(top = 18.dp), padding = PaddingValues(vertical = 14.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Stat(Fmt.acres(f.areaAcres), Fmt.areaAlt(f.areaAcres, true), Modifier.weight(1f).padding(start = 16.dp), unit = "ac")
                            Stat(
                                season?.let { "${Fmt.daysBetween(it.sowingDate, Demo.now())}" } ?: "—",
                                "Days since sowing", Modifier.weight(1f), unit = "d"
                            )
                        }
                        Hairline(Modifier.padding(vertical = 12.dp))
                        Row(Modifier.fillMaxWidth()) {
                            Stat(season?.variety ?: "—", "Wheat variety", Modifier.weight(1f).padding(start = 16.dp))
                            Stat(Fmt.meters(f.perimeterM), "Perimeter", Modifier.weight(1f))
                        }
                    }
                }

                item(key = "loop-h") { SectionHeader("Treatment loop", Modifier.padding(horizontal = 20.dp)) }
                item(key = "loop") {
                    val pre = AppState.surveysOf(fieldId).firstOrNull { it.role == SurveyRole.PRE }
                    val follow = AppState.surveysOf(fieldId).firstOrNull { it.role == SurveyRole.PLUS_14D }
                    val routed = zones.any { it.state != ZoneState.FLAGGED }
                    val steps = listOf(
                        LoopStep(
                            "Survey", Icons.Rounded.FlightTakeoff,
                            when {
                                surveyed && pre != null -> "Flown ${Fmt.dayMonth(pre.flownAt)} · ${pre.images} images · ${Fmt.plural(zones.size, "zone")} flagged"
                                pre != null -> "Flight scheduled ${Fmt.inDays(pre.flownAt)}"
                                else -> "Not scheduled"
                            },
                            surveyed
                        ) { nav.navigate(Routes.surveys(fieldId)) },
                        LoopStep("Route", Icons.Rounded.Route, if (routed) "Nearest-first route from the gate" else "Plan a walking route through the zones", routed) {
                            if (zones.isNotEmpty()) { AppState.routeAllZones(fieldId); nav.navigate(Routes.route(fieldId)) }
                        },
                        LoopStep("Treat", Icons.Rounded.WaterDrop, "$done of ${zones.size} zones marked treated", zones.isNotEmpty() && done == zones.size) {
                            if (zones.isNotEmpty()) nav.navigate(Routes.route(fieldId))
                        },
                        LoopStep(
                            "Record", Icons.Rounded.EditNote,
                            AppState.treatments.firstOrNull { it.fieldId == fieldId }?.let { "${it.product} · ${Fmt.relative(it.appliedAt)}" }
                                ?: "Product, HRAC group and dose applied", recorded
                        ) { nav.navigate(Routes.treatment(fieldId)) },
                        LoopStep(
                            "Verify", Icons.Rounded.Verified,
                            when {
                                verified -> "Per-zone efficacy saved"
                                follow?.status == SurveyStatus.READY -> "+14 d survey ready to compare"
                                follow != null -> "+14 d survey ${follow.status.label.lowercase()}"
                                else -> "After the +14 d follow-up flight"
                            },
                            verified
                        ) { nav.navigate(Routes.verify(fieldId)) }
                    )
                    val current = steps.indexOfFirst { !it.done }
                    ListCard(Modifier.padding(horizontal = 20.dp)) {
                        steps.forEachIndexed { i, s -> LoopRow(s, i, i == current, i == steps.lastIndex) }
                    }
                }

                item(key = "zones-h") {
                    SectionHeader(
                        if (zones.isEmpty()) "Zones" else "Zones · ${zones.size}", Modifier.padding(horizontal = 20.dp),
                        action = if (surveyed) "Map" else null, onAction = { nav.navigate(Routes.heatmap(fieldId)) }
                    )
                }
                item(key = "zones") {
                    ListCard(Modifier.padding(horizontal = 20.dp)) {
                        if (zones.isEmpty()) {
                            EmptyState(
                                Icons.Rounded.FlightTakeoff,
                                if (surveyed) "No weed pressure" else "Waiting for the survey",
                                if (surveyed) "Nothing crossed the prescription threshold on this flight."
                                else "Zones appear once the flight is processed on the dashboard and synced to this phone."
                            )
                        } else zones.forEachIndexed { i, z ->
                            ZoneRow(z) { nav.navigate(Routes.navigate(fieldId, z.id)) }
                            if (i != zones.lastIndex) Hairline(inset = 70.dp)
                        }
                    }
                }

                item(key = "records-h") { SectionHeader("Records", Modifier.padding(horizontal = 20.dp)) }
                item(key = "records") {
                    ListCard(Modifier.padding(horizontal = 20.dp)) {
                        ListRow("Weed map", "Spray grid at ${AppState.gridSize.label}", icon = Icons.Rounded.GridOn, tone = Tone.Neutral, onClick = if (surveyed) ({ nav.navigate(Routes.heatmap(fieldId)) }) else null)
                        Hairline(inset = 70.dp)
                        ListRow("Flights and provenance", "${AppState.surveysOf(fieldId).size} flights this season", icon = Icons.Rounded.FlightTakeoff, tone = Tone.Neutral) { nav.navigate(Routes.surveys(fieldId)) }
                        Hairline(inset = 70.dp)
                        ListRow("Rotation history", "Herbicide groups applied across seasons", icon = Icons.Rounded.History, tone = Tone.Neutral) {
                            AppState.activeFieldId = fieldId
                            AppState.draft = null
                            nav.navigate(Routes.ROTATION)
                        }
                        Hairline(inset = 70.dp)
                        ListRow("Quadrat records", "Agronomist ground counts", icon = Icons.Rounded.SquareFoot, tone = Tone.Neutral) { nav.navigate(Routes.QUADRAT) }
                    }
                }
            }

            val left = zones.filter { !it.state.done }
            if (zones.isNotEmpty()) BottomActions {
                when {
                    left.isNotEmpty() -> PrimaryButton(
                        if (done == 0) "Start spray route" else "Continue route · ${left.size} left",
                        { AppState.routeAllZones(fieldId); nav.navigate(Routes.route(fieldId)) },
                        trailingIcon = Icons.AutoMirrored.Rounded.ArrowForward
                    )
                    !recorded -> PrimaryButton("Record treatment", { nav.navigate(Routes.treatment(fieldId)) }, icon = Icons.Rounded.EditNote)
                    !verified -> PrimaryButton("Verify treatment", { nav.navigate(Routes.verify(fieldId)) }, icon = Icons.Rounded.Verified)
                    else -> PrimaryButton("View verification", { nav.navigate(Routes.verify(fieldId)) }, icon = Icons.Rounded.Verified)
                }
            }
        }

        // floating bar that turns solid once the hero has scrolled away
        Column(Modifier.fillMaxWidth().background(barBg).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (solid) IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", { nav.popBackStack() })
                else MapButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", { nav.popBackStack() }, size = 42.dp)
                Spacer(Modifier.width(8.dp))
                if (solid) Text(f.name, style = WrType.TitleM, modifier = Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            }
            if (solid) Hairline()
        }
    }
}

private data class LoopStep(val title: String, val icon: ImageVector, val detail: String, val done: Boolean, val go: () -> Unit)

@Composable
private fun LoopRow(s: LoopStep, index: Int, current: Boolean, last: Boolean) {
    val pulse = if (current) rememberPulse(2000) else 0f
    Row(Modifier.fillMaxWidth().rowPress(onClick = s.go).padding(horizontal = 16.dp)) {
        Column(Modifier.width(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(2.dp).height(14.dp).background(if (index == 0) Color.Transparent else Wr.Line))
            Box(contentAlignment = Alignment.Center) {
                if (current) Box(Modifier.size(30.dp).graphicsLayer { scaleX = 0.7f + pulse * 0.5f; scaleY = 0.7f + pulse * 0.5f; alpha = 1f - pulse }.clip(CircleShape).background(Wr.Sage))
                Box(
                    Modifier.size(26.dp).clip(CircleShape)
                        .background(if (s.done) Wr.Forest else if (current) Wr.Ivory else Wr.Cream)
                        .border(2.dp, if (s.done || current) Wr.Forest else Wr.LineStrong, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (s.done) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    else Text("${index + 1}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (current) Wr.Forest else Wr.Ink3)
                }
            }
            Box(Modifier.width(2.dp).height(22.dp).background(if (last) Color.Transparent else Wr.Line))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(top = 14.dp, bottom = 12.dp)) {
            Text(s.title, style = WrType.TitleS.copy(color = if (s.done || current) Wr.Ink else Wr.Ink2))
            Spacer(Modifier.height(2.dp))
            Text(s.detail, style = WrType.BodyS)
        }
        if (current) {
            Box(Modifier.align(Alignment.CenterVertically)) { StatusPill("Next", Tone.Forest) }
        }
    }
}

@Composable
fun ZoneBadge(z: TreatmentZone, size: androidx.compose.ui.unit.Dp = 38.dp) {
    val done = z.state.done
    Box(
        Modifier.size(size).clip(CircleShape).background(if (done) Wr.Sage else z.severity.bg)
            .border(1.5.dp, if (done) Wr.Moss else z.severity.fill, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (done) Icon(Icons.Rounded.Check, null, tint = Wr.Forest, modifier = Modifier.size(size * 0.5f))
        else Text(z.letter, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold, color = z.severity.ink)
    }
}

@Composable
fun ZoneRow(z: TreatmentZone, onClick: () -> Unit) {
    ListRow(
        title = z.label,
        subtitle = "${z.dominantClass.short} · ${Fmt.sqm(z.areaSqm)} · ${z.meanInfestPct.toInt()}% cover",
        leading = { ZoneBadge(z) },
        trailing = {
            when (z.state) {
                ZoneState.RESURVEYED -> {
                    val pct = z.efficacyPct ?: 0
                    StatusPill("$pct% control", if (pct >= 70) Tone.Forest else Tone.Clay)
                }
                ZoneState.TREATED -> StatusPill("Treated", Tone.Forest)
                else -> StatusPill(z.severity.label, when (z.severity) { Severity.HEAVY -> Tone.Clay; Severity.MODERATE -> Tone.Wheat; else -> Tone.Moss })
            }
        },
        chevron = false,
        onClick = onClick
    )
}
