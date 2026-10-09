package com.example.andriodfypprototype.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.andriodfypprototype.data.AppState
import com.example.andriodfypprototype.ui.components.CountBadge
import com.example.andriodfypprototype.ui.components.MessageHost
import com.example.andriodfypprototype.ui.components.animatedDp
import com.example.andriodfypprototype.ui.components.pressable
import com.example.andriodfypprototype.ui.components.rememberHaptics
import com.example.andriodfypprototype.ui.map.warmImagery
import com.example.andriodfypprototype.ui.screens.AboutScreen
import com.example.andriodfypprototype.ui.screens.ActivityTab
import com.example.andriodfypprototype.ui.screens.AddFieldScreen
import com.example.andriodfypprototype.ui.screens.DrawBoundaryScreen
import com.example.andriodfypprototype.ui.screens.FieldDetailScreen
import com.example.andriodfypprototype.ui.screens.FieldsTab
import com.example.andriodfypprototype.ui.screens.HeatmapScreen
import com.example.andriodfypprototype.ui.screens.MoreTab
import com.example.andriodfypprototype.ui.screens.NavigateScreen
import com.example.andriodfypprototype.ui.screens.QuadratScreen
import com.example.andriodfypprototype.ui.screens.QueueScreen
import com.example.andriodfypprototype.ui.screens.RotationScreen
import com.example.andriodfypprototype.ui.screens.RouteScreen
import com.example.andriodfypprototype.ui.screens.ScanResultScreen
import com.example.andriodfypprototype.ui.screens.ScanTab
import com.example.andriodfypprototype.ui.screens.SeasonScreen
import com.example.andriodfypprototype.ui.screens.SettingsScreen
import com.example.andriodfypprototype.ui.screens.SurveysScreen
import com.example.andriodfypprototype.ui.screens.SyncScreen
import com.example.andriodfypprototype.ui.screens.TreatmentScreen
import com.example.andriodfypprototype.ui.screens.VerifyScreen
import com.example.andriodfypprototype.ui.screens.WalkBoundaryScreen
import com.example.andriodfypprototype.ui.screens.WelcomeScreen
import com.example.andriodfypprototype.ui.theme.Wr
import com.example.andriodfypprototype.ui.theme.WrMotion
import com.example.andriodfypprototype.ui.theme.WrShape

object Routes {
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val SEASON = "season"
    const val ADD_FIELD = "addfield"
    const val WALK = "walk"
    const val DRAW = "draw"
    const val QUEUE = "queue"
    const val SYNC = "sync"
    const val SETTINGS = "settings"
    const val ROTATION = "rotation"
    const val QUADRAT = "quadrat"
    const val ABOUT = "about"
    fun field(id: String) = "field/$id"
    fun heatmap(id: String) = "heatmap/$id"
    fun route(id: String) = "route/$id"
    fun navigate(fieldId: String, zoneId: String) = "navigate/$fieldId/$zoneId"
    fun scanResult(id: String) = "scanresult/$id"
    fun treatment(id: String) = "treatment/$id"
    fun verify(id: String) = "verify/$id"
    fun surveys(id: String) = "surveys/$id"
}

private const val DURATION = 360

private fun AnimatedContentTransitionScope<NavBackStackEntry>.push(): EnterTransition =
    slideInHorizontally(tween(DURATION, easing = WrMotion.Emphasized)) { it / 4 } + fadeIn(tween(DURATION / 2, delayMillis = 40))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.pushExit(): ExitTransition =
    slideOutHorizontally(tween(DURATION, easing = WrMotion.Emphasized)) { -it / 12 } + fadeOut(tween(DURATION, easing = WrMotion.Emphasized), targetAlpha = 0.6f)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.pop(): EnterTransition =
    slideInHorizontally(tween(DURATION, easing = WrMotion.Emphasized)) { -it / 12 } + fadeIn(tween(DURATION), initialAlpha = 0.6f)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    slideOutHorizontally(tween(DURATION, easing = WrMotion.Emphasized)) { it / 4 } + fadeOut(tween(DURATION / 2))

@Composable
fun WeedReaverApp() {
    val nav = rememberNavController()
    LaunchedEffect(Unit) { warmImagery() }
    // The station ended the session (refresh token reused, account disabled): back to sign-in.
    LaunchedEffect(AppState.sessionEnded) {
        if (AppState.sessionEnded > 0) nav.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
    }
    Box(Modifier.fillMaxSize().background(Wr.Cream)) {
        // A cold start reads the offline store before the first frame that shows records.
        if (!AppState.loaded) return@Box
        NavHost(
            nav,
            startDestination = Routes.WELCOME,
            enterTransition = { push() },
            exitTransition = { pushExit() },
            popEnterTransition = { pop() },
            popExitTransition = { popExit() }
        ) {
            composable(Routes.WELCOME, enterTransition = { fadeIn() }, exitTransition = { fadeOut(tween(260)) }) { WelcomeScreen(nav) }
            composable(Routes.HOME, enterTransition = { fadeIn(tween(420)) + scaleIn(tween(420, easing = WrMotion.Emphasized), 0.985f) }) { MainShell(nav) }
            composable(Routes.SEASON) { SeasonScreen(nav) }
            composable(Routes.ADD_FIELD) { AddFieldScreen(nav) }
            composable(Routes.WALK) { WalkBoundaryScreen(nav) }
            composable(Routes.DRAW) { DrawBoundaryScreen(nav) }
            composable(Routes.QUEUE) { QueueScreen(nav) }
            composable(Routes.SYNC) { SyncScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.ROTATION) { RotationScreen(nav) }
            composable(Routes.QUADRAT) { QuadratScreen(nav) }
            composable(Routes.ABOUT) { AboutScreen(nav) }
            composable("field/{id}") { FieldDetailScreen(nav, it.arg("id")) }
            composable("heatmap/{id}") { HeatmapScreen(nav, it.arg("id")) }
            composable("route/{id}") { RouteScreen(nav, it.arg("id")) }
            composable("navigate/{id}/{zone}") { NavigateScreen(nav, it.arg("id"), it.arg("zone")) }
            composable("scanresult/{id}") { ScanResultScreen(nav, it.arg("id")) }
            composable("treatment/{id}") { TreatmentScreen(nav, it.arg("id")) }
            composable("verify/{id}") { VerifyScreen(nav, it.arg("id")) }
            composable("surveys/{id}") { SurveysScreen(nav, it.arg("id")) }
        }
        MessageHost(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().offset(y = if (AppState.signedIn) (-72).dp else 0.dp))
    }
}

