# Development

Use Android SDK 37.2 with Build Tools 37.0.0. The Gradle wrapper pins the build toolchain. Kotlin sources and the libxposed API 102 entry and scopes live under `app/src/main`.

Run `./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease`. Supply `GALAXY_GLASS_SIGNING_PROPERTIES` with the path to a local signing properties file for the signed release. Keep signing material outside Git. Use `apksigner verify --print-certs` and `zipalign -c -P 16 4` to verify the resulting release APK.

The old apps export their last glass settings to the release certificate of Galaxy Glass Tools. The new app owns the settings and writes imports once. Old applications keep their non-glass hooks and private configuration. The recording picker tags its native dialog so the separate theming module can style it in the same process.

[Rendering and review notes](glass-appearance.md)

The release APK bundles the Android 17 DexKit profiles as `assets/oneui-dexkit-profiles.json`. The profile digest in `DexKitConfig` identifies the cache generation. On the reviewed BZI9 stock SystemUI and Home APKs, the module retains the verified native class names and signatures even if a semantic search returns an unresolved entry. Updated APKs use the discovery result.

## Application scope

The three original package entries keep their dedicated adapters. API 102 dynamic scope includes regular owner-profile system packages with activities, including updated system apps. The settings screen sends the eligible list through the framework's scope service. Hook processes read only the shared appearance settings from the framework's remote preferences.

The common adapter styles bounded native controls and supported Material Compose bars, with additional resource IDs verified against the Samsung APKs. Apps with custom UI can use the visible system-bar regions. The first Activity starts the renderer so pure service processes do not register visual hooks, read configuration or initialize the material. It reuses the existing material above native backgrounds and uses the native window policy. It does not start an app or use screen capture in these hosts. View-level and Compose adapters only style interactive, application-owned, non-secure windows. Each active app process has its own crash guard.
