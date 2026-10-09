package com.example.andriodfypprototype.ui.map

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.andriodfypprototype.data.AppState
import com.example.andriodfypprototype.data.FieldParcel
import com.example.andriodfypprototype.data.TreatmentZone
import com.example.andriodfypprototype.ui.theme.Wr
import com.example.andriodfypprototype.ui.theme.WrMotion
import com.example.andriodfypprototype.ui.theme.WrShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val specCache = HashMap<String, Aerial.Spec>()

/** Imagery spec for a parcel; stable so the rendered mosaic is shared by every screen. */
fun imageryFor(f: FieldParcel): Aerial.Spec = synchronized(specCache) {
    val key = "${f.id}-${f.boundary.hashCode()}"
    specCache.getOrPut(key) {
        Aerial.spec(key, f.boundary, f.landscape, if (AppState.hasSurvey(f.id)) AppState.infestation(f.id) else null)
    }
}

fun FieldParcel.fitBounds(pad: Float = 0f): FloatArray {
    val b = bounds
    return floatArrayOf(b[0] - pad, b[1] - pad, b[2] + pad, b[3] + pad)
}

/** Renders every parcel's imagery in the background so maps are ready before they are opened. */
suspend fun warmImagery() = withContext(Dispatchers.Default) {
    AppState.fields.toList().forEach { f ->
        kotlinx.coroutines.yield()
        Aerial.load(imageryFor(f))
    }
}

/** Static or lightly interactive map of one parcel, with its zones as pins. */
@Composable
fun FieldMap(
    field: FieldParcel,
    modifier: Modifier = Modifier,
    zones: List<TreatmentZone> = emptyList(),
    camera: MapCamera = rememberMapCamera(),
    interactive: Boolean = false,
    padding: Dp = 22.dp,
    insetTop: Dp = 0.dp,
    insetBottom: Dp = 0.dp,
    dim: Boolean = true,
    pinSize: Dp = 26.dp,
    outline: Color = Color.White,
    fitMeters: Float = 0f,
    extra: @Composable BoxScope.() -> Unit = {}
) {
    val spec = remember(field.id) { imageryFor(field) }
    MapSurface(
        spec = spec,
        camera = camera,
        fit = field.fitBounds(fitMeters),
        modifier = modifier,
        fitPadding = padding,
        insetTop = insetTop,
        insetBottom = insetBottom,
        interactive = interactive,
        overlay = { c ->
            if (dim) dimOutside(c, field.boundary, 0.18f)
            fieldOutline(c, field.boundary, outline)
        },
        markers = {
            zones.forEach { z ->
                ZonePin(z, Modifier.mapAnchor(camera, z.center), size = pinSize)
            }
            extra()
        }
    )
}

@Composable
fun FieldThumbnail(field: FieldParcel, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Box(
        modifier
            .size(size)
            .clip(WrShape.sm)
            .border(1.dp, Color.Black.copy(alpha = 0.06f), WrShape.sm)
    ) {
        FieldMap(field, Modifier.fillMaxSize(), padding = 7.dp, dim = false)
    }
}

@Composable
fun ZonePin(
    zone: TreatmentZone,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    active: Boolean = false,
    index: Int? = null
) {
    val done = zone.state.done
    val fill = if (done) Wr.Forest else zone.severity.fill
    val scale by animateFloatAsState(if (active) 1.22f else 1f, WrMotion.settle(), label = "pin")
    val pulse = if (active) rememberPulse(1600) else 0f
    Box(modifier.size(size * 2), contentAlignment = Alignment.Center) {
        if (active) {
            Box(
                Modifier.size(size * 2).graphicsLayer {
                    scaleX = 0.5f + pulse * 0.6f; scaleY = 0.5f + pulse * 0.6f; alpha = (1f - pulse) * 0.7f
                }.clip(CircleShape).background(fill.copy(alpha = 0.5f))
            )
        }
        Box(
            Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .size(size)
                .shadow(4.dp, CircleShape, ambientColor = Color.Black, spotColor = Color(0x88000000))
                .clip(CircleShape)
                .background(Color.White)
                .padding(2.dp)
                .clip(CircleShape)
                .background(fill),
            contentAlignment = Alignment.Center
        ) {
            if (done) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
            else Text(
                index?.toString() ?: zone.letter,
                color = if (zone.severity.name == "MODERATE") Wr.Ink else Color.White,
                fontSize = (size.value * 0.44f).sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
