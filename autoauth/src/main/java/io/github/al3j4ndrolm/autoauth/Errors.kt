package io.github.al3j4ndrolm.autoauth

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import java.security.MessageDigest

internal const val TAG = "AutoAuth"

/** A setup step the developer is missing. Logged with exact instructions, never shown to users. */
internal enum class Hint { NoFirebase, AuthNotStarted, ProviderOff, Fingerprint, NoWebClientId }

/** Turns Firebase and Credential Manager errors into a sentence for users, plus a hint for the developer. */
internal object Errors {

    class Outcome(val result: AuthResult<Nothing>, val hint: Hint? = null)

    fun classify(e: Throwable, provider: Provider?): Outcome = when (e) {
        // A missing SHA-1 also arrives as a "cancel": "[16] Account reauth failed." (a real one says "Cancelled by user").
        is GetCredentialCancellationException ->
            if (e.message.orEmpty().contains("reauth failed", ignoreCase = true)) Outcome(notSetUp(provider, e), Hint.Fingerprint)
            else Outcome(AuthResult.Cancelled)
        is NoCredentialException ->
            // Also what Google answers when the app's SHA-1 isn't in Firebase.
            Outcome(error("No Google account was found on this phone", Reason.NoAccount, e), Hint.Fingerprint)
        is GetCredentialException ->
            if (isDeveloperError(e)) Outcome(notSetUp(provider, e), Hint.Fingerprint)
            else Outcome(error("Couldn't sign in with Google. Try again", Reason.Unknown, e))
        is FirebaseNetworkException -> Outcome(error("No internet connection", Reason.NoInternet, e))
        is FirebaseTooManyRequestsException -> Outcome(tooMany(e))
        is FirebaseAuthException -> byCode(e, provider)
        is FirebaseException ->
            if (e.message.orEmpty().contains("CONFIGURATION_NOT_FOUND")) Outcome(notSetUp(provider, e), Hint.AuthNotStarted)
            else Outcome(error("Couldn't sign in. Try again", Reason.Unknown, e))
        else -> Outcome(error("Couldn't sign in. Try again", Reason.Unknown, e))
    }

    private fun byCode(e: FirebaseAuthException, provider: Provider?): Outcome = when (e.errorCode) {
        "ERROR_WEB_CONTEXT_CANCELED" -> Outcome(AuthResult.Cancelled)
        "ERROR_WEB_CONTEXT_ALREADY_PRESENT" -> Outcome(error("Sign-in is already open", Reason.Unknown, e))
        "ERROR_INVALID_EMAIL" -> Outcome(error("That email doesn't look right", Reason.InvalidEmail, e))
        "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_LOGIN_CREDENTIALS" ->
            if (provider == Provider.Email) Outcome(error("Wrong email or password", Reason.WrongCredentials, e))
            else Outcome(error("Couldn't sign in. Try again", Reason.Unknown, e))
        "ERROR_USER_NOT_FOUND" -> Outcome(error("There's no account with that email", Reason.NoAccount, e))
        "ERROR_EMAIL_ALREADY_IN_USE" ->
            Outcome(error("That email already has an account. Sign in instead", Reason.AccountExists, e))
        "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL", "ERROR_CREDENTIAL_ALREADY_IN_USE" ->
            Outcome(error("That email already signs in another way. Use that one", Reason.OtherMethod, e))
        "ERROR_WEAK_PASSWORD" -> Outcome(error("Use a password with at least 6 characters", Reason.WeakPassword, e))
        "ERROR_USER_DISABLED" -> Outcome(error("This account has been disabled", Reason.Disabled, e))
        "ERROR_TOO_MANY_REQUESTS" -> Outcome(tooMany(e))
        "ERROR_REQUIRES_RECENT_LOGIN", "ERROR_USER_TOKEN_EXPIRED", "ERROR_INVALID_USER_TOKEN" ->
            Outcome(error("For your security, sign in again first", Reason.SignInAgain, e))
        "ERROR_NETWORK_REQUEST_FAILED" -> Outcome(error("No internet connection", Reason.NoInternet, e))
        // A turned-off provider: "OPERATION_NOT_ALLOWED", or for guests "restricted to administrators only".
        "ERROR_OPERATION_NOT_ALLOWED", "ERROR_ADMIN_RESTRICTED_OPERATION" -> Outcome(notSetUp(provider, e), Hint.ProviderOff)
        "ERROR_APP_NOT_AUTHORIZED", "ERROR_INVALID_CERT_HASH" -> Outcome(notSetUp(provider, e), Hint.Fingerprint)
        "ERROR_INTERNAL_ERROR" ->
            if (e.message.orEmpty().contains("CONFIGURATION_NOT_FOUND")) Outcome(notSetUp(provider, e), Hint.AuthNotStarted)
            else Outcome(error("Couldn't sign in. Try again", Reason.Unknown, e))
        else -> Outcome(error("Couldn't sign in. Try again", Reason.Unknown, e))
    }

