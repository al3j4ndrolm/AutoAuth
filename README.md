# AutoAuth

[![JitPack](https://jitpack.io/v/al3j4ndrolm/AutoAuth.svg)](https://jitpack.io/#al3j4ndrolm/AutoAuth)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

**Firebase sign-in for Android in one line.** Google, Apple, GitHub, Microsoft, email and guests, with ready-made buttons, plain error messages, and Logcat telling you exactly what setup is missing.

From the maker of [SnapNavigation](https://github.com/al3j4ndrolm/SnapNavigation) and the Facilis family: [FacilisCam](https://github.com/al3j4ndrolm/FacilisCam), [FacilisAI](https://github.com/al3j4ndrolm/FacilisAI) and more.

```kotlin
SignInButtons(
    Provider.Google, Provider.Apple, Provider.GitHub, Provider.Email, Provider.Guest,
    onSignedIn = { user -> /* go home */ },
)
```

That draws each provider's official button, an email and password form, and a "Continue as guest" link. While one button works it shows a spinner, and errors show underneath in plain words.

## Or call it yourself

```kotlin
auth.signIn()                          // Google, on Android's own account sheet
auth.signIn(Provider.GitHub)           // Apple, Microsoft, GitHub, Yahoo and X open a browser tab
auth.signIn(email, password)
auth.signUp(email, password, name = "Ale")
auth.signInAsGuest()
auth.resetPassword(email)
auth.signOut()
```

Every call returns a result:

```kotlin
scope.launch {
    auth.signIn()
        .onSuccess { user -> nav.reset(Home) }
        .onError { message -> error = message }   // "Wrong email or password", "No internet connection", ...
}
```

Closing the account sheet or the browser tab is neither a success nor an error, so you never show "Cancelled" by mistake.

## The signed-in user

```kotlin
auth.user          // User(id, name, email, photo, isGuest, isEmailVerified, providers), or null
```

It's Compose state, so a screen that reads it redraws when someone signs in or out. It's known right away at launch, with no loading screen:

```kotlin
setContent {
    if (auth.user == null) LoginScreen() else HomeScreen()
}
```

In a ViewModel, use `auth.userFlow`. For your own server, `auth.token()` gives a fresh ID token.

## Guests keep their data

```kotlin
auth.signInAsGuest()   // start without an account
// later, on a "Save your progress" screen:
auth.signIn()          // the guest becomes this Google account: same user id, same data
```

This works for every provider and for `signUp`. If that account already existed, they're signed in to it instead.

## Setup mistakes, explained

Firebase's own errors are vague. AutoAuth shows users a short message ("Google sign-in isn't set up yet") and logs the fix with links to *your* project:

```
W AutoAuth: Google sign-in was refused because Firebase doesn't know this build's signing key.
            Add this fingerprint at https://console.firebase.google.com/project/my-app/settings/general → Your apps → Add fingerprint:
              SHA-1: AB:CD:12:34:...
```

```
W AutoAuth: GitHub sign-in is turned off. Turn it on at https://console.firebase.google.com/project/my-app/authentication/providers.
            Create a GitHub OAuth app at https://github.com/settings/developers with callback URL https://my-app.firebaseapp.com/__/auth/handler
```

The SHA-1 printed is the real one of the build you're running, so you can paste it straight in.

## Install

1. [Add Firebase to your app](https://firebase.google.com/docs/android/setup): the `google-services.json` file and the `com.google.gms.google-services` plugin.
2. Add JitPack to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

3. Add AutoAuth (min SDK 23):

```kotlin
dependencies {
    implementation("com.github.al3j4ndrolm:AutoAuth:1.0.0")
}
```

4. In the Firebase console, open **Authentication → Sign-in method** and turn on the providers you use.

## What each provider needs

| Provider | In the Firebase console | Elsewhere |
| --- | --- | --- |
| Email, Guest | Turn it on | Nothing |
| Google | Turn it on, add your SHA-1 | Nothing |
| GitHub, Microsoft, Yahoo, X | Turn it on, paste a client id and secret | A free developer app at that provider |
| Apple | Turn it on, paste a Services ID and key | A paid Apple Developer account |

The browser-tab providers also need your SHA-1. AutoAuth's Logcat hints include the callback URL each provider asks for.

## Good to know

- **Debug and release builds have different SHA-1s.** Add both, plus Google Play's app signing key from the Play Console.
- **Buttons follow each provider's guidelines** (Google's and Apple's are required by their stores), in light and dark mode.
- **`delete()` may answer "sign in again first"** (`Reason.SignInAgain`) if the user signed in long ago. That's a Firebase security rule.
- **Not included yet:** phone (SMS) and Facebook sign-in, which need extra setup inside the app.
- **Firebase itself** is at `auth.firebase` for anything AutoAuth doesn't cover.

## Contributing

```bash
./gradlew :autoauth:testDebugUnitTest :autoauth:assembleRelease :sample:assembleDebug
```

`autoauth/` is the library and `sample/` a demo app. To run the sample, put your own Firebase project's `google-services.json` in `sample/` (package `io.github.al3j4ndrolm.autoauth.sample`). Without it, the sample builds and shows how to add one.

## License

[MIT](LICENSE)
