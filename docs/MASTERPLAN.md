# WhoAmI — Masterplan

> **Goal (your words):** Every time my phone unlocks OR WhatsApp opens, and it's NOT me —
> capture a front photo, back photo, 5s front video, 5s back video with audio, and audio for
> the whole session until I lock the phone again. The front photo becomes my wallpaper until
> I (the real me) unlock again — then the wallpaper instantly restores, a notification pops,
> and tapping it shows everything that was captured.

---

## 1. Decisions already made (from your answers)

| Question | Your answer |
|---|---|
| Phone | **Android — Motorola G Stylus 2025** (stock-ish Android, good news: less aggressive battery killing than Samsung/Xiaomi) |
| How we tell it's you | **Quiet face check** — a hidden camera photo at unlock, matched against your enrolled face. Works even if someone knows your PIN. |
| Where captures are stored | **On the phone only** — inside a private folder no gallery app or file manager (without root) can see. Works 100% offline. |

## 2. Decisions I'll make by default when you're offline

These are locked unless you tell me otherwise later:

- **App name:** WhoAmI · **Package:** `com.whoami.guard`
- **Build target:** Android 10 → Android 15 (your phone is Android 15)
- **If the face check can't decide** (dark room, weird angle, face covered): **let the person in** (no capture). Rationale: the phone is *already unlocked* at that point — failing "closed" would just spam you with false alarms. There will be a settings toggle to flip this to "always capture."
- **WhatsApp means:** both normal WhatsApp and WhatsApp Business.
- **Every capture session** = one folder: `sessions/2026-11-24_14-30-00/` with photos, videos, audio, and a small info file.
- **Keep the last 50 sessions** (oldest auto-deleted) so storage never fills up.
- **Deliverable:** a downloadable APK from this repo's GitHub **Releases** page — sideload onto your phone (you'll tap "install" once and allow unknown sources).

---

## 3. How it works (plain English)

```
┌─────────────────────────────────────────────────────────────────┐
│  GUARD SERVICE  (always-on, quiet, low battery)                 │
│  • watches for: phone unlocked  |  WhatsApp opened              │
│  • on trigger → runs the FACE CHECK                             │
└───────────────────────────┬─────────────────────────────────────┘
                            │
              ┌─────────────▼──────────────┐
              │  Is this face MINE?        │
              └─────┬──────────────┬───────┘
                 YES│              │NO / someone else
                    ▼              ▼
            do nothing      ┌──────────────────────────────────┐
            (if wallpaper   │  CAPTURE MODE                     │
             was swapped,   │  1. front photo  (from the check) │
             restore it +   │  2. back photo                    │
             notify me)     │  3. 5s front video                │
                            │  4. 5s back video + audio         │
                            │  5. session audio → until lock    │
                            │  6. front photo → my wallpaper    │
                            └──────────────┬───────────────────┘
                                           │ phone locks
                                           ▼
                            session saved, recording stops
                                           │
                            ┌──────────────▼───────────────────┐
                            │ I unlock again + face matches     │
                            │ → wallpaper restored INSTANTLY    │
                            │ → notification: "Tap to view"     │
                            │ → gallery of photos/videos/audio  │
                            └──────────────────────────────────┘
```

### The pieces

