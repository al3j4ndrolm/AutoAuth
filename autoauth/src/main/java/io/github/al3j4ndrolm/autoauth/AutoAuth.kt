package io.github.al3j4ndrolm.autoauth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Firebase sign-in in one line, from anywhere:
 *
 * ```
 * auth.signIn()                              // Google
 * auth.signIn(email, password)
 * auth.signIn(Provider.GitHub)
 * auth.user                                  // the signed-in User, or null
 * auth.signOut()
 * ```
 */
val auth: AutoAuth get() = AutoAuth

object AutoAuth {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val userState = mutableStateOf<User?>(null)
    private val userFlowState = MutableStateFlow<User?>(null)
    private var started = false

    private val firebaseOrNull: FirebaseAuth? by lazy {
        try {
            FirebaseAuth.getInstance()
        } catch (e: IllegalStateException) {
            Errors.log(Hint.NoFirebase, null, e)
            null
        }
    }

    /**
     * The signed-in user, or null. Composables reading it redraw when it changes:
     *
     * ```
     * SnapNav(start = if (auth.user == null) Login else Home)
     * ```
     *
     * Known right away at launch: no waiting or loading screen needed.
     */
    val user: User?
        get() {
            start()
            return userState.value
        }

    /** [user] as a flow, for ViewModels. */
    val userFlow: StateFlow<User?>
        get() {
            start()
            return userFlowState.asStateFlow()
        }

    /** Firebase's own [FirebaseAuth], for anything AutoAuth doesn't cover. */
    val firebase: FirebaseAuth
        get() = firebaseOrNull ?: error("Firebase isn't set up in this app: add google-services.json and the google-services plugin")

    /**
     * The OAuth client id Google sign-in uses. Read from google-services.json by default
     * (`default_web_client_id`), so you only set it if you don't use that file.
     */
    @JvmStatic
    var googleClientId: String? = null

    /** Signs in with Google, using Android's own account sheet. */
    suspend fun signIn(): SignIn = signIn(Provider.Google)

    /**
     * Signs in with [provider]. Google uses Android's account sheet; Apple, Microsoft, GitHub,
     * Yahoo and X open a browser tab. A guest who signs in keeps their user id and data.
     *
     * For [Provider.Email] use `signIn(email, password)`.
     */
    suspend fun signIn(provider: Provider): SignIn = attempt(provider) {
        when (provider) {
            Provider.Google -> google()
            Provider.Guest -> guest()
            Provider.Email -> throw IllegalArgumentException("Use signIn(email, password) to sign in with email")
            else -> browser(provider)
        }
    }

    /** Signs in with an existing email account. */
    suspend fun signIn(email: String, password: String): SignIn = attempt(Provider.Email) {
        checkInput(email, password)?.let { return@attempt it }
        val result = firebase.signInWithEmailAndPassword(email.trim(), password).await()
        signedIn(result.user)
    }

