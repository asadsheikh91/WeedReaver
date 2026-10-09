package com.example.andriodfypprototype.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Grass
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.example.andriodfypprototype.data.AppState
import com.example.andriodfypprototype.data.Demo
import com.example.andriodfypprototype.data.FieldParcel
import com.example.andriodfypprototype.data.Fmt
import com.example.andriodfypprototype.data.LogEntry
import com.example.andriodfypprototype.data.LogKind
import com.example.andriodfypprototype.data.Severity
import com.example.andriodfypprototype.data.SurveyStatus
import com.example.andriodfypprototype.ui.Routes
import com.example.andriodfypprototype.ui.components.Card
import com.example.andriodfypprototype.ui.components.Hairline
import com.example.andriodfypprototype.ui.components.IconAction
import com.example.andriodfypprototype.ui.components.IconTile
import com.example.andriodfypprototype.ui.components.InfoSheet
import com.example.andriodfypprototype.ui.components.ListCard
import com.example.andriodfypprototype.ui.components.ListRow
import com.example.andriodfypprototype.ui.components.Meter
import com.example.andriodfypprototype.ui.components.PrimaryButton
import com.example.andriodfypprototype.ui.components.SectionHeader
import com.example.andriodfypprototype.ui.components.StatusPill
import com.example.andriodfypprototype.ui.components.TabHeader
import com.example.andriodfypprototype.ui.components.Tone
import com.example.andriodfypprototype.ui.components.enter
import com.example.andriodfypprototype.ui.components.rowPress
import com.example.andriodfypprototype.ui.map.FieldMap
import com.example.andriodfypprototype.ui.map.FieldThumbnail
import com.example.andriodfypprototype.ui.theme.Wr
import com.example.andriodfypprototype.ui.theme.WrShape
import com.example.andriodfypprototype.ui.theme.WrType
import java.util.Calendar

@Composable
fun FieldsTab(nav: NavHostController) {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when {
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }
    val first = AppState.operatorName.substringBefore(" ")
    var spraySheet by remember { mutableStateOf(false) }
    var treatment by remember { mutableStateOf<com.example.andriodfypprototype.data.TreatmentRecord?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            TabHeader(
                title = if (AppState.trainee) greeting else "$greeting, $first",
                subtitle = "${Fmt.weekday(Demo.now())} · ${AppState.seasonLabel}"
            ) { SyncStatusButton(nav) }
        }
        item { ConditionsCard(Modifier.padding(horizontal = 20.dp).padding(top = 10.dp).enter(0)) { spraySheet = true } }
        item { UpNextCard(nav, Modifier.padding(horizontal = 20.dp).padding(top = 14.dp).enter(1)) }

        item {
            SectionHeader(
                "Your fields", Modifier.padding(horizontal = 20.dp),
                action = "Add field", onAction = { nav.navigate(Routes.ADD_FIELD) }
            )
        }
        item {
            ListCard(Modifier.padding(horizontal = 20.dp).enter(2)) {
                AppState.fields.forEachIndexed { i, f ->
                    FieldListRow(f) {
                        AppState.activeFieldId = f.id
                        nav.navigate(Routes.field(f.id))
                    }
                    if (i != AppState.fields.lastIndex) Hairline(inset = 86.dp)
                }
            }
        }

        item {
            SectionHeader(
                "Recent activity", Modifier.padding(horizontal = 20.dp),
                action = "See all", onAction = { AppState.homeTab = 2 }
            )
        }
        item {
            val entries = AppState.timeline().take(3)
            ListCard(Modifier.padding(horizontal = 20.dp).enter(3)) {
                entries.forEachIndexed { i, e ->
                    ActivityRow(e) {
                        if (e.kind == LogKind.TREATMENT) treatment = (AppState.treatments + AppState.priorTreatments).firstOrNull { it.id == e.refId }
                        else openLogEntry(nav, e)
                    }
                    if (i != entries.lastIndex) Hairline(inset = 70.dp)
                }
            }
        }
    }

    treatment?.let { TreatmentSheet(it) { treatment = null } }
    if (spraySheet) {
        InfoSheet(
            "Spray window",
            listOf(
                "Wind ${Demo.conditions.windKmh} km/h from the ${Demo.conditions.windFrom}, gusting ${Demo.conditions.gustKmh}. " +
                    "Below 15 km/h drift onto the neighbouring berseem and mustard is low.",
                "Delta T ${Demo.conditions.deltaT} sits inside the 2 to 8 range where droplets neither evaporate before " +
                    "landing nor stay on the leaf long enough to run off.",
                "No rain is forecast for ${Demo.conditions.rainFreeHours} hours, longer than the rain-fast period on the " +
                    "labels of the products registered for this crop."
            ),
            onDismiss = { spraySheet = false },
            footer = "Forecast cached at ${Fmt.time(Demo.conditions.updated)} before leaving signal."
        )
    }
}

