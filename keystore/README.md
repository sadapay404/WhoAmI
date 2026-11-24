# WhoAmI CI signing keystore

`whoami-ci.jks` is the app's **stable sideload identity**. Every build — GitHub
Actions and local — signs with it so new APKs always update an existing install
in place (no uninstall between versions).

- Alias: `whoami`
- Store/key password: `whoami2026` (also in `app/build.gradle.kts`)
- SHA-256: `BB:A8:29:58:7C:9D:D6:FB:67:71:1F:3B:C8:AA:8A:BF:5B:E7:56:CE:26:7F:A8:16:61:22:9F:F1:C1:CE:F2:F6`
- Valid until: 2054-04-11

This is a personal sideload key living in a public repo — fine for a
one-user app distributed by direct APK download. **It is not a Play Store
upload key.** If you ever publish to the Play Store, generate a separate key
and keep it out of git.

**Back this file up** (e.g. copy into Google Drive). Losing it means the next
build can't update your installed app and you'd need one uninstall to re-baseline.
