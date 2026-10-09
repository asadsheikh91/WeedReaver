package com.example.andriodfypprototype.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.BottomSheetScaffoldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.andriodfypprototype.ui.components.MapButton
import com.example.andriodfypprototype.ui.theme.Wr
import com.example.andriodfypprototype.ui.theme.WrShape
import com.example.andriodfypprototype.ui.theme.WrType

/** Floating header over imagery: back, a title card, trailing map controls. */
@Composable
fun MapTopBar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier.matchParentSizeWithScrim()
        )
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MapButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(10.dp))
            Column(
                Modifier
                    .weight(1f)
                    .shadow(8.dp, WrShape.pill, ambientColor = Color.Black, spotColor = Color(0x44000000))
                    .clip(WrShape.pill)
                    .background(Wr.Ivory)
                    .padding(horizontal = 18.dp, vertical = 7.dp)
            ) {
                Text(title, style = WrType.TitleS, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = WrType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            actions()
        }
    }
}

private fun Modifier.matchParentSizeWithScrim(): Modifier =
    this.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color(0x55000000), Color.Transparent)))

/** Cream bottom sheet over a full-bleed map. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapSheetScaffold(
    state: BottomSheetScaffoldState,
    peek: Dp,
    sheet: @Composable ColumnScope.() -> Unit,
    content: @Composable () -> Unit
) {
    BottomSheetScaffold(
        scaffoldState = state,
        sheetPeekHeight = peek,
        sheetContainerColor = Wr.Cream,
        sheetContentColor = Wr.Ink,
        sheetShape = WrShape.sheet,
        sheetShadowElevation = 16.dp,
        sheetDragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 4.dp).size(width = 36.dp, height = 4.dp).clip(WrShape.pill).background(Wr.LineStrong))
        },
        containerColor = Wr.Night,
        sheetContent = sheet
    ) { content() }
}