@Composable
fun SyncStatusButton(nav: NavHostController) {
    val pending = AppState.pendingSync
    IconAction(
        icon = when {
            pending > 0 -> Icons.Rounded.CloudUpload
            AppState.offline -> Icons.Rounded.CloudOff
            else -> Icons.Rounded.CloudDone
        },
        contentDescription = "Sync status",
        onClick = { nav.navigate(Routes.SYNC) },
        tint = if (pending > 0) Wr.WheatInk else Wr.Ink2,
        background = Wr.Ivory,
        border = true,
        badge = pending
    )
}

@Composable
private fun ConditionsCard(modifier: Modifier = Modifier, onInfo: () -> Unit) {
    val c = Demo.conditions
    Card(modifier, padding = PaddingValues(0.dp), onClick = onInfo) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.WbSunny, Tone.Wheat, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Good spray window", style = WrType.TitleS)
                Text("Until 13:00 · forecast from ${Fmt.time(c.updated)}", style = WrType.Caption)
            }
            StatusPill("Spray", Tone.Moss, dot = true)
        }
        Hairline()
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
            Condition(Icons.Rounded.Air, "${c.windKmh} km/h", "Wind ${c.windFrom}", Modifier.weight(1f))
            Condition(Icons.Rounded.Thermostat, "${c.tempC}°", "Temp", Modifier.weight(1f))
            Condition(Icons.Rounded.WaterDrop, "${c.deltaT}", "Delta T", Modifier.weight(1f))
            Condition(Icons.Rounded.Umbrella, "${c.rainFreeHours} h", "Rain-free", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Condition(icon: ImageVector, value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Wr.Ink3, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text(value, style = WrType.TitleS.copy(fontFeatureSettings = "tnum"))
        }
        Text(label, style = WrType.Caption)
    }
}

/** The one thing the operator should do next, derived from where each field is in the loop. */
@Composable
private fun UpNextCard(nav: NavHostController, modifier: Modifier = Modifier) {
    val surveyed = AppState.fields.filter { AppState.hasSurvey(it.id) }
    val focus = surveyed.maxByOrNull { f ->
        val z = AppState.zones(f.id)
        z.count { !it.state.done } * 10 + z.count { it.severity == Severity.HEAVY && !it.state.done } * 5
    } ?: return
    val zones = AppState.zones(focus.id)
    val done = zones.count { it.state.done }
    val left = zones.filter { !it.state.done }
    val recorded = AppState.treatments.any { it.fieldId == focus.id }
    val verified = AppState.verifications.containsKey(focus.id)
    val areaLeft = left.sumOf { it.areaSqm }

    data class Step(val eyebrow: String, val title: String, val body: String, val cta: String, val icon: ImageVector, val go: () -> Unit)

    val step = when {
        left.isNotEmpty() && done == 0 -> Step(
            "Ready to spray", focus.name,
            "${Fmt.plural(left.size, "zone")} flagged · ${Fmt.sqm(areaLeft)} to treat · first stop ${left.first().distanceM} m from the gate",
            "Start spray route", Icons.AutoMirrored.Rounded.ArrowForward
        ) { AppState.routeAllZones(focus.id); nav.navigate(Routes.route(focus.id)) }
        left.isNotEmpty() -> Step(
            "In progress", focus.name,
            "$done of ${zones.size} zones treated · next is ${left.first().label}",
            "Continue route", Icons.AutoMirrored.Rounded.ArrowForward
        ) { nav.navigate(Routes.route(focus.id)) }
        !recorded -> Step(
            "Log the application", focus.name,
            "All ${zones.size} zones marked treated. Record the product before you leave the field.",
            "Record treatment", Icons.Rounded.EditNote
        ) { nav.navigate(Routes.treatment(focus.id)) }
        !verified -> Step(
            "Follow-up ready", focus.name,
            "The +14 d survey is processed. Check per-zone control.",
            "Verify treatment", Icons.Rounded.Verified
        ) { nav.navigate(Routes.verify(focus.id)) }
        else -> Step(
            "All caught up", focus.name,
            "Treated, logged and verified. Next flight ${Fmt.inDays(Demo.date(4, 2, 2027))}.",
            "Open field", Icons.Rounded.DoneAll
        ) { nav.navigate(Routes.field(focus.id)) }
    }

    Card(modifier, padding = PaddingValues(0.dp)) {
        Box(Modifier.fillMaxWidth().height(188.dp).clip(WrShape.lg).rowPress { nav.navigate(Routes.field(focus.id)) }) {
            FieldMap(focus, Modifier.fillMaxSize(), zones = zones, padding = 26.dp, pinSize = 22.dp)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x55000000), 0.35f to Color.Transparent)))
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(step.eyebrow, if (left.isEmpty()) Tone.Moss else Tone.Clay, solid = true, dot = left.isNotEmpty())
            }
        }
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.rowPress { nav.navigate(Routes.field(focus.id)) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(step.title, style = WrType.DisplayS)
                    Spacer(Modifier.height(3.dp))
                    Text(step.body, style = WrType.BodyS)
                }
            }
            if (zones.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Meter(done / zones.size.toFloat(), Modifier.weight(1f), color = Wr.Moss)
                    Spacer(Modifier.width(10.dp))
                    Text("$done/${zones.size}", style = WrType.Caption.copy(color = Wr.Ink2, fontFeatureSettings = "tnum"))
                }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(step.cta, step.go, trailingIcon = step.icon, height = 50.dp)
        }
    }
}

