package io.github.al3j4ndrolm.autoauth.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.firebase.FirebaseApp
import io.github.al3j4ndrolm.autoauth.Provider
import io.github.al3j4ndrolm.autoauth.SignInButtons
import io.github.al3j4ndrolm.autoauth.User
import io.github.al3j4ndrolm.autoauth.auth
import io.github.al3j4ndrolm.autoauth.onError
import io.github.al3j4ndrolm.autoauth.onSuccess
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val firebaseReady = FirebaseApp.getApps(this).isNotEmpty()
        setContent {
            MaterialTheme(if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // auth.user is Compose state: signing in or out swaps the screen.
                        val user = auth.user
                        when {
                            !firebaseReady -> NoFirebase()
                            user == null -> Login()
                            else -> Home(user)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Login() {
    Spacer(Modifier.height(32.dp))
    Text("Welcome", style = MaterialTheme.typography.headlineLarge)
    Text("Sign in to continue", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    SignInButtons(
        Provider.Google, Provider.Apple, Provider.GitHub, Provider.Microsoft, Provider.Email, Provider.Guest,
        onSignedIn = { },   // auth.user changes, so this screen is replaced by Home
    )
}

@Composable
private fun Home(user: User) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }

    Spacer(Modifier.height(32.dp))
    Box(
        Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (user.name ?: user.email ?: "?").take(1).uppercase(),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
    Text(if (user.isGuest) "Hi, guest" else "Hi ${user.name ?: user.email}", style = MaterialTheme.typography.headlineMedium)
    Text(
        buildString {
            user.email?.let { append(it).append(if (user.isEmailVerified) " (verified)\n" else " (not verified)\n") }
            append("Signs in with: ").append(user.providers.joinToString { it.label }.ifEmpty { "nothing yet (guest)" })
            append("\nUser id: ").append(user.id)
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (user.isGuest) {
        HorizontalDivider()
        Text("Save your progress", style = MaterialTheme.typography.titleMedium)
        Text("Sign in and keep this same user id and everything saved under it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SignInButtons(Provider.Google, Provider.GitHub, Provider.Email, onSignedIn = { status = "Saved. Same user id: ${it.id}" })
    }

    HorizontalDivider()
    if (Provider.Email in user.providers && !user.isEmailVerified) {
        OutlinedButton(onClick = {
            scope.launch {
                auth.sendVerificationEmail().onSuccess { status = "Sent. Open the link, then tap Refresh" }.onError { status = it }
            }
        }) { Text("Verify my email") }
        TextButton(onClick = { scope.launch { auth.refresh().onError { status = it } } }) { Text("Refresh") }
    }
    OutlinedButton(onClick = {
        scope.launch { status = auth.token()?.let { "ID token for your server: ${it.take(24)}…" } ?: "No token" }
    }) { Text("Get ID token") }
    OutlinedButton(onClick = { auth.signOut() }) { Text("Sign out") }
    TextButton(onClick = { scope.launch { auth.delete().onError { status = it } } }) {
        Text("Delete account", color = MaterialTheme.colorScheme.error)
    }
    status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun NoFirebase() {
    Spacer(Modifier.height(32.dp))
    Text("Add your Firebase project", style = MaterialTheme.typography.headlineMedium)
    Text(
        "1. Create a project at console.firebase.google.com\n" +
            "2. Add an Android app with the package io.github.al3j4ndrolm.autoauth.sample\n" +
            "3. Put its google-services.json in sample/ and run again",
        textAlign = TextAlign.Start,
    )
}
