package io.github.al3j4ndrolm.autoauth

/**
 * What a sign-in (or another account action) ended with:
 *
 * ```
 * auth.signIn()
 *     .onSuccess { user -> nav.reset(Home) }
 *     .onError { message -> error = message }   // "Wrong email or password", "No internet connection", ...
 * ```
 *
 * When the user closes the account picker or the browser tab, it's [Cancelled]: neither
 * `onSuccess` nor `onError` runs, so there's no error to show.
 */
sealed interface AuthResult<out T> {

    data class Success<out T>(val value: T) : AuthResult<T>

    /** [message] is a short sentence you can show as is. */
    data class Error(
        val message: String,
        val reason: Reason,
        val cause: Throwable? = null,
    ) : AuthResult<Nothing>

    /** The user closed the picker or the browser tab. */
    data object Cancelled : AuthResult<Nothing>
}

/** The result of signing in: the [User] on success. */
typealias SignIn = AuthResult<User>

/** Why an [AuthResult.Error] happened, for when you want to react to a case in code. */
enum class Reason {
    /** Wrong email or password. */
    WrongCredentials,
    /** No account with that email. */
    NoAccount,
    /** An account with that email already exists (signing up). */
    AccountExists,
    /** The email already signs in another way, e.g. with Google. */
    OtherMethod,
    InvalidEmail,
    WeakPassword,
    /** The email or password field is empty. */
    MissingInput,
    NoInternet,
    TooManyAttempts,
    Disabled,
    /** Deleting the account or changing the password needs a fresh sign-in first. */
    SignInAgain,
    /** Not signed in. */
    SignedOut,
    /** A Firebase or provider setting is missing. Logcat (tag "AutoAuth") says exactly what to do. */
    NotSetUp,
    Unknown,
}

/** Runs [action] with the value if this is a [AuthResult.Success]. */
inline fun <T> AuthResult<T>.onSuccess(action: (T) -> Unit): AuthResult<T> {
    if (this is AuthResult.Success) action(value)
    return this
}

/** Runs [action] with a message you can show if this is an [AuthResult.Error]. Not called on [AuthResult.Cancelled]. */
inline fun <T> AuthResult<T>.onError(action: (message: String) -> Unit): AuthResult<T> {
    if (this is AuthResult.Error) action(message)
    return this
}

/** The value, or null for an error or a cancel. */
val <T> AuthResult<T>.valueOrNull: T? get() = (this as? AuthResult.Success)?.value
