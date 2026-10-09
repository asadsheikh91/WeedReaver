package com.example.andriodfypprototype.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.example.andriodfypprototype.data.AppState
import com.example.andriodfypprototype.data.Demo
import com.example.andriodfypprototype.data.Fmt
import com.example.andriodfypprototype.data.HracGroup
import com.example.andriodfypprototype.data.Product
import com.example.andriodfypprototype.data.SurveyRole
import com.example.andriodfypprototype.data.SurveyStatus
import com.example.andriodfypprototype.data.TreatmentDraft
import com.example.andriodfypprototype.data.TreatmentRecord
import com.example.andriodfypprototype.data.WeedClass
import com.example.andriodfypprototype.ui.Routes
import com.example.andriodfypprototype.ui.components.BeforeAfterBar
import com.example.andriodfypprototype.ui.components.Card
import com.example.andriodfypprototype.ui.components.ChipRow
import com.example.andriodfypprototype.ui.components.DetailScreen
import com.example.andriodfypprototype.ui.components.EmptyState
import com.example.andriodfypprototype.ui.components.FieldLabel
import com.example.andriodfypprototype.ui.components.Hairline
import com.example.andriodfypprototype.ui.components.LegendSwatch
import com.example.andriodfypprototype.ui.components.ListCard
import com.example.andriodfypprototype.ui.components.ListRow
import com.example.andriodfypprototype.ui.components.Meter
import com.example.andriodfypprototype.ui.components.Notice
import com.example.andriodfypprototype.ui.components.PrimaryButton
import com.example.andriodfypprototype.ui.components.SecondaryButton
import com.example.andriodfypprototype.ui.components.SectionHeader
import com.example.andriodfypprototype.ui.components.Segmented
import com.example.andriodfypprototype.ui.components.SelectField
import com.example.andriodfypprototype.ui.components.Sheet
import com.example.andriodfypprototype.ui.components.Stat
import com.example.andriodfypprototype.ui.components.StatusPill
import com.example.andriodfypprototype.ui.components.TextInput
import com.example.andriodfypprototype.ui.components.Tone
import com.example.andriodfypprototype.ui.components.TrendChart
import com.example.andriodfypprototype.ui.components.pressable
import com.example.andriodfypprototype.ui.components.rememberHaptics
import com.example.andriodfypprototype.ui.components.rowPress
import com.example.andriodfypprototype.ui.map.Aerial
import com.example.andriodfypprototype.ui.map.FieldMap
import com.example.andriodfypprototype.ui.map.MapSurface
import com.example.andriodfypprototype.ui.map.ZonePin
import com.example.andriodfypprototype.ui.map.fieldOutline
import com.example.andriodfypprototype.ui.map.fitBounds
import com.example.andriodfypprototype.ui.map.heatLayer
import com.example.andriodfypprototype.ui.map.imageryFor
import com.example.andriodfypprototype.ui.map.mapAnchor
import com.example.andriodfypprototype.ui.map.rememberMapCamera
import com.example.andriodfypprototype.ui.theme.Wr
import com.example.andriodfypprototype.ui.theme.WrShape
import com.example.andriodfypprototype.ui.theme.WrType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Most recent group applied on a field in an earlier season, if any. */
private fun lastGroupOf(fieldId: String): HracGroup? = AppState.priorFor(fieldId).maxByOrNull { it.appliedAt }?.hracGroup

/* ================================================================== treatment record */

