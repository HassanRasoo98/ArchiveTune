# ArchiveTune Developer Guide

## Project

- Native Android application written in Kotlin with Jetpack Compose and Material 3.
- Gradle Kotlin DSL; use the checked-in `./gradlew` wrapper.
- Java 21 and Android SDK 37 are required. The minimum supported Android version is API 26.
- Main modules are `app`, `core`, `lyrics`, `lastfm`, `canvas`, `shazamkit`, `spotifycore`, and `moriextractor`.

## Common commands

- Build a phone-compatible debug APK: `./gradlew :app:assembleGmsMobileUniversalDebug`
- Install it on a connected phone: `./gradlew :app:installGmsMobileUniversalDebug`
- Run unit tests: `./gradlew test`
- Run lint: `./gradlew lint`

## Conventions

- Preserve the existing Kotlin, Compose, Hilt, and MVVM architecture.
- Prefer existing version-catalog aliases from `gradle/libs.versions.toml` for dependencies.
- Do not commit secrets from `local.properties` or signing material.
- Choose the `gms` or `foss`, `mobile` or `tv`, and ABI flavor explicitly in Gradle commands.
