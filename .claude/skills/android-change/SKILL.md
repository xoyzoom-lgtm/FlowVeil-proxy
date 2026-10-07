---
name: android-change
description: Checklist for changing the Android app (Kotlin/Compose) when no Android SDK is available locally. Use before pushing any Android change.
---

# Android change checklist

There is no Android SDK in the cloud box: Kotlin/Compose compiles only in CI. So make mistakes cheap:

1. Before pushing, re-read the diff for: duplicated declarations (paste/slice errors), missing imports, `if` with an assignment as the last statement of a lambda (use braces and an explicit `: () -> Unit`), unbalanced braces.
2. Pure logic goes into small Kotlin files without Android imports so the local runner can test it:
   `scratchpad/kt/runall.sh` (275 tests last green). Add a test next to every new rule.
3. Strings: every new `R.string.*` needs both `values/strings.xml` (English) and `values-ru/strings.xml`; validate both with an XML parser.
4. Public texts never say "VPN", "обход", "блокировки", "белые списки" (technical Android names stay).
5. No Happ branding, no spoofed User-Agent, no telemetry, no accounts. Secrets and subscription URLs never go to logs.
6. Push to a `bypass-*` branch first; read the failing job log with `get_job_logs` instead of guessing.
7. Say what was not checked on a device — there is none here.
