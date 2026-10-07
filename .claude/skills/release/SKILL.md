---
name: release
description: Ship a verified change to main as a release. Use when the owner says "в релизы", "выгружай" or asks to publish a build.
---

# Release a change

A push to `main` builds both apps and publishes a release (tag `v%04d` = run number), and phones then offer the update.
So nothing reaches `main` before it has built green somewhere else.

1. Work in a branch named `bypass-<topic>` (the workflow builds Android + Windows + tests on `bypass-*` and does not publish).
2. Run the cheap checks first (see `android-change`).
3. Push the branch, wait for the run on that exact commit (`tests`, `android`, `windows` all `success`; `release` is `skipped`).
4. Only then fast-forward main: `git fetch origin main && git merge-base --is-ancestor origin/main HEAD && git push origin <branch>:main`.
   If main moved, merge it into the branch and go back to step 3.
5. Wait for the run on `main`; confirm `release` is `success` and read the new tag from the releases API. Report that tag.
6. A change that only touches `docs/**` builds nothing (paths-ignore) — safe to push straight to main, no release.

Rules: commits carry no AI attribution lines (owner's request). Never touch `HUPP_KEYSTORE_*` or the signing alias.
`CHANGES.md` describes only the next build: replace its content with this batch, plain Russian words.
