package com.example.andriodfypprototype.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.example.andriodfypprototype.BuildConfig
import com.example.andriodfypprototype.data.AppState
import com.example.andriodfypprototype.data.Store
import com.example.andriodfypprototype.data.net.ApiException
import com.example.andriodfypprototype.data.net.OfflineException
import com.example.andriodfypprototype.data.Demo
import com.example.andriodfypprototype.data.Fmt
import com.example.andriodfypprototype.data.Pt
import com.example.andriodfypprototype.ui.Routes
import com.example.andriodfypprototype.ui.StatusBarIcons
import com.example.andriodfypprototype.ui.components.Avatar
import com.example.andriodfypprototype.ui.components.BrandMark
import com.example.andriodfypprototype.ui.components.Card
import com.example.andriodfypprototype.ui.components.PrimaryButton
import com.example.andriodfypprototype.ui.components.Segmented
import com.example.andriodfypprototype.ui.components.Sheet
import com.example.andriodfypprototype.ui.components.TextInput
import com.example.andriodfypprototype.ui.components.enter
import com.example.andriodfypprototype.ui.components.pressable
import com.example.andriodfypprototype.ui.map.FieldMap
import com.example.andriodfypprototype.ui.map.rememberMapCamera
import com.example.andriodfypprototype.ui.theme.Wr
import com.example.andriodfypprototype.ui.theme.WrShape
import com.example.andriodfypprototype.ui.theme.WrType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WelcomeScreen(nav: NavHostController) {
    val field = AppState.field("F-047")
    val camera = rememberMapCamera()
    val stored = remember { Store.lastUser }
    var role by remember { mutableIntStateOf(if (stored?.role == "TRAINEE") 1 else 0) }
    var opening by remember { mutableStateOf(false) }
    var signIn by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // The account on this phone, if it matches the chosen role: it opens without signal.
    val account = stored?.takeIf { (it.role == "TRAINEE") == (role == 1) }

    fun open() {
        opening = true
        scope.launch {
            delay(650)
            AppState.signedIn = true
            if (AppState.trainee) AppState.voicePrompts = true
            Store.requestSync()
            nav.navigate(Routes.HOME) { popUpTo(Routes.WELCOME) { inclusive = true } }
        }
    }
    StatusBarIcons(light = true)

    // Slow drift across the survey mosaic.
    LaunchedEffect(camera.ready) {
        if (!camera.ready) return@LaunchedEffect
        val base = camera.center
        val z = camera.zoom
        var i = 0
        while (true) {
            val t = listOf(Pt(28f, -14f), Pt(-30f, 10f), Pt(10f, 22f), Pt(-12f, -18f))[i % 4]
            camera.animateTo(Pt(base.x + t.x, base.y + t.y), z * (if (i % 2 == 0) 1.08f else 1.0f), 12000)
            i++
        }
    }

    Box(Modifier.fillMaxSize().background(Wr.Cream)) {
        FieldMap(
            field,
            Modifier.fillMaxWidth().height(430.dp),
            camera = camera,
            padding = 0.dp,
            fitMeters = 40f,
            dim = false,
            outline = Color.White.copy(alpha = 0.85f)
        )
        Box(
            Modifier.fillMaxWidth().height(430.dp)
                .background(Brush.verticalGradient(0f to Color(0x66000000), 0.25f to Color.Transparent, 0.62f to Color.Transparent, 1f to Wr.Cream))
        )

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(size = 34.dp, background = Wr.Cream, ink = Wr.Forest)
                Spacer(Modifier.width(10.dp))
                Text("WeedReaver", style = WrType.DisplayS.copy(color = Color.White))
            }
            Spacer(Modifier.weight(1f))

            Text("Know where control failed.", style = WrType.DisplayL, modifier = Modifier.enter(0))
            Spacer(Modifier.height(10.dp))
            Text(
                "Survey-guided spot spraying for wheat. Built to work in the field without signal.",
                style = WrType.Body.copy(color = Wr.Ink2),
                modifier = Modifier.enter(1)
            )
            Spacer(Modifier.height(26.dp))

            Card(Modifier.enter(2), padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(if (role == 0) Fmt.initials(account?.name ?: Demo.OPERATOR) else "TR", size = 46.dp, color = if (role == 0) Wr.Forest else Wr.Wheat)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(account?.name ?: if (role == 0) "Field operator" else "Trainee operator", style = WrType.TitleM)
                        Text(
                            when {
                                account != null && role == 0 -> listOfNotNull(account.roleLabel.ifBlank { null }, account.operatorCode).joinToString(" · ")
                                role == 0 -> "Sign in with your station account"
                                else -> "Guided mode · voice prompts on"
                            },
                            style = WrType.BodyS
                        )
                    }
                    Icon(Icons.Rounded.Lock, null, tint = Wr.Ink3, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.height(14.dp))
                Segmented(listOf("Operator", "Trainee"), role, { role = it }, height = 38.dp)
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().enter(3).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.CloudOff, null, tint = Wr.Ink2, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (AppState.fields.isEmpty()) "Sign in once with signal · then it works offline"
                    else "Offline · ${AppState.fields.size} fields and imagery on this phone",
                    style = WrType.Caption.copy(color = Wr.Ink2)
                )
            }
            Spacer(Modifier.height(18.dp))

            PrimaryButton(
                if (opening) "Opening" else "Continue",
                loading = opening,
                onClick = { if (account != null && Store.hasSession) open() else signIn = true },
                modifier = Modifier.enter(4)
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                Text("Version ${Demo.APP_VERSION}", style = WrType.Caption)
                Text("  ·  ", style = WrType.Caption)
                Text(
                    "About this build",
                    style = WrType.Caption.copy(color = Wr.Forest2, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.clip(WrShape.pill).pressable { nav.navigate(Routes.ABOUT) }
                )
            }
        }
    }

    if (signIn) SignInSheet(trainee = role == 1, onDismiss = { signIn = false }) { signIn = false; open() }
}

/**
 * The station account behind the card. Sign-in needs signal once: it registers this handset,
 * downloads the operator's fields, and from then on the phone opens without a network.
 */
@Composable
private fun SignInSheet(trainee: Boolean, onDismiss: () -> Unit, onSignedIn: () -> Unit) {
    val last = Store.lastUser?.takeIf { (it.role == "TRAINEE") == trainee }?.email
    val demo = if (BuildConfig.DEMO_PASSWORD.isNotEmpty()) {
        if (trainee) "trainee.0212@pindibhattian-station.pk" else "a.mehmood@pindibhattian-station.pk"
    } else ""
    var email by remember { mutableStateOf(last ?: demo) }
    var password by remember { mutableStateOf(BuildConfig.DEMO_PASSWORD) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Sheet(
        { if (!busy) onDismiss() },
        if (trainee) "Trainee sign-in" else "Sign in",
        "Your station account. Needed once with signal; after that the phone works offline."
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            TextInput(email, { email = it; error = null }, label = "Email", placeholder = "name@station.pk", keyboard = KeyboardType.Email)
            Spacer(Modifier.height(14.dp))
            TextInput(password, { password = it; error = null }, label = "Password", password = true, error = error)
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                if (busy) "Downloading your fields" else "Sign in",
                onClick = {
                    if (busy) return@PrimaryButton
                    busy = true
                    scope.launch {
                        try {
                            Store.signIn(email, password)
                            onSignedIn()
                        } catch (e: OfflineException) {
                            error = "The station could not be reached. Sign-in needs signal once."
                        } catch (e: ApiException) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = email.isNotBlank() && password.isNotEmpty(), loading = busy
            )
        }
    }
}
