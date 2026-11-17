package io.github.al3j4ndrolm.autoauth

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** The wording on the buttons: "Continue with Google", "Sign in with Google" or "Sign up with Google". */
enum class ButtonText(internal val prefix: String) {
    Continue("Continue with"),
    SignIn("Sign in with"),
    SignUp("Sign up with"),
}

/**
 * Sign-in buttons for the providers you pass, styled the way each provider asks for:
 *
 * ```
 * SignInButtons(
 *     Provider.Google, Provider.Apple, Provider.GitHub, Provider.Email, Provider.Guest,
 *     onSignedIn = { nav.reset(Home) },
 * )
 * ```
 *
 * [Provider.Email] opens a small email and password form, [Provider.Guest] is a "Continue as guest" link
 * (hidden for someone who is already a guest). While one button works, it shows a spinner and the others wait.
 * Errors show under the buttons unless you pass [onError].
 */
@Composable
fun SignInButtons(
    vararg providers: Provider,
    onSignedIn: (User) -> Unit,
    modifier: Modifier = Modifier,
    text: ButtonText = ButtonText.Continue,
    onError: ((message: String) -> Unit)? = null,
) {
    val shown = providers.distinct().ifEmpty { listOf(Provider.Google, Provider.Email) }
    val scope = rememberCoroutineScope()
    val signedIn by rememberUpdatedState(onSignedIn)
    val error by rememberUpdatedState(onError)
    var busy by remember { mutableStateOf<Provider?>(null) }
    var message by remember { mutableStateOf<Message?>(null) }
    var emailOpen by rememberSaveable { mutableStateOf(false) }

    fun <T> run(provider: Provider, done: (T) -> Unit, block: suspend () -> AuthResult<T>) {
        if (busy != null) return
        busy = provider
        message = null
        scope.launch {
            try {
                when (val result = block()) {
                    is AuthResult.Success -> done(result.value)
                    is AuthResult.Error -> error?.invoke(result.message) ?: run { message = Message(result.message, isError = true) }
                    AuthResult.Cancelled -> Unit
                }
            } finally {
                busy = null
            }
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        shown.filter { it != Provider.Email && it != Provider.Guest }.forEach { provider ->
            ProviderButton(provider, text, loading = busy == provider, enabled = busy == null) {
                run(provider, signedIn) { auth.signIn(provider) }
            }
        }

        if (Provider.Email in shown) {
            if (!emailOpen) {
                ProviderButton(Provider.Email, text, loading = false, enabled = busy == null) { emailOpen = true }
            }
            AnimatedVisibility(emailOpen) {
                EmailForm(
                    busy = busy == Provider.Email,
                    enabled = busy == null,
                    signUpFirst = text == ButtonText.SignUp,
                    onSignIn = { email, password -> run(Provider.Email, signedIn) { auth.signIn(email, password) } },
                    onSignUp = { email, password -> run(Provider.Email, signedIn) { auth.signUp(email, password) } },
                    onReset = { email ->
                        run(Provider.Email, { message = Message("Check your email for a link to choose a new password", isError = false) }) {
                            auth.resetPassword(email)
                        }
                    },
                )
            }
        }

        if (Provider.Guest in shown && auth.user?.isGuest != true) {
            TextButton(
                onClick = { run(Provider.Guest, signedIn) { auth.signInAsGuest() } },
                enabled = busy == null,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                if (busy == Provider.Guest) Spinner(MaterialTheme.colorScheme.primary) else Text("Continue as guest")
            }
        }

        message?.let {
            Text(
                it.text,
                color = if (it.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One provider's button, e.g. to place Google sign-in on its own. */
@Composable
fun SignInButton(
    provider: Provider,
    onSignedIn: (User) -> Unit,
    modifier: Modifier = Modifier,
    text: ButtonText = ButtonText.Continue,
    onError: ((message: String) -> Unit)? = null,
) = SignInButtons(provider, onSignedIn = onSignedIn, modifier = modifier, text = text, onError = onError)

private class Message(val text: String, val isError: Boolean)

@Composable
private fun ProviderButton(provider: Provider, text: ButtonText, loading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val brand = brandOf(provider, dark, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.outline)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = brand.container,
        contentColor = brand.content,
        border = brand.border?.let { BorderStroke(1.dp, it) },
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.align(Alignment.CenterStart).size(20.dp), contentAlignment = Alignment.Center) {
                when {
                    loading -> Spinner(brand.content)
                    brand.icon != null -> Icon(brand.icon(), null, Modifier.size(20.dp), tint = brand.iconTint ?: Color.Unspecified)
                    else -> Text("Y!", fontWeight = FontWeight.Black, fontSize = 15.sp)
                }
            }
            Text(
                "${text.prefix} ${provider.label}",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Spinner(color: Color) = CircularProgressIndicator(Modifier.size(18.dp), color = color, strokeWidth = 2.dp)

@Composable
private fun EmailForm(
    busy: Boolean,
    enabled: Boolean,
    signUpFirst: Boolean,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String) -> Unit,
    onReset: (String) -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val primary = if (signUpFirst) onSignUp else onSignIn

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            email, { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            password, { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { primary(email, password) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { primary(email, password) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (busy) Spinner(MaterialTheme.colorScheme.onPrimary)
            else Text(if (signUpFirst) "Create account" else "Sign in")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { (if (signUpFirst) onSignIn else onSignUp)(email, password) }, enabled = enabled) {
                Text(if (signUpFirst) "Sign in instead" else "Create account")
            }
            TextButton(onClick = { onReset(email) }, enabled = enabled) { Text("Forgot password?") }
        }
    }
}

private class Brand(
    val container: Color,
    val content: Color,
    val border: Color?,
    val icon: (@Composable () -> Painter)?,
    /** Null keeps the logo's own colors. */
    val iconTint: Color?,
)

private fun drawable(@DrawableRes id: Int): @Composable () -> Painter = { painterResource(id) }

// Colors from each provider's sign-in button guidelines.
private fun brandOf(provider: Provider, dark: Boolean, onSurface: Color, outline: Color): Brand = when (provider) {
    Provider.Google ->
        if (dark) Brand(Color(0xFF131314), Color(0xFFE3E3E3), Color(0xFF8E918F), drawable(R.drawable.autoauth_google), null)
        else Brand(Color.White, Color(0xFF1F1F1F), Color(0xFF747775), drawable(R.drawable.autoauth_google), null)
    Provider.Microsoft ->
        if (dark) Brand(Color(0xFF2F2F2F), Color.White, null, drawable(R.drawable.autoauth_microsoft), null)
        else Brand(Color.White, Color(0xFF5E5E5E), Color(0xFF8C8C8C), drawable(R.drawable.autoauth_microsoft), null)
    Provider.Apple -> monochrome(R.drawable.autoauth_apple, Color.Black, dark)
    Provider.X -> monochrome(R.drawable.autoauth_x, Color.Black, dark)
    Provider.GitHub -> monochrome(R.drawable.autoauth_github, Color(0xFF24292F), dark)
    Provider.Yahoo -> Brand(Color(0xFF6001D2), Color.White, null, null, null)
    Provider.Email, Provider.Guest -> Brand(Color.Transparent, onSurface, outline, { rememberVectorPainter(Icons.Outlined.Email) }, onSurface)
}

/** Dark on light screens, light on dark screens. */
private fun monochrome(@DrawableRes icon: Int, color: Color, dark: Boolean): Brand =
    if (dark) Brand(Color.White, color, null, drawable(icon), color)
    else Brand(color, Color.White, null, drawable(icon), Color.White)
