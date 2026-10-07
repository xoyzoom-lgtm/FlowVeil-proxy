---
name: update-debug
description: Diagnose "the update does not install / Приложение не установлено / package invalid". Use when a user reports in-app update loops or installer errors.
---

# Update does not install

Facts to collect before changing code (the app only shows what Android allows):
- Which build the user has: Settings -> check update row shows `build N` (`versionCode = 4000749 + N`).
- Where the installed copy came from and the Android version.

What to verify on the release itself (all done once on v0030..v0206, all fine; repeat for the suspect tag):
- Download `FlowVeil-android-arm64.apk`, compare SHA-256 with `SHA256SUMS.txt`.
- The v2 signature digest matches the file, the signing certificate is the same as in older releases
  (`5001fb9d...0219`), `resources.arsc` is stored uncompressed and 4-byte aligned, `versionCode` grows by 1 per build.

Android's "package is invalid (for example corrupt)" also covers a version downgrade, so ask for the installed `build N` first.
In-app flow (`CheckUpdateViewModel`): download -> SHA-256 check -> same-signer check -> manifest check -> installer.
Any rejection must stay visible on the dialog (`updateError`), never only in a toast.
Releases carry no `FlowVeil-manifest.json.sig` until the owner signs it (OWNER-TODO), so the "install anyway?" question appears every time.
