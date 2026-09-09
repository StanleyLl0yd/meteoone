# Android toolchain baseline

Verified against official upstream documentation on 2026-09-09.

## Baseline

| Component | Version / level |
| --- | --- |
| Android Gradle Plugin | 9.4.0 |
| Gradle | 9.6.0 |
| JDK | 17 |
| Kotlin | 2.4.20 |
| Compose BOM | 2026.08.00 |
| Compose UI | 1.12.0 through the BOM |
| Material 3 | 1.4.0 through the BOM |
| Activity Compose | 1.13.0 |
| Lifecycle | 2.11.0 |
| compileSdk | 37 |
| targetSdk | 36 |
| minSdk | 26 |

## Rationale

AGP 9.4.0 supports API 37 and requires Gradle 9.6.0 and JDK 17.

AGP 9 uses built-in Kotlin for Android modules, so MeteoOne does not apply the legacy `org.jetbrains.kotlin.android` plugin to `:app`.

Stable Compose 1.12.0 requires compileSdk 37. Targeting remains API 36 for the initial baseline, which satisfies the Google Play requirement effective 2026-08-31 while avoiding an unnecessary target-SDK behavior jump during foundation work.

The Compose compiler Gradle plugin uses the same Kotlin release line.

## Sources

- https://developer.android.com/build/releases/agp-9-4-0-release-notes
- https://docs.gradle.org/9.6.0/release-notes.html
- https://kotlinlang.org/docs/releases.html
- https://developer.android.com/build/migrate-to-built-in-kotlin
- https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler
- https://developer.android.com/jetpack/androidx/releases/compose
- https://developer.android.com/jetpack/androidx/releases/activity
- https://developer.android.com/jetpack/androidx/releases/lifecycle
- https://developer.android.com/google/play/requirements/target-sdk