@Composable
fun TreatmentScreen(nav: NavHostController, fieldId: String) {
    val f = AppState.field(fieldId)
    val zones = AppState.zones(fieldId)
    val draft = AppState.draft?.takeIf { it.fieldId == fieldId }
    val defaultZones = zones.filter { it.state.done }.ifEmpty { zones }.map { it.label }
    var chosen by remember { mutableStateOf((draft?.zoneLabels ?: defaultZones).toSet()) }
    var product by remember { mutableStateOf(draft?.product) }
    var dose by remember { mutableStateOf(draft?.dose ?: "") }
    var unit by remember { mutableStateOf(draft?.doseUnit ?: AppState.doseUnits[0]) }
    var water by remember { mutableStateOf(draft?.water ?: "100") }
    var mode by remember { mutableStateOf(draft?.mode ?: AppState.applicationModes[0]) }
    var stage by remember { mutableStateOf(draft?.growthStage ?: AppState.growthStages[1]) }
    var whenIdx by remember { mutableIntStateOf(0) }
    var notes by remember { mutableStateOf(draft?.notes ?: "") }
    var picker by remember { mutableStateOf<String?>(null) }
    val selectedZones = zones.filter { it.label in chosen }
    val areaAc = selectedZones.sumOf { it.areaSqm } / 4046.86f
    val complete = product != null && dose.toFloatOrNull() != null && chosen.isNotEmpty()

    // A pre-filled product from the rotation screen wins over the remembered one.
    val incoming = AppState.draft?.product
    if (incoming != null && incoming != product && draft != null) product = incoming

    DetailScreen(
        "Record treatment",
        onBack = { AppState.draft = null; nav.popBackStack() },
        eyebrow = f.name,
        subtitle = "What went on the crop, and where. Saved on the phone until it syncs.",
        bottomBar = {
            PrimaryButton(
                if (complete) "Check rotation" else if (product == null) "Choose a product" else "Enter the dose",
                {
                    AppState.draft = TreatmentDraft(
                        fieldId, chosen.toList().sorted(), product, dose, unit, mode, stage, areaAc, water, notes,
                        Demo.now() - when (whenIdx) { 1 -> 3 * 3_600_000L; 2 -> 86_400_000L; else -> 0L }
                    )
                    AppState.activeFieldId = fieldId
                    nav.navigate(Routes.ROTATION)
                },
                enabled = complete,
                trailingIcon = Icons.AutoMirrored.Rounded.ArrowForward
            )
        }
    ) {
        item { SectionHeader("Where") }
        item {
            Card(padding = PaddingValues(0.dp)) {
                Box(Modifier.fillMaxWidth().height(170.dp).clip(WrShape.lg)) {
                    FieldMap(f, Modifier.fillMaxSize(), zones = selectedZones, padding = 18.dp, pinSize = 22.dp)
                }
                Column(Modifier.padding(16.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        zones.forEach { z ->
                            val on = z.label in chosen
                            val bg by animateColorAsState(if (on) Wr.Forest else Wr.Ivory, label = "z")
                            Row(
                                Modifier.clip(WrShape.pill).background(bg).border(1.dp, if (on) Wr.Forest else Wr.Line, WrShape.pill)
                                    .pressable { chosen = if (on) chosen - z.label else chosen + z.label }
                                    .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(if (on) Icons.Rounded.Check else Icons.Rounded.RadioButtonUnchecked, null, tint = if (on) Color.White else Wr.Ink3, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(z.label, style = WrType.Label.copy(color = if (on) Color.White else Wr.Ink2))
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row {
                        Stat(Fmt.sqm(selectedZones.sumOf { it.areaSqm }), "Area treated", Modifier.weight(1f))
                        Stat(Fmt.pct(areaAc / f.areaAcres), "Of the field", Modifier.weight(1f))
                        Stat(Fmt.pct(1f - areaAc / f.areaAcres), "Not sprayed", Modifier.weight(1f), valueColor = Wr.Forest2)
                    }
                }
            }
        }

        item { SectionHeader("Product") }
        item {
            SelectField(
                product?.trade ?: "", "Choose a registered herbicide", { picker = "product" },
                supporting = product?.let { "${it.active} · ${it.formulation}" },
                leading = product?.let { p -> { HracBadge(p.hrac, p.hrac == lastGroupOf(fieldId)) } }
            )
            product?.let { p ->
                Spacer(Modifier.height(10.dp))
                if (p.hrac == lastGroupOf(fieldId)) {
                    Notice(
                        "Same mode of action as last season",
                        "${p.hrac.display}. The rotation check will show the history before you save.",
                        icon = Icons.Rounded.WarningAmber, tone = Tone.Clay
                    )
                } else {
                    Text("${p.hrac.display} · different from last season's group", style = WrType.Caption.copy(color = Wr.Forest2))
                }
            }
        }

        item { SectionHeader("Dose") }
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                TextInput(dose, { v -> dose = v.filter { it.isDigit() || it == '.' }.take(6) }, Modifier.weight(1f), label = "Amount per acre", placeholder = "0", keyboard = KeyboardType.Decimal)
                Spacer(Modifier.width(10.dp))
                SelectField(unit, "Unit", { picker = "unit" }, Modifier.weight(0.9f))
            }
            Spacer(Modifier.height(12.dp))
            TextInput(water, { v -> water = v.filter { it.isDigit() }.take(4) }, label = "Spray volume", suffix = "L / acre", keyboard = KeyboardType.Number)
            Spacer(Modifier.height(8.dp))
            Text("Copy the dose from the product label. The app records it; it never calculates one.", style = WrType.Caption)
        }

        item { SectionHeader("Application") }
        item {
            SelectField(mode, "Method", { picker = "mode" }, label = "Method")
            Spacer(Modifier.height(12.dp))
            SelectField(stage, "Crop stage", { picker = "stage" }, label = "Crop growth stage")
            Spacer(Modifier.height(16.dp))
            FieldLabel("Applied")
            Segmented(listOf("Now", "Earlier today", "Yesterday"), whenIdx, { whenIdx = it })
            Spacer(Modifier.height(16.dp))
            TextInput(notes, { notes = it }, label = "Notes", placeholder = "Nozzle, wind, anything unusual", singleLine = false, minLines = 3, optional = true)
        }
    }

    when (picker) {
        "product" -> ProductSheet(product, lastGroupOf(fieldId), onPick = { product = it; picker = null }, onDismiss = { picker = null })
        "unit" -> OptionSheet("Dose unit", AppState.doseUnits, unit, { unit = it; picker = null }) { picker = null }
        "mode" -> OptionSheet("Application method", AppState.applicationModes, mode, { mode = it; picker = null }) { picker = null }
        "stage" -> OptionSheet("Crop growth stage", AppState.growthStages, stage, { stage = it; picker = null }) { picker = null }
    }
}

@Composable
fun HracBadge(g: HracGroup, warn: Boolean = false) {
    Box(
        Modifier.size(38.dp).clip(WrShape.sm).background(if (warn) Wr.ClayBg else Wr.SageTint),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("HRAC", style = WrType.Caption.copy(fontSize = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp), color = if (warn) Wr.ClayInk else Wr.Forest))
            Text(g.code, style = WrType.TitleS.copy(color = if (warn) Wr.ClayInk else Wr.Forest))
        }
    }
}

