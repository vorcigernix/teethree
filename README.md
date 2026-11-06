# Teethree

A native Kotlin and Jetpack Compose Android client for T3 Code. Open this directory
in Android Studio. The project builds independently and vendors its terminal
runtime under `third_party/ghostty`.

## Build

Use JDK 25, Android SDK 37, NDK `28.2.13676358`, and CMake `3.22.1`.
Android Studio's bundled JDK and SDK Manager provide these tools. The original
Gradle daemon criteria and Foojay resolver are preserved and select JDK 25.
Set `JAVA_HOME` and `ANDROID_HOME`, or configure the SDK through `local.properties`.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:installDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. The release task
`:app:assembleRelease` produces an unsigned APK with code and resources optimized.
Native Compose device tests run with `:app:connectedDebugAndroidTest`.

The app supports Android 12 and newer. Pair it with a running T3 Code environment
using that environment's pairing link. Android 17 requests local-network access
when connecting to local environments.

This candidate uses the original Android development package,
`com.t3tools.t3code.dev`, separately from the published production app. Clerk's
native OAuth callback is `clerk://com.t3tools.t3code.dev.callback`; the selected
Clerk instance must authorize it. The default public key selects T3's hosted
instance and does not grant access to configure its callback allowlist. An
enabled email-code sign-in flow completes inside the native UI without an OAuth
callback; use the email already attached to the existing T3 account.

The existing Android client remains the default until this Kotlin client reaches
full feature parity and passes device verification.

Push notifications use the original mobile build's
`T3CODE_ANDROID_GOOGLE_SERVICES_FILE` environment variable or Gradle property.
Point it at a Firebase Android configuration file with a client matching this
app's application ID (`com.t3tools.t3code.dev`). An ignored
`app/google-services.json` also works. Without that file the app builds normally;
Firebase push delivery remains unconfigured. Device tokens are generated at
runtime; Firebase server credentials belong to the relay deployment.

Ported T3 Code sources retain the upstream MIT notice in `third_party/t3code/LICENSE`.
Terminal runtime/font provenance is recorded in `third_party/ghostty`.
