package io.github.al3j4ndrolm.autoauth

/**
 * A way to sign in. Each one has to be turned on in the Firebase console
 * (Authentication → Sign-in method). If one isn't, AutoAuth logs what to do.
 */
enum class Provider(internal val id: String, val label: String) {
    /** Android's own Google account sheet. Needs your app's SHA-1 in Firebase. */
    Google("google.com", "Google"),
    /** Opens a browser tab. Needs an Apple Developer account. */
    Apple("apple.com", "Apple"),
    /** Opens a browser tab. Needs an app in Microsoft Entra (Azure). */
    Microsoft("microsoft.com", "Microsoft"),
    /** Opens a browser tab. Needs a GitHub OAuth app. */
    GitHub("github.com", "GitHub"),
    /** Opens a browser tab. Needs a Yahoo app. */
    Yahoo("yahoo.com", "Yahoo"),
    /** X (Twitter). Opens a browser tab. Needs an X developer app. */
    X("twitter.com", "X"),
    /** Email and password. */
    Email("password", "email"),
    /** No account: a guest who can sign in for real later and keep their data. */
    Guest("anonymous", "guest");

    /** The name the Firebase console uses. */
    internal val consoleName: String
        get() = when (this) {
            Email -> "Email/Password"
            Guest -> "Anonymous"
            else -> label
        }

    internal val opensBrowser get() = this in setOf(Apple, Microsoft, GitHub, Yahoo, X)

    internal companion object {
        fun fromId(id: String): Provider? = entries.firstOrNull { it.id == id && it != Guest }
    }
}
