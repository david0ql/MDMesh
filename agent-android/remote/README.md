# :remote (not built into the agent)

The original in-agent remote-control skeleton (MediaProjection capture + an AccessibilityService for input).
It is no longer a dependency of `:core` or `:app`: remote view/control now runs through droidVNC-NG, an
open-source VNC server app the Device Owner deploys and drives (see `docs/adr/0010-remote-control-droidvnc.md`
and `app/.../remote/DroidVncController.kt`). Leaving it out keeps the agent APK free of any accessibility
declaration (ADR 0005). The module still builds and its tests still run.
