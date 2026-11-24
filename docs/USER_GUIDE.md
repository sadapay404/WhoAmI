# WhoAmI — User Guide (one page)

## Install

1. Open the repo's **Releases** page on your phone.
2. Download **WhoAmI-latest.apk** (or a tagged release like `WhoAmI-v1.0.apk`).
3. Open the file → Android asks to allow installs from your browser/file manager → Allow → Install.

> Builds are debug-key signed, which is normal for a personal sideloaded app. Android
> 14+ may warn "unsafe app" because it wasn't installed from Play — tap *Install anyway*.

## First-time setup (about 3 minutes)

The app opens a 7-step wizard. Each step explains itself; the short version:

| Step | What you do |
|---|---|
| Camera & microphone | Tap **Allow** on both Android prompts |
| Display over other apps | Find WhoAmI in the list → toggle **ON** (this lets the quiet check appear at unlock) |
| Usage access | Find WhoAmI → allow usage access (this is how it notices WhatsApp opening) |
| Keep WhoAmI awake | Choose **Unrestricted** battery (Motorola: Settings → Apps → WhoAmI → Battery) |
| Enroll your face | Look at the camera; it takes ~8 photos while you slowly turn your head. Good light. Sunglasses off. |
| Back up wallpaper | Tap **Back up now** — this is the wallpaper that comes back when *you* return |
| You're protected | Tap **Enter WhoAmI** |

## Daily use

- **Lock and unlock your phone.** The home screen's RECENT CHECKS log shows every
  decision with a score: `MATCH` = it believed you, `NO_MATCH` = capture ran.
- **Open WhatsApp** — same check happens there (toggleable in settings).
- **When a capture happens:** front/back photos, a 5-second video of each camera
  (back video with sound), and microphone audio until you lock the phone. Your
  intruder photo becomes the wallpaper.
- **When YOU unlock with your face:** wallpaper restores instantly, a notification
  appears → tap it → the gallery shows everything captured.

## Testing it works

1. Home screen → **Test face check** → look at camera → expect `MATCH — it's you`.
2. Home screen → **⚠ Simulate intruder** → wait ~20s → **View captured sessions**
   → you should see front.jpg, back.jpg two videos and audio. Lock your phone,
   unlock with your face, and the "wallpaper restored" notification should appear.

## Tuning

- **Too many false alarms** (it captures *you* sometimes): raise the **threshold** (+)
  in 0.05 steps. Normal range 0.50–0.70.
- **It fails to recognize you** (usually dark rooms): lower it (−), and/or turn
  **strict mode** OFF so unclear faces are allowed through instead of captured.
- **Re-enroll** after haircuts/beard changes that matter, new glasses, etc.

## If captures stop happening

1. Open the app — the HEALTH section shows what's broken; each failure has a
   one-tap **fix** button.
2. Long-press the HEALTH headline for Motorola-specific battery instructions.
3. Check RECENT CHECKS — if `guard started` appears after reboots but nothing else,
   the face check isn't launching (overlay permission got reset).

## Privacy

- Everything stays on your phone: `filesDir/sessions/` inside the app sandbox.
  No internet permission is even declared — the app *cannot* send data anywhere.
- Uninstalling deletes all captures. To remove captures without uninstalling:
  gallery → open sessions individually, or clear app storage in Android settings.
- Keeps the newest **50 sessions**; older ones auto-delete.

## Uninstall

Settings → Apps → WhoAmI → Uninstall. (Your wallpaper stays whatever it was last
set to — set it back manually from Settings → Wallpaper.)
