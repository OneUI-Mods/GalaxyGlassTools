# Development

Use Android SDK 37.2 with Build Tools 37.0.0. The Gradle wrapper pins the build toolchain. Kotlin sources and the libxposed API 102 entry and scopes live under `app/src/main`.

Run `./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease`. Supply `GALAXY_GLASS_SIGNING_PROPERTIES` with the path to a local signing properties file for the signed release. Keep signing material outside Git. Use `apksigner verify --print-certs` and `zipalign -c -P 16 4` to verify the resulting release APK.

The old apps export their last glass settings to the release certificate of Galaxy Glass Tools. The new app owns the settings and writes imports once. Old applications keep their non-glass hooks and private configuration. The recording picker tags its native dialog so the separate theming module can style it in the same process.

[Rendering and review notes](glass-appearance.md)
