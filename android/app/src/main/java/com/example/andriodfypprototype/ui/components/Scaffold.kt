package com.example.andriodfypprototype.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.andriodfypprototype.data.AppState
import com.example.andriodfypprototype.ui.theme.Wr
import com.example.andriodfypprototype.ui.theme.WrShape
import com.example.andriodfypprototype.ui.theme.WrType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ============================================================== top bars */

@Composable
fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier, onImagery: Boolean = false) {
    if (onImagery) {
        MapButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, modifier, size = 44.dp)
    } else {
        IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, modifier)
    }
}

/**
 * Detail screen with an iOS-style large title that hands over to a compact bar once it
 * scrolls away. The hairline only appears when content is actually underneath the bar.
 */
@Composable
fun DetailScreen(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    eyebrow: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    state: LazyListState = rememberLazyListState(),
    bottomBar: (@Composable ColumnScope.() -> Unit)? = null,
    contentPadding: Dp = 20.dp,
    header: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    val collapsed by remember { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 70 } }
    val scrolled by remember { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 4 } }
    val lineAlpha by animateFloatAsState(if (scrolled) 1f else 0f, label = "line")
    com.example.andriodfypprototype.ui.StatusBarIcons(light = false)

    Column(modifier.fillMaxSize().background(Wr.Cream)) {
        Column(Modifier.background(Wr.Cream).statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) BackButton(onBack) else Spacer(Modifier.width(14.dp))
                Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    androidx.compose.animation.AnimatedVisibility(
                        collapsed,
                        enter = fadeIn() + slideInVertically { it / 3 },
                        exit = fadeOut() + slideOutVertically { it / 3 }
                    ) {
                        Text(title, style = WrType.TitleM, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, content = actions)
                Spacer(Modifier.width(6.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).graphicsLayer { alpha = lineAlpha }.background(Wr.Line))
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = state,
                contentPadding = PaddingValues(start = contentPadding, end = contentPadding, bottom = if (bottomBar == null) 40.dp else 12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "__title") {
                    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp)) {
                        if (eyebrow != null) {
                            Text(eyebrow.uppercase(), style = WrType.Overline)
                            Spacer(Modifier.height(6.dp))
                        }
                        Text(title, style = WrType.DisplayM)
                        if (subtitle != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(subtitle, style = WrType.BodyS)
                        }
                    }
                }
                if (header != null) item(key = "__header") { header() }
                content()
            }
        }
        if (bottomBar != null) BottomActions(content = bottomBar)
    }
}

/** Home-tab header: serif title, a line of context and trailing actions. */
@Composable
fun TabHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            if (subtitle != null) {
                Text(subtitle, style = WrType.Caption.copy(color = Wr.Ink2, fontWeight = FontWeight.Medium))
                Spacer(Modifier.height(2.dp))
            }
            Text(title, style = WrType.DisplayM)
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/* ============================================================== sheets and dialogs */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(
    onDismiss: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Wr.Cream,
        contentColor = Wr.Ink,
        scrimColor = Color(0xFF0B140F).copy(alpha = 0.42f),
        shape = WrShape.sheet,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(WrShape.pill).background(Wr.LineStrong))
        }
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            if (title != null) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp)) {
                    Text(title, style = WrType.DisplayS)
                    if (subtitle != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(subtitle, style = WrType.BodyS)
                    }
                }
            }
            content()
        }
    }
}

/** Explanations live behind an info affordance, not in the operator's path. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(title: String, paragraphs: List<String>, onDismiss: () -> Unit, footer: String? = null) {
    Sheet(onDismiss, title) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            paragraphs.forEachIndexed { i, p ->
                if (i > 0) Spacer(Modifier.height(12.dp))
                Text(p, style = WrType.Body.copy(color = Wr.Ink2))
            }
            if (footer != null) {
                Spacer(Modifier.height(16.dp))
                Text(footer, style = WrType.Caption)
            }
            Spacer(Modifier.height(20.dp))
            SecondaryButton("Got it", onDismiss)
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismiss: String = "Cancel"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Wr.Ivory,
        shape = WrShape.xl,
        title = { Text(title, style = WrType.DisplayS) },
        text = { Text(body, style = WrType.Body.copy(color = Wr.Ink2)) },
        confirmButton = {
            Text(
                confirm,
                style = WrType.Button.copy(color = if (destructive) Wr.Clay else Wr.Forest),
                modifier = Modifier.clip(WrShape.pill).pressable { onConfirm() }.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        },
        dismissButton = {
            Text(
                dismiss,
                style = WrType.Button.copy(color = Wr.Ink2),
                modifier = Modifier.clip(WrShape.pill).pressable { onDismiss() }.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    )
}

/* ============================================================== transient messages */

@Composable
fun MessageHost(modifier: Modifier = Modifier) {
    val msg = AppState.message
    var shown by remember { mutableStateOf<AppState.UiMessage?>(null) }
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(msg?.id) {
        if (msg != null) {
            if (visible) { visible = false; delay(160) }
            shown = msg
            visible = true
            delay(if (msg.action != null) 5200 else 3200)
            visible = false
            AppState.consumeMessage(msg.id)
        }
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 }
        ) {
            val m = shown ?: return@AnimatedVisibility
            Row(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .shadow(16.dp, WrShape.md, ambientColor = Color.Black, spotColor = Color(0x66000000))
                    .clip(WrShape.md)
                    .background(Wr.Night2)
                    .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(m.text, style = WrType.BodyS.copy(color = Wr.Cream), modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                if (m.action != null) {
                    Text(
                        m.action,
                        style = WrType.Button.copy(color = Color(0xFFB9DDA3)),
                        modifier = Modifier.clip(WrShape.pill)
                            .pressable {
                                m.onAction?.invoke()
                                visible = false
                                scope.launch { delay(200); AppState.consumeMessage(m.id) }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                } else {
                    Icon(
                        Icons.Rounded.Close, "Dismiss", tint = Wr.Cream.copy(alpha = 0.6f),
                        modifier = Modifier.size(36.dp).clip(CircleShape).pressable { visible = false }.padding(8.dp)
                    )
                }
            }
        }
    }
}
