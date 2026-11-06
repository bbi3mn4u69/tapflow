# ADR-007: Bootstrap toolchain and honest readiness

Status: accepted, 2026-11-07.

Pin Gradle 9.6.0, AGP 9.5.1, Kotlin/Compose compiler 2.4.21, Ktor 3.6.1, and Compose BOM 2026.09.00. AGP 9 supplies built-in Kotlin for Android, so the obsolete Kotlin Android plugin is not applied. The backend builds/runs with JDK 21; shared protocol bytecode targets JVM 17 for Android consumers.

Use the stable Android SDK 37.2 package with target API 37 and min API 28. The current Compose artifacts require API 37; API 36 produces an explicit AAR metadata failure. SDK packages now include the minor version in their identifier. Android Studio 2024.1 on the development host must be updated to sync AGP 9.5; wrapper builds are independent of the installed IDE version.

TypeScript stays at 5.9.3 because openapi-typescript 7.13.0 requires TypeScript 5.x and the installed typescript-eslint release does not support TypeScript 7. Pin the compatible version rather than suppressing peer warnings. Node 24 LTS is the CI baseline.

Phase 0 exposes only process liveness and implementation status. Readiness returns 503 until real persistence and migrations exist. The portal displays actual API connectivity, without synthetic financial records or optimistic database health.

References:

- [AGP 9.5 compatibility](https://developer.android.com/build/releases/agp-9-5-0-release-notes)
- [Built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Kotlin releases](https://kotlinlang.org/docs/releases.html)
- [Specification](../../overall.md), sections 2, 16 and 20.
