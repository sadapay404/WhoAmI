# WhoAmI

**Quiet intruder capture for your phone.** When your Android phone unlocks (or WhatsApp opens) and the face looking back is *not* you, WhoAmI silently grabs a front photo, back photo, 5s front video, 5s back video with audio, and keeps recording audio until you lock the screen again. The front photo becomes your wallpaper until *you* unlock next — then your real wallpaper snaps back, a notification appears, and tapping it opens everything that was captured.

## Status

6-phase build in progress. See **[docs/MASTERPLAN.md](docs/MASTERPLAN.md)** for the full plan and progress checkboxes.

- **Phase 1** — project skeleton + automatic APK builds via GitHub Actions ✅

## Install (debug builds)

1. Open the **[Releases](../../releases/latest)** page of this repo
2. Download `WhoAmI-latest.apk` to your phone
3. Open it → allow "install unknown apps" for your browser/file manager → Install

## Build yourself

```bash
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17. CI builds every push automatically.

## Privacy

Captures never leave the phone (Phase 1–6 scope). Stored only in the app's private folder.