@Composable
private fun ProductSheet(current: Product?, last: HracGroup?, onPick: (Product) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    val list = AppState.products.filter { p ->
        (filter == "All" || (filter == "Grass" && p.target == WeedClass.GRASS) || (filter == "Broadleaf" && p.target == WeedClass.BROADLEAF)) &&
            (query.isBlank() || p.trade.contains(query, true) || p.active.contains(query, true) || p.hrac.code == query.trim())
    }
    Sheet(onDismiss, "Registered herbicides", "Wheat · advisory list. Rates come from the label.") {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Box {
                TextInput(query, { query = it }, placeholder = "Search name, ingredient or group")
                Icon(Icons.Rounded.Search, null, tint = Wr.Ink3, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        ChipRow(listOf("All", "Grass", "Broadleaf"), filter, { filter = it })
        Spacer(Modifier.height(12.dp))
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            ListCard {
                if (list.isEmpty()) EmptyState(Icons.Rounded.Search, "No match", "Try the active ingredient or an HRAC group number.")
                list.forEachIndexed { i, p ->
                    ListRow(
                        p.trade, "${p.active} · ${p.target.short}",
                        leading = { HracBadge(p.hrac, p.hrac == last) },
                        trailing = { if (p == current) Icon(Icons.Rounded.Check, null, tint = Wr.Forest) },
                        chevron = false,
                        onClick = { onPick(p) }
                    )
                    if (i != list.lastIndex) Hairline(inset = 68.dp)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun OptionSheet(title: String, options: List<String>, selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    Sheet(onDismiss, title) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            ListCard {
                options.forEachIndexed { i, o ->
                    Row(Modifier.fillMaxWidth().rowPress { onPick(o) }.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(o, style = WrType.Body, modifier = Modifier.weight(1f))
                        if (o == selected) Icon(Icons.Rounded.Check, null, tint = Wr.Forest)
                    }
                    if (i != options.lastIndex) Hairline(inset = 16.dp)
                }
            }
        }
    }
}

/* ================================================================== rotation check */

@Composable
fun RotationScreen(nav: NavHostController) {
    val draft = AppState.draft
    val fieldId = draft?.fieldId ?: AppState.activeFieldId
    val f = AppState.field(fieldId)
    val group = draft?.product?.hrac
    val prior = AppState.priorFor(fieldId).sortedByDescending { it.appliedAt }
    val lastGroup = prior.firstOrNull()?.hracGroup
    val streak = prior.takeWhile { it.hracGroup == group }.size
    val clash = group != null && group == lastGroup
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    fun save() {
        val d = draft ?: return
        val p = d.product ?: return
        saving = true
        scope.launch {
            delay(600)
            AppState.saveTreatment(
                TreatmentRecord(
                    AppState.nextTreatmentId(), AppState.seasonOf(fieldId)?.id ?: "FS-$fieldId", fieldId, f.name,
                    d.zoneLabels, d.appliedAt, p.trade, p.active, p.hrac, d.dose, d.doseUnit, d.mode, d.growthStage,
                    AppState.operatorName, d.areaAcres, false, d.water, d.notes
                ),
                clash = clash
            )
            haptics.confirm()
            AppState.draft = null
            AppState.toast("Treatment saved · waiting to sync")
            nav.navigate(Routes.field(fieldId)) { popUpTo(Routes.HOME) }
        }
    }

    DetailScreen(
        if (draft == null) "Rotation history" else "Rotation check",
        onBack = { nav.popBackStack() },
        eyebrow = f.name,
        subtitle = if (draft == null) "Herbicide groups applied on this field across seasons" else "Before the record is written",
        bottomBar = if (draft == null) null else ({
            if (clash) {
                SecondaryButton("Choose a different product", { nav.popBackStack() })
                PrimaryButton("Record anyway", { save() }, color = Wr.Clay, loading = saving)
            } else {
                PrimaryButton("Save treatment", { save() }, icon = Icons.Rounded.Save, loading = saving)
            }
        })
    ) {
        if (draft?.product != null) {
            item {
                val p = draft.product
                Spacer(Modifier.height(14.dp))
                if (clash) {
                    Card(color = Wr.ClayBg, border = Wr.ClayLine, padding = PaddingValues(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(CircleShape).background(Wr.Clay), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.WarningAmber, null, tint = Color.White)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (streak >= 2) "${ordinal(streak + 1)} season in a row" else "Same group as last season", style = WrType.DisplayS.copy(color = Wr.ClayInk))
                                Text("${p.trade} is ${p.hrac.display}", style = WrType.BodyS.copy(color = Wr.ClayInk))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (streak >= 2) "Group ${p.hrac.code} has been applied on ${f.name} every season since ${Fmt.date(prior[streak - 1].appliedAt).takeLast(4)}, and control has fallen each time. Repeating it on survivors is how clodinafop resistance built up in this area."
                            else "Group ${p.hrac.code} was applied on ${f.name} last season. Rotating to a different mode of action slows resistance.",
                            style = WrType.Body.copy(color = Wr.ClayInk)
                        )
                    }
                } else {
                    Notice(
                        "No rotation conflict",
                        "${p.trade} · ${p.hrac.display} differs from the group used last season.",
                        icon = Icons.Rounded.Verified, tone = Tone.Forest
                    )
                }
            }
        }

        if (fieldId == "F-047") item { SectionHeader("Control with Group ${Demo.rotationHistory.last().second.code}") }
        if (fieldId == "F-047") item {
            Card(padding = PaddingValues(start = 8.dp, end = 12.dp, top = 16.dp, bottom = 12.dp)) {
                TrendChart(Demo.rotationHistory.map { it.third }, Demo.rotationHistory.map { "Rabi ${it.first}" })
                Text(
                    "Per-zone control on the worst zone, from the follow-up survey each season.",
                    style = WrType.Caption, modifier = Modifier.padding(start = 8.dp, top = 6.dp)
                )
            }
        }

        item { SectionHeader("Applications on ${f.name}") }
        item {
            ListCard {
                if (prior.isEmpty()) EmptyState(Icons.Rounded.History, "No earlier applications", "Nothing recorded on this field in previous seasons.")
                prior.forEachIndexed { i, t ->
                    ListRow(
                        t.product, "${t.activeIngredient} · ${Fmt.date(t.appliedAt)} · ${Fmt.plural(t.zoneLabels.size, "zone")}",
                        leading = { HracBadge(t.hracGroup, t.hracGroup == group) },
                        chevron = false
                    )
                    if (i != prior.lastIndex) Hairline(inset = 68.dp)
                }
            }
        }

        item { SectionHeader("Registered alternatives") }
        item {
            val alts = AppState.products.filter { it.hrac != lastGroup && it.hrac != group && it.crop == "Wheat" && it.target == WeedClass.GRASS }
            ListCard {
                alts.forEachIndexed { i, p ->
                    ListRow(
                        p.trade, "${p.active} · ${p.hrac.moa}",
                        leading = { HracBadge(p.hrac) },
                        trailing = { if (draft != null) StatusPill("Use", Tone.Forest) },
                        chevron = false,
                        onClick = if (draft != null) ({
                            AppState.draft = draft.copy(product = p)
                            nav.popBackStack()
                        }) else null
                    )
                    if (i != alts.lastIndex) Hairline(inset = 68.dp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Advisory only. Follow the label for rates, and ask the agronomist about a resistant population.", style = WrType.Caption)
        }
    }
}

private fun ordinal(n: Int) = when (n) { 2 -> "Second"; 3 -> "Third"; 4 -> "Fourth"; else -> "${n}th" }

/* ================================================================== verify */

@Composable
fun VerifyScreen(nav: NavHostController, fieldId: String) {
    val f = AppState.field(fieldId)
    val zones = AppState.zones(fieldId)
    var survey by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val saved = AppState.verifications[fieldId]
    val surveys = AppState.surveysOf(fieldId)
    val pre = surveys.firstOrNull { it.role == SurveyRole.PRE }
    val follow = surveys.firstOrNull { it.role == if (survey == 0) SurveyRole.PLUS_14D else SurveyRole.PLUS_28D }
    val ready = follow?.status == SurveyStatus.READY

    val before = remember { AppState.grid(fieldId) }
    val after = remember { AppState.followUpGrid(fieldId) }
    val results = zones.map { z ->
        val factor = Demo.efficacyFactors[z.letter] ?: 0.5f
        z to ((1f - factor) * 100).toInt()
    }
    val worst = results.minByOrNull { it.second }
    val chemicalSaved = 1f - before.treatedAreaFraction

    DetailScreen(
        "Verify treatment",
        onBack = { nav.popBackStack() },
        eyebrow = f.name,
        subtitle = "Compare the follow-up flight with the pre-treatment survey, zone by zone.",
        bottomBar = if (!ready || zones.isEmpty()) null else ({
            PrimaryButton(
                if (saved != null) "Saved ${Fmt.relative(saved)}" else "Save verification",
                {
                    saving = true
                    scope.launch {
                        delay(700)
                        AppState.saveVerification(fieldId, results.map { it.first.label to it.second })
                        saving = false
                        AppState.toast("Verification saved · waiting to sync")
                    }
                },
                enabled = saved == null, loading = saving, icon = if (saved != null) Icons.Rounded.Check else Icons.Rounded.Verified
            )
        })
    ) {
        item {
            Spacer(Modifier.height(14.dp))
            val labels = surveys.filter { it.role != SurveyRole.PRE }.map { "${it.role.short} · ${Fmt.dayMonth(it.flownAt)}" }
            Segmented(labels.ifEmpty { listOf("+14 d", "+28 d") }, survey, { survey = it })
        }

        if (!ready) {
            item {
                EmptyState(
                    Icons.Rounded.Schedule,
                    if (follow == null) "No follow-up flight" else "Flight ${follow.status.label.lowercase()}",
                    if (follow != null) "The ${follow.role.label.lowercase()} survey is ${Fmt.inDays(follow.flownAt)}. Results appear here once it is processed and synced."
                    else "Schedule a follow-up survey on the dashboard.",
                    Modifier.padding(top = 24.dp)
                )
            }
            return@DetailScreen
        }

        item {
            Spacer(Modifier.height(14.dp))
            CompareMap(fieldId, before, after, pre?.flownAt ?: 0L, follow?.flownAt ?: 0L)
        }
        item {
            Card(Modifier.padding(top = 12.dp), padding = PaddingValues(vertical = 14.dp)) {
                Row(Modifier.padding(horizontal = 16.dp)) {
                    Stat("${before.flagged} → ${after.flagged}", "Cells above threshold", Modifier.weight(1.3f))
                    Stat("−${100 - after.flagged * 100 / before.flagged.coerceAtLeast(1)}%", "Field-wide", Modifier.weight(0.8f), valueColor = Wr.Forest2)
                    Stat("${results.count { it.second < 70 }}", "To inspect", Modifier.weight(0.8f), valueColor = if (results.any { it.second < 70 }) Wr.ClayInk else Wr.Ink)
                }
            }
        }

        item { SectionHeader("Control by zone") }
        item {
            val scale = zones.maxOfOrNull { it.meanInfestPct }?.coerceAtLeast(1f) ?: 1f
            ListCard {
                results.forEachIndexed { i, (z, pct) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        ZoneBadge(z, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(z.label, style = WrType.TitleS, modifier = Modifier.weight(1f))
                                Text("$pct%", style = WrType.TitleM.copy(color = if (pct >= 70) Wr.Forest else Wr.ClayInk, fontFeatureSettings = "tnum"))
                            }
                            Spacer(Modifier.height(6.dp))
                            BeforeAfterBar(z.meanInfestPct, z.meanInfestPct * (1f - pct / 100f), scale)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${z.meanInfestPct.toInt()}% → ${(z.meanInfestPct * (1f - pct / 100f)).toInt()}% cover · ${z.dominantClass.short}", style = WrType.Caption, modifier = Modifier.weight(1f))
                                if (pct < 70) StatusPill("Inspect", Tone.Clay)
                            }
                        }
                    }
                    if (i != results.lastIndex) Hairline(inset = 64.dp)
                }
                Row(Modifier.padding(start = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LegendSwatch(Wr.Clay.copy(alpha = 0.75f), "Before")
                    LegendSwatch(Wr.Moss, "After")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Reported per zone, never as a field average: a mean hides the zone where control failed.", style = WrType.Caption)
        }

        if (worst != null && worst.second < 70) {
            item {
                Spacer(Modifier.height(16.dp))
                Notice(
                    "${worst.first.label} barely responded",
                    "Survivors at label rate look like a resistant patch, not a missed spray. The same mode of action has been used here for three seasons.",
                    icon = Icons.Rounded.WarningAmber, tone = Tone.Clay,
                    action = "Open rotation history", onAction = {
                        AppState.activeFieldId = fieldId
                        AppState.draft = null
                        nav.navigate(Routes.ROTATION)
                    }
                )
            }
        }

        item { SectionHeader("Chemical saved") }
        item {
            Card(padding = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Fmt.pct(chemicalSaved), style = WrType.NumXL.copy(color = Wr.Forest))
                    Spacer(Modifier.width(10.dp))
                    Text("less herbicide than a blanket spray", style = WrType.BodyS, modifier = Modifier.padding(bottom = 8.dp))
                }
                Spacer(Modifier.height(10.dp))
                Meter(chemicalSaved, color = Wr.Moss, height = 8.dp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "At equal control, on this prescription, at ${AppState.gridSize.label} resolution. Savings and yield are measured separately against different baselines.",
                    style = WrType.Caption
                )
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            ListCard {
                ListRow("Rotation history", "Groups applied on this field", icon = Icons.Rounded.History, tone = Tone.Neutral) {
                    AppState.activeFieldId = fieldId; AppState.draft = null; nav.navigate(Routes.ROTATION)
                }
            }
        }
    }
}

/** Swipe comparison: pre-treatment heat on the left of the handle, follow-up on the right. */
@Composable
private fun CompareMap(fieldId: String, before: com.example.andriodfypprototype.data.RasterGrid, after: com.example.andriodfypprototype.data.RasterGrid, beforeAt: Long, afterAt: Long) {
    val f = AppState.field(fieldId)
    val zones = AppState.zones(fieldId)
    val hb = remember { Aerial.heatBitmap(before) }
    val ha = remember { Aerial.heatBitmap(after) }
    var split by remember { mutableFloatStateOf(0.5f) }
    var width by remember { mutableFloatStateOf(1f) }
    val camera = rememberMapCamera()
    val haptics = rememberHaptics()
    val density = LocalDensity.current

    Box(
        Modifier.fillMaxWidth().height(300.dp).clip(WrShape.lg)
            .onSizeChanged { width = it.width.toFloat() }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragStart = { haptics.tick() }) { change, dx ->
                    change.consume()
                    split = (split + dx / width).coerceIn(0.04f, 0.96f)
                }
            }
    ) {
        MapSurface(
            spec = imageryFor(f), camera = camera, fit = f.fitBounds(), modifier = Modifier.fillMaxSize(),
            fitPadding = 16.dp, interactive = false,
            overlay = { c ->
                val x = size.width * split
                clipRect(right = x) { heatLayer(c, hb, before, f.boundary) }
                clipRect(left = x) { heatLayer(c, ha, after, f.boundary) }
                fieldOutline(c, f.boundary, width = 1.6f)
            },
            markers = { zones.forEach { z -> ZonePin(z.copy(state = com.example.andriodfypprototype.data.ZoneState.FLAGGED), Modifier.mapAnchor(camera, z.center), size = 18.dp) } }
        )
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width * split
            drawLine(Color.Black.copy(alpha = 0.3f), Offset(x, 0f), Offset(x, size.height), 5.dp.toPx())
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2.5.dp.toPx())
        }
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = with(density) { (width * split).toDp() } - 20.dp)
                .size(40.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.CompareArrows, "Drag to compare", tint = Wr.Ink, modifier = Modifier.size(22.dp)) }
        StatusPill("Before · ${Fmt.dayMonth(beforeAt)}", Tone.Neutral, Modifier.align(Alignment.TopStart).padding(10.dp))
        StatusPill("After · ${Fmt.dayMonth(afterAt)}", Tone.Forest, Modifier.align(Alignment.TopEnd).padding(10.dp))
    }
    Spacer(Modifier.height(6.dp))
    Text("Drag the handle to compare the two flights", style = WrType.Caption, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}