    /**
     * Creates an email account and signs in. A guest becomes this account and keeps their data.
     *
     * @param name shown as [User.name].
     */
    suspend fun signUp(email: String, password: String, name: String? = null): SignIn = attempt(Provider.Email) {
        checkInput(email, password)?.let { return@attempt it }
        val credential = EmailAuthProvider.getCredential(email.trim(), password)
        val current = firebase.currentUser
        val result = if (current?.isAnonymous == true) {
            current.linkWithCredential(credential).await()
        } else {
            firebase.createUserWithEmailAndPassword(email.trim(), password).await()
        }
        val user = result.user
        if (user != null && !name.isNullOrBlank()) {
            user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()).await()
        }
        signedIn(user)
    }

    /**
     * Signs in without an account. Their data is kept when they later sign in with a provider
     * or sign up with email. Already signed in? Returns that user.
     */
    suspend fun signInAsGuest(): SignIn = signIn(Provider.Guest)

    /** Emails a link to choose a new password. Succeeds for unknown emails too, so nobody can probe which exist. */
    suspend fun resetPassword(email: String): AuthResult<Unit> = attempt(Provider.Email) {
        if (email.isBlank()) return@attempt AuthResult.Error("Enter your email", Reason.MissingInput)
        firebase.sendPasswordResetEmail(email.trim()).await()
        AuthResult.Success(Unit)
    }

    /** Emails the signed-in user a link to confirm their address. See [User.isEmailVerified] and [refresh]. */
    suspend fun sendVerificationEmail(): AuthResult<Unit> = attempt(Provider.Email) {
        val current = firebase.currentUser ?: return@attempt signedOut()
        current.sendEmailVerification().await()
        AuthResult.Success(Unit)
    }

    /** Reloads the user from Firebase, e.g. after they confirmed their email in another app. */
    suspend fun refresh(): AuthResult<User> = attempt(null) {
        val current = firebase.currentUser ?: return@attempt signedOut()
        current.reload().await()
        signedIn(firebase.currentUser)
    }

    /** Signs out. The next Google sign-in asks which account again. */
    fun signOut() {
        firebaseOrNull?.signOut()
        publish(null)
        scope.launch {
            runCatching { CredentialManager.create(Tracker.appContext).clearCredentialState(ClearCredentialStateRequest()) }
        }
    }

    /**
     * Deletes the account. If the user signed in a while ago, Firebase asks for a fresh sign-in
     * first: you get [Reason.SignInAgain].
     */
    suspend fun delete(): AuthResult<Unit> = attempt(null) {
        val current = firebase.currentUser ?: return@attempt signedOut()
        current.delete().await()
        signOut()
        AuthResult.Success(Unit)
    }

    /** A fresh ID token for your own server, or null if signed out or offline. */
    suspend fun token(forceRefresh: Boolean = false): String? = try {
        firebaseOrNull?.currentUser?.getIdToken(forceRefresh)?.await()?.token
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't get an ID token", e)
        null
    }

    // region Internals

    internal fun start() {
        if (started) return
        started = true
        val firebase = firebaseOrNull ?: return
        publish(firebase.currentUser)
        firebase.addAuthStateListener { publish(it.currentUser) }
    }

    private fun publish(user: FirebaseUser?): User? {
        val value = user?.let(User::from)
        userState.value = value
        userFlowState.value = value
        return value
    }

    private fun signedIn(user: FirebaseUser?): SignIn =
        publish(user)?.let { AuthResult.Success(it) } ?: AuthResult.Error("Couldn't sign in. Try again", Reason.Unknown)

    private fun signedOut() = AuthResult.Error("You're not signed in", Reason.SignedOut)

    private fun checkInput(email: String, password: String): AuthResult.Error? = when {
        email.isBlank() -> AuthResult.Error("Enter your email", Reason.MissingInput)
        password.isEmpty() -> AuthResult.Error("Enter your password", Reason.MissingInput)
        else -> null
    }

    /** Runs [block] on the main thread and turns any failure into an [AuthResult]. */
    private suspend fun <T> attempt(provider: Provider?, block: suspend () -> AuthResult<T>): AuthResult<T> {
        start()
        if (firebaseOrNull == null) {
            Errors.log(Hint.NoFirebase, provider, null)
            return Errors.notSetUp(provider)
        }
        return try {
            withContext(Dispatchers.Main.immediate) { block() }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = (e as? com.google.firebase.auth.FirebaseAuthException)?.errorCode?.let { " ($it)" }.orEmpty()
            Log.d(TAG, "${provider?.consoleName ?: "Auth"} failed: ${e.javaClass.simpleName}$code: ${e.message}")
            val outcome = Errors.classify(e, provider)
            if (outcome.hint != null) Errors.log(outcome.hint, provider, e)
            else if (outcome.result is AuthResult.Error) Log.i(TAG, "${provider?.consoleName ?: "Auth"}: ${outcome.result.message}")
            outcome.result
        }
    }

    private suspend fun activity(): Activity =
        Tracker.activity() ?: throw IllegalStateException("No screen is showing, so there's nowhere to sign in")

    private suspend fun google(): SignIn {
        val activity = activity()
        val clientId = googleClientId ?: webClientId(activity)
        if (clientId == null) {
            Errors.log(Hint.NoWebClientId, Provider.Google, null)
            return Errors.notSetUp(Provider.Google)
        }
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return AuthResult.Error("Couldn't sign in with Google. Try again", Reason.Unknown)
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        return signInOrLink(GoogleAuthProvider.getCredential(idToken, null))
    }

    private suspend fun browser(provider: Provider): SignIn {
        val firebase = firebase
        // Android may have closed the app while the browser tab was open: finish that sign-in.
        firebase.pendingAuthResult?.let { return signedIn(it.await().user) }

        val activity = activity()
        val oauth = OAuthProvider.newBuilder(provider.id).apply {
            when (provider) {
                Provider.Apple -> scopes = listOf("email", "name")
                Provider.Microsoft -> addCustomParameter("prompt", "select_account")
                else -> Unit
            }
        }.build()
        val current = firebase.currentUser
        val result = if (current?.isAnonymous == true) {
            try {
                current.startActivityForLinkWithProvider(activity, oauth).await()
            } catch (e: FirebaseAuthUserCollisionException) {
                // That account already exists: sign in to it (the guest is left behind).
                val existing = e.updatedCredential ?: throw e
                firebase.signInWithCredential(existing).await()
            }
        } else {
            firebase.startActivityForSignInWithProvider(activity, oauth).await()
        }
        return signedIn(result.user)
    }

    private suspend fun guest(): SignIn {
        firebase.currentUser?.let { return signedIn(it) }
        return signedIn(firebase.signInAnonymously().await().user)
    }

    /** A guest becomes the new account; if that account already exists, signs in to it instead. */
    private suspend fun signInOrLink(credential: AuthCredential): SignIn {
        val current = firebase.currentUser
        if (current?.isAnonymous == true) {
            try {
                return signedIn(current.linkWithCredential(credential).await().user)
            } catch (e: FirebaseAuthUserCollisionException) {
                return signedIn(firebase.signInWithCredential(e.updatedCredential ?: credential).await().user)
            }
        }
        return signedIn(firebase.signInWithCredential(credential).await().user)
    }

    // Generated from google-services.json by the google-services plugin.
    @SuppressLint("DiscouragedApi")
    private fun webClientId(context: Context): String? {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id == 0) null else context.getString(id).takeIf { it.isNotBlank() }
    }

    // endregion
}
