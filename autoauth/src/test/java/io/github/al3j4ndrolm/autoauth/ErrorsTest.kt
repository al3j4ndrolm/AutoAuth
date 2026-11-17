package io.github.al3j4ndrolm.autoauth

import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorsTest {

    private fun error(e: Throwable, provider: Provider? = Provider.Email) =
        Errors.classify(e, provider).result as AuthResult.Error

    private fun code(code: String) = FirebaseAuthException(code, "details")

    @Test
    fun `email mistakes become plain sentences`() {
        assertEquals("Wrong email or password", error(code("ERROR_INVALID_CREDENTIAL")).message)
        assertEquals(Reason.WrongCredentials, error(code("ERROR_WRONG_PASSWORD")).reason)
        assertEquals(Reason.AccountExists, error(code("ERROR_EMAIL_ALREADY_IN_USE")).reason)
        assertEquals(Reason.WeakPassword, error(code("ERROR_WEAK_PASSWORD")).reason)
        assertEquals(Reason.InvalidEmail, error(code("ERROR_INVALID_EMAIL")).reason)
    }

    @Test
    fun `a bad credential from Google isn't called a wrong password`() {
        val e = error(code("ERROR_INVALID_CREDENTIAL"), Provider.Google)
        assertEquals(Reason.Unknown, e.reason)
    }

    @Test
    fun `closing the browser tab is a cancel, not an error`() {
        assertSame(AuthResult.Cancelled, Errors.classify(code("ERROR_WEB_CONTEXT_CANCELED"), Provider.GitHub).result)
    }

    @Test
    fun `missing setup gives a hint and names the provider`() {
        val off = Errors.classify(code("ERROR_OPERATION_NOT_ALLOWED"), Provider.GitHub)
        assertEquals(Hint.ProviderOff, off.hint)
        assertEquals("GitHub sign-in isn't set up yet", (off.result as AuthResult.Error).message)
        assertEquals(Reason.NotSetUp, off.result.reason)

        val guests = Errors.classify(code("ERROR_ADMIN_RESTRICTED_OPERATION"), Provider.Guest)
        assertEquals(Hint.ProviderOff, guests.hint)
        assertEquals("Anonymous sign-in isn't set up yet", (guests.result as AuthResult.Error).message)
        assertTrue(Hints.text(Hint.ProviderOff, Provider.Email, null, "p").startsWith("Email/Password sign-in is turned off"))

        assertEquals(Hint.Fingerprint, Errors.classify(code("ERROR_APP_NOT_AUTHORIZED"), Provider.Google).hint)
        assertEquals(Hint.AuthNotStarted, Errors.classify(FirebaseException("CONFIGURATION_NOT_FOUND"), Provider.Email).hint)
    }

    @Test
    fun `Google's missing SHA-1 looks like a cancel, but isn't`() {
        val missing = Errors.classify(GetCredentialCancellationException("[16] Account reauth failed."), Provider.Google)
        assertEquals(Hint.Fingerprint, missing.hint)
        assertEquals("Google sign-in isn't set up yet", (missing.result as AuthResult.Error).message)

        val cancelled = Errors.classify(GetCredentialCancellationException("[16] Cancelled by user."), Provider.Google)
        assertSame(AuthResult.Cancelled, cancelled.result)
    }

    @Test
    fun `network and unknown errors`() {
        assertEquals(Reason.NoInternet, error(FirebaseNetworkException("offline")).reason)
        val unknown = Errors.classify(RuntimeException("boom"), Provider.Google)
        assertNull(unknown.hint)
        assertEquals(Reason.Unknown, (unknown.result as AuthResult.Error).reason)
    }

    @Test
    fun `hints point at the project and the callback URL`() {
        val text = Hints.text(Hint.ProviderOff, Provider.GitHub, null, "my-app")
        assertTrue(text, "https://console.firebase.google.com/project/my-app/authentication/providers" in text)
        assertTrue(text, "https://my-app.firebaseapp.com/__/auth/handler" in text)
    }

    @Test
    fun `fingerprints are colon separated hex`() {
        val sha1 = Hints.fingerprint("abc".toByteArray(), "SHA-1")
        assertEquals("A9:99:3E:36:47:06:81:6A:BA:3E:25:71:78:50:C2:6C:9C:D0:D8:9D", sha1)
    }

    @Test
    fun `result helpers`() {
        var got: String? = null
        var failed: String? = null
        AuthResult.Success("x").onSuccess { got = it }.onError { failed = it }
        assertEquals("x", got)
        assertNull(failed)

        AuthResult.Cancelled.onSuccess { got = "cancel" }.onError { failed = it }
        assertEquals("x", got)
        assertNull("a cancel shows no error", failed)

        AuthResult.Error("Nope", Reason.Unknown).onError { failed = it }
        assertEquals("Nope", failed)
        assertNull(AuthResult.Error("Nope", Reason.Unknown).valueOrNull)
    }

    @Test
    fun `provider ids map back, guests have none`() {
        assertEquals(Provider.Google, Provider.fromId("google.com"))
        assertEquals(Provider.X, Provider.fromId("twitter.com"))
        assertNull(Provider.fromId("firebase"))
        assertNull(Provider.fromId("anonymous"))
    }
}
