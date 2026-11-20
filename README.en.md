# Galaxy Glass Tools

[日本語](README.md) · [Developer guide](docs/development.md)

An Android module for glass effects in Galaxy SystemUI, One UI Home and Samsung Capture. Configure the Quick Panel, notifications and lock screen, volume panel, Home folders and dock, and capture controls independently.

1. Install Galaxy Glass Tools.
2. For an existing setup, update to One UI Home Tweaks 0.21.52 and Galaxy Capture Tools 0.4.15 or later. The app imports the previous glass settings once.
3. Enable Galaxy Glass Tools in Vector or another libxposed API 102 manager and select SystemUI, One UI Home and Samsung Capture as needed.
4. Reload the selected processes and check the surface diagnostics in Galaxy Glass Tools.

The main rendering contract targets Android 17 BZI9 on the Galaxy S26 Ultra (SM-S948Q). Android 16 AZHL has a separate Samsung Capture path. The local preview is available without enabling the module.

The settings screen can reload One UI Home and Samsung Capture separately with root access. Home opens immediately. Samsung Capture starts on the next screenshot or recording. A current recording or screen share blocks its reload.
