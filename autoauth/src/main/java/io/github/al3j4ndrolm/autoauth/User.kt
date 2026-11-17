package io.github.al3j4ndrolm.autoauth

import com.google.firebase.auth.FirebaseUser

/**
 * The signed-in user. For anything else Firebase knows, use `auth.firebase.currentUser`.
 *
 * @property id Firebase's user id. It stays the same when a guest signs in with a real account.
 * @property photo a URL, or null.
 * @property isGuest signed in with [AutoAuth.signInAsGuest] and not upgraded yet.
 * @property providers how this user can sign in, e.g. `[Google, Email]`. Empty for a guest.
 */
data class User(
    val id: String,
    val name: String?,
    val email: String?,
    val photo: String?,
    val isGuest: Boolean,
    val isEmailVerified: Boolean,
    val providers: List<Provider>,
) {
    internal companion object {
        fun from(user: FirebaseUser): User {
            val linked = user.providerData
            return User(
                id = user.uid,
                name = user.displayName.orBlank() ?: linked.firstNotNullOfOrNull { it.displayName.orBlank() },
                email = user.email.orBlank() ?: linked.firstNotNullOfOrNull { it.email.orBlank() },
                photo = (user.photoUrl ?: linked.firstNotNullOfOrNull { it.photoUrl })?.toString(),
                isGuest = user.isAnonymous,
                isEmailVerified = user.isEmailVerified,
                providers = linked.mapNotNull { Provider.fromId(it.providerId) }.distinct(),
            )
        }

        private fun String?.orBlank() = this?.takeIf { it.isNotBlank() }
    }
}