1. **Guard Service** — a tiny always-running service. Listens for "phone unlocked" and
   "WhatsApp came to the front". Uses almost no battery (it's just listening, no camera).
   Restarts itself after reboot.

2. **Face Check screen** — for ~1 second after a trigger, a see-through screen grabs one
   front-camera photo and compares it to your enrolled face, then disappears. You'll see a
   brief flicker + the system's green privacy dot — **Android forces that dot to appear; it
   cannot be hidden.** That's the honest limitation of every camera app on Android 12+.

3. **Capture Mode** — only runs when the face is NOT yours. Grabs the photo set, both
   videos, then keeps the microphone alive (with a quiet foreground notification — Android
   requires one while recording) until the screen turns off.

4. **Wallpaper swap** — your captured front photo is set as wallpaper the moment capture
   starts. The *original* wallpaper is saved during setup and restored the instant you
   unlock with your own face.

5. **Gallery** — inside the app: every session with its photos, playable videos, playable
   audio. Opened by tapping the "capture complete" notification (or from the app).

### Setup wizard (first launch, ~3 minutes)

You'll tap through, in order:

1. Camera + Microphone permission
2. Notifications permission
3. **"Display over other apps"** — this is what lets the face-check screen appear right at
   unlock. (Android blocks apps from popping up after unlock without it.)
4. **Usage access** — lets the app notice WhatsApp opening
5. **Battery: set to Unrestricted** — one switch, I'll deep-link you to the exact screen
   (Motorola: Settings → Apps → WhoAmI → Battery → Unrestricted)
6. **Enroll your face** — look at the phone while it takes ~8 photos from slightly
   different angles (turn your head a little as prompted)
7. **Save current wallpaper** as the "default" to restore later
8. **Start protection** → done

---

## 4. Honest limitations (read this once)

| Limitation | Why | Mitigation |
|---|---|---|
| Green privacy dot during checks/recording | Drawn by Android itself, unhideable | None possible — it's OS-level |
| Brief flicker at each unlock/WhatsApp open | The face check must run somewhere visible | Keep it under ~1s; can be tuned |
| A persistent quiet notification while audio records | Android law for microphone use | Low-priority, innocuous wording |
| Thief can factory-reset → evidence gone | You chose local-only storage | (If that worries you later, cloud backup is a Phase-later option) |
| Someone who knows about the app can force-stop it | Android allows any app to be stopped | App re-arms on reboot + on next manual open; can't fully beat a determined attacker |
| Face check may fail on you in the dark | Camera quality | Default = allow through; toggle exists |
| No app is 100% reliable on every Android | OEM quirks | Phase 6 includes an on-device test checklist we run together |

---

## 5. The 6-phase plan

> Progress checkboxes live here. If you're offline, I move through them in order and
> leave notes in §6.

### Phase 1 — Skeleton + build machine ✅ (2026-11-24)
**Goal:** an APK exists and installs on your phone.
- [x] Kotlin/Gradle project structure in this repo
- [x] GitHub Actions workflow: every push → builds a debug APK automatically
- [x] APK attached to a rolling GitHub Release (`latest-build`) so you can download it on your phone
- [x] Placeholder screen showing app name + version
- **You're needed:** download the APK, install it, confirm it opens. (Everything else can proceed without you.)
- **APK:** https://github.com/sadapay404/WhoAmI/releases/tag/latest-build (`WhoAmI-latest.apk`, ~12.6 MB)

### Phase 2 — Setup wizard + permissions + face enrollment ✅ (2026-11-24)
**Goal:** app is fully configured on your phone and knows your face.
- [x] Step-by-step setup screen with all steps from §3
- [x] Deep-links to the exact settings screens for overlay / usage / battery
- [x] Face enrollment (multi-angle capture → face print saved privately)
- [x] Current wallpaper backed up as "default"
- [x] "Test face check" button (runs the check and tells you MATCH / NO MATCH + score)
- **You're needed:** run setup on the phone, enroll your face, tap Test a few times (daylight + a dark room).

### Phase 3 — Detection engine ✅ (2026-11-24)
**Goal:** the app correctly decides "me" vs "not me" on real unlocks and WhatsApp opens.
- [x] Guard Service listening for unlock + WhatsApp foreground
- [x] Quiet face-check flow wired to both triggers
- [x] Decision log (visible in-app: "14:32 — unlocked — MATCH — allowed")
- [x] Settings: allow/deny-on-uncertain toggle, WhatsApp check on/off, master arm switch
- **You're needed:** live with it 1–2 days, confirm the log matches reality.

### Phase 4 — Intruder capture ✅ (2026-11-24)
**Goal:** full capture chain fires when a face doesn't match.
- [x] Front photo, back photo, 5s front video, 5s back video with audio
- [x] Session audio from trigger → screen off
- [x] Everything saved into a private session folder + info file
- [x] Front photo set as wallpaper on capture start
- [x] Storage cap: keep last 50 sessions
- **You're needed:** tap "Simulate intruder" and verify all 5 media items appear.

### Phase 5 — Owner's return (wallpaper restore + notification + gallery) ✅ (2026-11-24)
**Goal:** the full loop from your description works end-to-end.
- [x] On your unlock + face match: wallpaper restored instantly, notification posted
- [x] Notification tap → session gallery (photos, videos, audio playback)
- [x] In-app session list + manual "Restore wallpaper now" button
- [x] Session audio stops cleanly on lock; no orphan recordings
- **You're needed:** full dress rehearsal: simulate intruder → lock → unlock as you → tap notification.

### Phase 6 — Hardening, polish, release ✅ (2026-11-24)
**Goal:** daily-driver ready.
- [x] Motorola battery-restriction auto-detector + fix-it guide inside the app
- [x] Threshold tuning from home screen (+/−, 0.30–0.90; default 0.55)
- [x] Auto-rearm after reboot (BootReceiver) + on every app open (best effort)
- [x] Tagged release `v1.0` → CI builds → GitHub Release with versioned APK
- [x] Written one-page user guide (`docs/USER_GUIDE.md`)
- **You're needed:** final install of `v1.0`, sign-off checklist.

---

## 6. Offline progress notes

*(I append here each time I finish a chunk while you're away.)*

- **2026-11-24 — Plan created.** Architecture locked per §2–§3. Starting Phase 1.
- **2026-11-24 — Phase 1 done.** First CI build green (2m15s). APK attached to release `latest-build`.
- **2026-11-24 — Phase 2 done.** Setup wizard, FaceEngine (ML Kit + MobileFaceNet, fully offline),
  enrollment, wallpaper backup, test-check screen. Debugging notes: CI logs host is blocked from
  this sandbox — build errors now surface as workflow annotations (readable via API).
  Fixed: 4 Java-style ternaries in Kotlin, a suspend/non-suspend mismatch, an enrollment
  latch deadlock (same-executor submission).
  Next: Phase 3 — Guard Service + quiet face check on unlock and WhatsApp.
- **2026-11-24 — Phase 5 done.** GalleryActivity (sessions → items → FileProvider open),
  notification tap opens gallery directly, restore-wallpaper escape hatch, View-captures button.
- **2026-11-24 — Phase 6 done. Health checks + threshold tuner + user guide + `v1.0` release.**
  All six phases complete. `v1.0` tag → CI release job green → APK on the Releases page.
  Final state: setup wizard → face enrollment → quiet checks on unlock/WhatsApp →
  full capture (2 photos, 2 videos, session audio, wallpaper swap) → owner unlock restores
  wallpaper + notification → gallery. Everything 100% on-device (no INTERNET permission).
  **Waiting on you:** install `WhoAmI v1.0` (or WhoAmI-latest.apk), run setup, do the
  dress rehearsal in Phase 5's checkbox, and report any false alarms so we can tune the
  threshold from your real log.
- **2026-11-24 — Phase 4 done.** CaptureService (own LifecycleOwner for headless CameraX),
  SessionStore (50-session cap, meta.json), wallpaper swap at capture start, simulate button.
  Fixed: missing `import android.app.Service`; release step moved to `gh` CLI after
  softprops action 404'd on asset replacement.
  Next: Phase 5 — gallery, notification tap-through, restore loop.

---

## 7. Permission glossary (what you'll see and why)

| Permission Android asks for | Why we need it |
|---|---|
| Camera | photos + videos of the intruder |
| Microphone | session audio + video sound |
| Notifications | the "tap to view captures" alert |
| Display over other apps | show the quiet face-check right at unlock |
| Usage access | notice when WhatsApp opens |
| Ignore battery optimizations | keep the guard alive in the background |

No permissions are requested until the setup wizard walks you through them, one at a time,
each with a plain-English reason.
