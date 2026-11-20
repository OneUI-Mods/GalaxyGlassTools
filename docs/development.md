# Development

Use Android SDK 37.2 with Build Tools 37.0.0. The Gradle wrapper pins the build toolchain. Kotlin sources and the libxposed API 102 entry and scopes live under `app/src/main`.

Run `./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease`. Supply `GALAXY_GLASS_SIGNING_PROPERTIES` with the path to a local signing properties file for the signed release. Keep signing material outside Git. Use `apksigner verify --print-certs` and `zipalign -c -P 16 4` to verify the resulting release APK.

The old apps export their last glass settings to the release certificate of Galaxy Glass Tools. The new app owns the settings and writes imports once. Old applications keep their non-glass hooks and private configuration. The recording picker tags its native dialog so the separate theming module can style it in the same process.

[Rendering and review notes](glass-appearance.md)

The release APK bundles the Android 17 DexKit profiles as `assets/oneui-dexkit-profiles.json`. The profile digest in `DexKitConfig` identifies the cache generation. On the reviewed BZI9 stock SystemUI and Home APKs, the module retains the verified native class names and signatures even if a semantic search returns an unresolved entry. Updated APKs use the discovery result.

## Application scope

SystemUI, One UI Home, and Samsung Capture use dedicated adapters in the owner profile. API 102 dynamic scope includes system packages with Activities and the supported Samsung keyboard service. It includes updated system apps. For managed profiles, the common adapter requires a separate per-profile scope in Vector and waits for the profile to unlock. Hook processes read appearance settings from the framework's remote preferences.

The first Activity or the supported IME service initializes the common renderer. The Activity class loader is checked when it first appears, including components in isolated APK splits. The native traversal works in batches and continues into child controls. System bar drawing clips out surfaces already styled by the common renderer and covers the caption inset when Android supplies one. The WindowManager Shell layouts cover the separate DeX headers.

The common renderer styles app-owned surfaces while the screen and profile are unlocked. It rejects secure windows, password and private IME fields, and sensitive Activity or dialog types. The standard uninstall confirmation in Google Package Installer is identified by its native fragment and uninstall intent. Only its confirmation panel receives the glass finish. It uses Android window blur where the dialog theme supports it and Samsung per-view blur on Samsung's floating dialog. The renderer uses the existing material over native backgrounds and performs no screen capture. Each active app process has its own crash guard.

Launcher quick options reuse Samsung's menu crop. A separate background display list applies a single blur and tint to the crop. It is recorded again when the source, dimensions, theme or glass strength changes. Menu text, icons and their animations keep their native drawing paths.