@Composable
fun FieldListRow(f: FieldParcel, onClick: () -> Unit) {
    val zones = AppState.zones(f.id)
    val done = zones.count { it.state.done }
    val season = AppState.seasonOf(f.id)
    ListRow(
        title = f.name,
        subtitle = "${Fmt.area(f.areaAcres)} · ${season?.crop ?: "Wheat"}" +
            if (zones.isNotEmpty()) " · $done/${zones.size} zones" else "",
        leading = { FieldThumbnail(f, size = 56.dp) },
        trailing = { FieldStatus(f) },
        chevron = false,
        onClick = onClick
    )
}

@Composable
fun FieldStatus(f: FieldParcel) {
    val zones = AppState.zones(f.id)
    val next = AppState.surveysOf(f.id).firstOrNull { it.status == SurveyStatus.SCHEDULED }
    when {
        !AppState.hasSurvey(f.id) -> StatusPill(
            if (next != null) "Flight ${Fmt.dayMonth(next.flownAt)}" else "No survey", Tone.Slate
        )
        zones.isEmpty() -> StatusPill("Clean", Tone.Moss)
        zones.all { it.state.done } && AppState.verifications.containsKey(f.id) -> StatusPill("Verified", Tone.Forest)
        zones.all { it.state.done } -> StatusPill("Treated", Tone.Forest)
        AppState.pressureOf(f.id) == Severity.HEAVY -> StatusPill("High pressure", Tone.Clay, dot = true)
        else -> StatusPill("Spot spray", Tone.Wheat, dot = true)
    }
}

@Composable
fun ActivityRow(e: LogEntry, onClick: () -> Unit) {
    val (icon, tone) = when (e.kind) {
        LogKind.SCAN -> Icons.Rounded.Grass to if (e.accent == Wr.Wheat) Tone.Wheat else Tone.Slate
        LogKind.TREATMENT -> Icons.Rounded.WaterDrop to if (e.synced && e.id.startsWith("T-20")) Tone.Neutral else Tone.Forest
        LogKind.ZONE -> Icons.Rounded.DoneAll to Tone.Moss
        LogKind.SURVEY -> Icons.Rounded.CloudDone to Tone.Slate
        LogKind.VERIFY -> Icons.Rounded.Verified to Tone.Forest
        LogKind.FIELD -> Icons.Rounded.Add to Tone.Forest
    }
    ListRow(
        title = e.title,
        subtitle = e.subtitle,
        icon = icon,
        tone = tone,
        trailing = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                Text(Fmt.relative(e.at), style = WrType.Caption)
                if (!e.synced) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Wr.Wheat))
                        Spacer(Modifier.width(4.dp))
                        Text("Queued", style = WrType.Caption.copy(color = Wr.WheatInk, fontWeight = FontWeight.Medium))
                    }
                }
            }
        },
        chevron = false,
        onClick = onClick
    )
}

fun openLogEntry(nav: NavHostController, e: LogEntry) {
    when (e.kind) {
        LogKind.SCAN -> e.refId?.let { nav.navigate(Routes.scanResult(it)) }
        LogKind.VERIFY -> e.fieldId?.let { nav.navigate(Routes.verify(it)) }
        LogKind.SURVEY -> e.fieldId?.let { nav.navigate(Routes.surveys(it)) }
        LogKind.TREATMENT -> Unit
        else -> e.fieldId?.let { nav.navigate(Routes.field(it)) }
    }
}