private fun NavBackStackEntry.arg(name: String) = arguments?.getString(name) ?: ""

/**
 * Light or dark status-bar icons. Every screen declares what it needs instead of restoring
 * on dispose: during a transition both screens are composed, and a restore would race.
 */
@Composable
fun StatusBarIcons(light: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(light) {
        val window = (view.context as? Activity)?.window
        window?.let {
            val ctl = WindowCompat.getInsetsController(it, view)
            ctl.isAppearanceLightStatusBars = !light
            ctl.isAppearanceLightNavigationBars = !light
        }
        onDispose { }
    }
}

/** Keeps the display awake while the operator is navigating or walking a boundary. */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(AppState.keepScreenOn) {
        view.keepScreenOn = AppState.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
}

private data class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab("Fields", Icons.Outlined.Map, Icons.Rounded.Map),
    Tab("Scan", Icons.Outlined.CenterFocusWeak, Icons.Rounded.CenterFocusStrong),
    Tab("Activity", Icons.Outlined.History, Icons.Rounded.History),
    Tab("More", Icons.Outlined.AccountCircle, Icons.Rounded.AccountCircle)
)

@Composable
fun MainShell(nav: NavHostController) {
    val tab = AppState.homeTab
    BackHandler(enabled = tab != 0) { AppState.homeTab = 0 }
    val dark = tab == 1
    StatusBarIcons(light = dark)
    val bg by animateColorAsState(if (dark) Wr.Night else Wr.Cream, tween(320), label = "shell")

    Column(Modifier.fillMaxSize().background(bg)) {
        Box(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 70)) + scaleIn(tween(260, delayMillis = 70, easing = WrMotion.Emphasized), 0.985f))
                        .togetherWith(fadeOut(tween(90)))
                },
                label = "tabs"
            ) { t ->
                Box(Modifier.fillMaxSize().then(if (t == 1) Modifier else Modifier.statusBarsPadding())) {
                    when (t) {
                        0 -> FieldsTab(nav)
                        1 -> ScanTab(nav)
                        2 -> ActivityTab(nav)
                        else -> MoreTab(nav)
                    }
                }
            }
        }
        BottomBar(tab, dark) { AppState.homeTab = it }
    }
}

@Composable
private fun BottomBar(selected: Int, dark: Boolean, onSelect: (Int) -> Unit) {
    val haptics = rememberHaptics()
    val bar by animateColorAsState(if (dark) Wr.Night else Wr.Ivory, tween(320), label = "bar")
    val line by animateColorAsState(if (dark) Color.White.copy(alpha = 0.08f) else Wr.Line, tween(320), label = "line")
    Column(Modifier.fillMaxWidth().background(bar)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(line))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(68.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { i, t ->
                val on = i == selected
                val ink by animateColorAsState(
                    when {
                        on && dark -> Color.White
                        on -> Wr.Forest
                        dark -> Color.White.copy(alpha = 0.55f)
                        else -> Wr.Ink3
                    }, tween(220), label = "ink"
                )
                val pill by animateColorAsState(
                    when {
                        on && dark -> Color.White.copy(alpha = 0.14f)
                        on -> Wr.Sage
                        else -> Color.Transparent
                    }, tween(220), label = "pill"
                )
                val pillW = animatedDp(if (on) 60.dp else 32.dp)
                val badge = when (i) {
                    2 -> AppState.pendingSync
                    3 -> AppState.abstentionQueue.size
                    else -> 0
                }
                Column(
                    Modifier
                        .weight(1f)
                        .pressable(scaleTo = 0.92f) { if (!on) { haptics.tick(); onSelect(i) } },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(Modifier.width(pillW).height(32.dp).clip(WrShape.pill).background(pill))
                        Icon(if (on) t.selectedIcon else t.icon, t.label, tint = ink, modifier = Modifier.size(24.dp))
                        if (badge > 0) {
                            CountBadge(
                                badge, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp),
                                color = if (i == 2) Wr.Wheat else Wr.Clay
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t.label,
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        color = ink
                    )
                }
            }
        }
    }
}