    // Google answers a missing SHA-1 with "[28444] Developer console is not set up correctly" (or the older code 10).
    private fun isDeveloperError(e: GetCredentialException): Boolean {
        val text = "${e.type} ${e.errorMessage}"
        return "28444" in text || "Developer console" in text || "DEVELOPER_ERROR" in text
    }

    private fun tooMany(e: Throwable) = error("Too many attempts. Try again in a few minutes", Reason.TooManyAttempts, e)

    fun notSetUp(provider: Provider?, cause: Throwable? = null) = error(
        if (provider == null) "Sign-in isn't set up yet" else "${provider.consoleName} sign-in isn't set up yet",
        Reason.NotSetUp,
        cause,
    )

    private fun error(message: String, reason: Reason, cause: Throwable?) = AuthResult.Error(message, reason, cause)

    /** Logs what the developer has to do. Users only see the short message. */
    fun log(hint: Hint, provider: Provider?, cause: Throwable?) {
        val text = runCatching { Hints.text(hint, provider, Tracker.appContext, projectId()) }
            .getOrElse { "AutoAuth: ${hint.name}" }
        Log.w(TAG, text)
    }

    private fun projectId(): String? = runCatching { FirebaseApp.getInstance().options.projectId }.getOrNull()
}

internal object Hints {

    private const val CONSOLE = "https://console.firebase.google.com"

    fun text(hint: Hint, provider: Provider?, context: Context?, projectId: String?): String {
        val project = projectId?.let { "$CONSOLE/project/$it" } ?: CONSOLE
        val name = provider?.consoleName ?: "This"
        return when (hint) {
            Hint.NoFirebase ->
                "Firebase isn't set up in this app. Add google-services.json to your app module and the " +
                    "com.google.gms.google-services plugin. Steps: https://firebase.google.com/docs/android/setup"
            Hint.AuthNotStarted ->
                "Authentication isn't started in your Firebase project. Open $project/authentication and press Get started."
            Hint.NoWebClientId ->
                "Google sign-in isn't turned on, so google-services.json has no web client id. " +
                    "Turn on Google at $project/authentication/providers, then download google-services.json again " +
                    "($project/settings/general) and replace the one in your app."
            Hint.Fingerprint -> buildString {
                append("$name sign-in was refused because Firebase doesn't know this build's signing key. ")
                append("Add this fingerprint at $project/settings/general → Your apps → Add fingerprint:")
                context?.let { fingerprints(it) }?.forEach { (kind, value) -> append("\n  $kind: $value") }
                append("\nThen download google-services.json again. Debug and release (Play) builds have different keys: add each.")
            }
            Hint.ProviderOff -> buildString {
                append("$name sign-in is turned off. Turn it on at $project/authentication/providers.")
                providerSetup(provider, projectId)?.let { append("\n").append(it) }
            }
        }
    }

    /** Where to get the client id and secret Firebase asks for, for providers that open a browser tab. */
    private fun providerSetup(provider: Provider?, projectId: String?): String? {
        val callback = "https://${projectId ?: "<your-project>"}.firebaseapp.com/__/auth/handler"
        return when (provider) {
            Provider.GitHub -> "Create a GitHub OAuth app at https://github.com/settings/developers with callback URL $callback"
            Provider.Microsoft -> "Register an app at https://entra.microsoft.com (App registrations) with redirect URI $callback"
            Provider.Apple -> "Create a Services ID at https://developer.apple.com/account/resources/identifiers with return URL $callback"
            Provider.Yahoo -> "Create an app at https://developer.yahoo.com/apps with redirect URI $callback"
            Provider.X -> "Create an app at https://developer.x.com with callback URL $callback"
            else -> null
        }
    }

    /** The SHA-1 and SHA-256 of the key this build is signed with. */
    @Suppress("DEPRECATION")
    fun fingerprints(context: Context): List<Pair<String, String>> = runCatching {
        val pm = context.packageManager
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }.orEmpty()
        certs.flatMap { listOf("SHA-1" to fingerprint(it.toByteArray(), "SHA-1"), "SHA-256" to fingerprint(it.toByteArray(), "SHA-256")) }
    }.getOrDefault(emptyList())

    fun fingerprint(bytes: ByteArray, algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString(":") { "%02X".format(it) }
}
