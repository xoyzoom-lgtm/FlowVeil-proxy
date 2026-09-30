# FlowVeil — handoff for Claude

Read this fully before touching anything. It is the memory of the project so far.

## How to work with the owner (permanent, from the owner)
You are a blunt senior developer and a pragmatic technical partner. Goal: reliable code, save the owner's time.
1. Zero fluff: no openers ("Sure!", "Great question", "I'll help"), no routine apologies, no boilerplate wrap-ups. Start with the point, the decision or the code in the first sentence.
2. Be concise: facts, architecture, working code. No lectures.
3. No blind agreement: if the idea is a hack, over-engineering, hurts UX, risks bans/crashes or is just a bad approach, say so directly with arguments and propose an adequate alternative right away.
4. Focus on real risks: platform pitfalls (OS limits, background processes, memory leaks, version incompatibility), not trivia.
5. Tone: informal, direct, like a colleague. Russian, short.

## What FlowVeil is
- A **proxy client / traffic router** in the spirit of Happ, v2rayN, v2rayNG — **not a VPN
  service**. It has no servers; users bring a subscription or configs from their provider.
  Never call it a "VPN" in user-facing marketing text (advertising VPNs is illegal in Russia).
  Technical Android names like "VPN mode", "VPN MTU" stay as they are.
- Two apps in one repo:
  - `android/` — fork of **2dust/v2rayNG** (Kotlin, Jetpack Compose, MMKV, Xray core via libv2ray).
  - `windows/` — fork of **2dust/v2rayN** (C#/.NET 10, WPF, MaterialDesignThemes, ReactiveUI).
    The shared logic lives in `windows/v2rayN/ServiceLib`, the WPF UI in `windows/v2rayN/v2rayN`.
    `v2rayN.Desktop` (Avalonia) is upstream only and **not** built or maintained.
- Owner: Telegram **@GxoyzoomG** (https://t.me/GxoyzoomG). Talks Russian, wants **short answers
  in Russian**, casual tone. Is promoting the app publicly, so quality matters.
- Old name was "Hupp"; many internal identifiers still say `Hupp*`/`Happ*` (e.g. `HuppHomeView`,
  `HappTheme`, `HUPP_BUILD`, secrets `HUPP_KEYSTORE_*`). That is fine internally; users must never
  see "Hupp" or "Happ" as our branding.

## Repositories
- Main: **https://github.com/xoyzoom-lgtm/FlowVeil-proxy** (branch `main`, pushes go straight
  to `main`; every push builds and publishes a release). Renamed from `hupp-proxy`.
- `xoyzoom-lgtm/drugavto` — only a scratch/session repo (branch
  `claude/happ-themes-pc-android-sz6gnv` holds `flowveil-site.zip`). Not the product.

## Hard rules (from the owner, keep them)
- Do not embed the owner's own subscription URL anywhere.
- No Happ branding, logo, name look-alikes; no spoofing Happ (User-Agent etc.).
- Do not decrypt `happ://crypt…` links — tell the user only Happ opens them.
- Do not copy another app's HWID to bypass device limits.
- Never commit keystores or passwords. Signing uses repo secrets `HUPP_KEYSTORE_BASE64`
  (alias `hupp`) and `HUPP_KEYSTORE_PASSWORD`; they already exist.
- Keep GPL-3.0 credit: "Основано на v2rayNG/v2rayN".
- Every user-visible change goes into **`CHANGES.md`** (Russian, plain words). CI puts it at
  the top of the release notes. Replace its content when starting a new batch of changes
  (it describes the *next* build), don't let it grow forever.

## Versions and releases
- Build number = GitHub Actions `run_number`. Tags `v%04d` (e.g. `v0060`), title
  `FlowVeil build-N`. Android `versionCode = 749 + N` (`-PhuppBuild=N`, `BuildConfig.HUPP_BUILD`);
  Windows shows `InformationalVersion=build-N` in the title.
- `.github/workflows/build.yml`: jobs `android`, `windows`, `release` (release only if both
  succeed). `concurrency: build`, `cancel-in-progress: false` → a newer push cancels a *pending*
  run; only the newest pending one survives. Publishing retries 4× (GitHub sometimes gives 403).
- Release assets: `FlowVeil-android.apk` (universal), `FlowVeil-android-arm64.apk`,
  `FlowVeil-Setup.exe`, `FlowVeil-windows-portable.zip`, `Hupp-Setup.exe` (copy for very old
  updaters).
- In-app updaters (Android `UpdateCheckerManager`, Windows `HuppUpdater`) list
  `releases?per_page=10` and take the **highest build number**, not GitHub's "latest" flag.
- Windows CI bundles: Xray core, sing-box ≤ 1.14, wintun.dll, country flags from flagcdn
  (`FlowVeil/flags/*.png`), then Inno Setup (`windows/FlowVeil.iss`, AppId GUID must stay).
  A self-test (`windows/selftest/HuppSelfTest`) imports sample subscriptions (continue-on-error).

## Android specifics
- `applicationId = com.flowveil.app` (changed from `com.v2ray.ang` so it can live next to
  v2rayNG). Kotlin package/namespace is still `com.v2ray.ang` — do not rename packages.
- Processes: UI (default), `:daemon` (VPN/core, widgets), `:tasks`. MMKV is MULTI_PROCESS.
  `CoreServiceManager` lives in the core process — do not reference it from the UI process
  (use MMKV `CACHE_CONNECTED_SINCE` to know if connected).
- Key FlowVeil files (all under `android/V2rayNG/app/src/main/java/com/v2ray/ang/`):
  - `handler/SubscriptionFormats.kt` — loose base64, Clash YAML and sing-box JSON → share links.
  - `handler/AngConfigManager.kt` — subscription download (proxy → direct → DoH, UA retries),
    error reasons (`SubscriptionErrors`), import, new subs auto-update interval.
  - `handler/DeviceIdentity.kt` — `x-hwid` = SHA-256("flowveil:"+ANDROID_ID) first 8 bytes,
    cached in MMKV; headers x-hwid/x-device-os/x-ver-os/x-device-model.
  - `core/ConnectionWatchdog.kt` — auto failover: check every 30 s (screen on), recheck after
    5 s, online check via ya.ru/vk.com/mail.ru, parallel candidate tests, notification.
  - `service/SpeedtestConfig.kt` — one real test; custom JSON profiles are slimmed to their
    outbounds so they can run in parallel.
  - `service/RealPingWorkerService.kt` — list check, max 8 parallel, always reports a result.
  - `handler/ProxySpeedTest.kt` — 6-stream download/upload/ping through local proxy.
  - `core/SingboxBridge.kt` — TUIC v5 runs in a bundled sing-box (`libsingbox.so`, downloaded in CI
    for arm64-v8a only (armv7 dropped to keep the APK small; 32-bit phones get the "engine missing" message), step "Add sing-box engine (TUIC)", optional). Per server one
    process: SOCKS inbound on 127.0.0.1 (random port + login) → TUIC outbound; Xray sees it as an
    ordinary SOCKS outbound (`CoreOutboundBuilder.toOutboundTuic`). Live-connection bridges are
    stopped in `CoreServiceManager` (`launchCore`/`stopCoreLoop`); server tests use
    `SingboxBridge.scoped {}` (see `SpeedtestConfig.measure`) and are limited to 3 in parallel.
    Hysteria 2 needs no bridge: Xray has a native hysteria outbound. `fmt/TuicFmt.kt` parses `tuic://`.
  - Auto bypass of mobile whitelists (off by default; public texts never say "bypass"): settings/state in
    `handler/WhitelistBypass.kt` (MMKV `pref_whitelist_bypass_*`), decisions in `core/BypassController.kt`
    (called by `ConnectionWatchdog`, one `switchLock` with failover). Rule: a server is switched to only after
    an isolated test on the phone's real network (`SpeedtestConfig.measureStrict`: two hosts; the app is
    excluded from its own VPN, so its sockets use the default network), stays only after a live check
    through the profile's REAL local port (`handler/LocalProxy.kt`; custom JSON profiles have their own
    inbounds, none = core delay test), and a search that ends unverified rolls back (`net/BypassSearch.kt`,
    pure + tested). Candidates: `net/BypassClassifier.kt` (pure scoring: name markers, transport, mask SNI,
    history; all weights in `BypassData`, version field) via `handler/BypassRating.kt`; auto = STRONG+LIKELY
    only (WEAK if "try the rest"), manual = the user's list; history per server fingerprint + operator
    MCC/MNC bucket, only from mobile-network tests (`handler/BypassHistory.kt`, `net/BypassHistoryLogic.kt`).
    Decision log for developer mode: `handler/BypassLog.kt`. Russian servers ARE allowed for bypass, never for
    the return to Wi-Fi. Network card and log are developer-mode only. `BatteryOptimization.kt` asks for the
    battery exemption (OEM ROMs kill the background watchdog).
  - `handler/SubscriptionReminders.kt`, `SubscriptionUpdater.kt` (interval for all subs),
    `DirectSites.kt` (user + ~80 default Russian domains as one locked routing rule),
    `FavoriteServers.kt`, `ServerCountry.kt` (skip Russian servers for "Best"/failover),
    `DevMode.kt` (hides technical UI), `ui/main/WhatsNewDialog.kt` (bump `CONTENT_VERSION`
    when the `whats_new_items` list changes).
  - Widgets: `receiver/WidgetProvider.kt` (1×1) + `WidgetCardProvider.kt` (4×1).
  - Deep links: `ui/UrlSchemeActivity.kt` → `MainActivity.EXTRA_IMPORT_TEXT`
    (`flowveil://add?url=…`, `v2rayng://install-sub?url=…`).
  - Strings: English in `res/values/strings.xml`, Russian in `res/values-ru/strings.xml`.
    Every new string must be added to both.
- Settings → "Подключение": Russian sites directly, my sites directly, apps directly,
  best button, auto failover, subscription auto-update interval, reminders.
  Settings → "Для разработчиков": developer mode (off by default).

## Windows specifics
- `Directory.Build.props` has **`CheckForOverflowUnderflow=true`** and
  **`UseSystemResourceKeys=true`**: any integer overflow throws, and exception messages show
  resource keys like `Arg_OverflowException`. Be careful with casts (`(int)double`), division
  by zero in doubles, `Sum()` etc.
- `Utils.HumanFy(x)` expects **kilobytes** (divide bytes by 1024 first).
- Key FlowVeil files:
  - `v2rayN/Views/HuppHomeView.xaml(.cs)` + `v2rayN/ViewModels/HuppHomeViewModel.cs` — home
    screen, modes Proxy / TUN (sing-box, gVisor, Xray) / Local, subscription card, "Connect to
    the best" (skips Russian servers).
  - `v2rayN/Views/MainWindow.xaml(.cs)` — nav rail, settings page built in code
    (`BuildSettingsPage`), developer mode (file `guiConfigs/dev_mode`), deep link import.
  - `v2rayN/Views/StatusBarView.xaml.cs` — tray menu (style `TrayContextMenu` in `App.xaml`).
  - `ServiceLib/Handler/HuppUpdater.cs` + `v2rayN/Views/HuppUpdateView` — in-app update.
  - `ServiceLib/Handler/SubscriptionInfoStore.cs` — provider headers (traffic, expire, announce).
  - `ServiceLib/Handler/DeviceIdentity.cs` — HWID from MachineGuid hash, `guiConfigs/hwid.txt`.
  - `ServiceLib/Services/SecureDns.cs` — DoH fallback for subscription download.
  - `ServiceLib/Handler/SubAutoUpdate.cs` — one interval for all subs (`guiConfigs/sub_update_interval`).
  - `ServiceLib/Common/ElevatedTask.cs` — TUN admin rights once: first elevated start registers
    Task Scheduler task "FlowVeil (TUN)" (HighestAvailable), later restarts use it silently;
    `guiConfigs/tun_pending` carries a TUN request over the restart.
  - `ServiceLib/Services/SpeedtestService.cs` — FlowVeil adds `RunCustomRealPingAsync` (custom
    JSON profiles tested with their outbounds behind a SOCKS inbound) and an empty-batch guard.
  - `v2rayN/Common/DeepLink.cs` — `flowveil://` (installer registers the scheme in HKCU).
  - `v2rayN/Converters/HuppConverters.cs` — flags (local `flags/` first), ping text/colours.
  - `ServiceLib/Common/HappThemes.cs` — 17 colour themes (legacy "Happ · " prefix migrated).
- UI text on Windows is hard-coded Russian (not localized).

### Windows navigation and pairing (FlowVeil Pair)
- `MainWindow.Nav.cs`: expandable panel (200/72 px, state in `guiConfigs/nav_collapsed`, auto-collapse < 1000 px, Ctrl+1..5).
  Pages are hosted in `MainWindow.xaml` ContentControls: `AddPageView`, `StatsPageView`, `LogsPageView` (code-built like the settings page).
- «Добавить» → «С телефона» = `PairPanel` + `ServiceLib/Handler/Pair*.cs`: session (sid/token 128 bit, key 256 bit, 6-digit code, 5 min, single use,
  5 wrong attempts lock), listener on one private LAN address only while the session lives, GET/POST only, body ≤ 64 KB, AES-256-GCM
  (AAD = sid). Android side: `net/PairProtocol.kt`, `handler/PairClient.kt`, `ui/main/PairSendDialog.kt`.
  The shared crypto vector lives in `PairTests.Crypto_SharedVector` and `PairProtocolTest.sharedVector`: change both or neither.
- Never add decrypting of foreign encrypted links, foreign branding or auto-connect after a pairing; adding always needs confirmation on the PC.

## Website
- `docs/index.html` (+ `logo.png`, `banner.png`) — single-file animated landing page
  (routing diagram hero, rules, modes, download, FAQ). Positioned as a traffic router on
  Xray/sing-box. GitHub Pages not enabled yet (owner must: Settings → Pages → main /docs), owner
  may host it on his own domain.

## Building locally
- Android: JDK 21, Android SDK platform 37 + build-tools 37.0.0, NDK 29.0.14206865. Before
  gradle: build `libhev-socks5-tunnel` (`android/compile-hevtun.sh`, hev-socks5-tunnel commit
  64cc609f…) into `V2rayNG/app/libs`, and download
  `https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.9.9/libv2ray.aar` to
  `V2rayNG/app/libs/`. Then `cd android/V2rayNG && ./gradlew assemblePlaystoreRelease
  -PhuppBuild=<N>` with the `android.injected.signing.*` properties (see the `android` job in
  `build.yml`). A different signing key cannot update an installed APK — for real releases
  always let CI sign with the repo secrets.
- Windows: .NET 10 SDK, `windows/BUILD-FlowVeil.bat` or
  `dotnet publish windows/v2rayN/v2rayN/v2rayN.csproj -c Release -r win-x64 -p:SelfContained=true -o FlowVeil`,
  then copy cores as in `build.yml`, installer via Inno Setup 6 `windows/FlowVeil.iss`.
- Check CI after every push: `https://github.com/xoyzoom-lgtm/FlowVeil-proxy/actions`.

## Diagnostics, updates, subscriptions, failover (what exists now)
- **"Why does it not work?"** (Android `net/Diagnosis.kt` + `handler/DiagnosticsRunner.kt`, Windows `Handler/Diagnosis.cs` + `DiagnosticsRunner.cs`): ordered probes (network, clock, DNS, subscription, server, end-to-end through the core) → one cause from the shared taxonomy (`DiagCause` ids are identical on both platforms) → plain-language text + what to do. The report never contains links, keys, addresses or network names (`ReportMask`). A new cause needs: enum + both texts (ru/en) + test.
- **Update notifications** (`net/UpdateLogic.kt` ↔ `Handler/UpdateLogic.cs`, mirrored; `UpdateNotifier`): check on start and every ~6 h, ETag/304, one notice per version + one reminder after 3 days, "Later"/"Skip this version", off-switch in settings. Only request the app makes on its own; GitHub only; no identifiers. Nothing is installed without the user's "Update"; the download is checked against the release's `SHA256SUMS.txt` (written by the `release` job in `build.yml`).
- **Subscriptions on Windows**: `SubsLogic` (pure: health, filter, best, failover order), `HuppHomeViewModel.Subs.cs` (chips strip, grouped list, state in `guiConfigs/subs_ui.json`). "Best" = best inside the current filter; Russian servers, expired/disabled/out-of-traffic subscriptions are never picked. The Add page refreshes the VM's subscription snapshot (a stale snapshot was the cause of "second subscription not selectable").
- **Failover**: Android `core/ConnectionWatchdog.kt` (+ `net/FailoverPlan.kt`, setting "switch between subscriptions"), Windows `HuppHomeViewModel.Failover.cs` (+ `Handler/Failover.cs`). Two failed checks in a row while the machine itself is online → test candidates (same subscription first, then other usable ones) → switch to the fastest that works → tell the user. Not verified on real devices.
- **Invite links**: `flowveil://add?url=<encoded>&name=<title>` (`net/InviteLink.kt` ↔ `Handler/InviteLink.cs`); the site generates link, QR and a printable sheet in the browser. Provider headers shown in the app: `profile-title`, `subscription-userinfo`, `announce`, `support-url`, `profile-web-page-url` (only http(s)/tg:// are opened).
- **Backup**: Windows zips the whole `guiConfigs` folder (so every state file is included); Android backs up all MMKV stores.
- **Real-device checks** live in `TESTING.md` (sections 0–7). Nothing in this list is verified on a device unless the owner says so; report "done / not done / not verified" honestly.

## What we deliberately do not do
- No telemetry, accounts, ads, or server-side components (no stats for providers, no relay). No silent auto-install of updates.
- Rule profiles ("Профили правил") are deferred (stashed as `profiles-deferred`), not dropped.
- Tiles mode for the Windows server list (virtualization risk with 500+ servers); colour labels for subscriptions.
- Windows installer is not signed yet: see `SIGNING.md` (SignPath Foundation is the free route; needs the owner's application).
- Commit messages carry no AI attribution trailers (owner's request); older history still has them.

## Open issues / ideas
- Real screenshots for the site: drop files into `docs/assets/shots/` and list them in `shots.json` (the section stays hidden until then).
- Windows UI is not localized; no macOS/Linux build of the FlowVeil UI.
- Data folder is still `%LocalAppData%\v2rayN` and the subscription User-Agent is `v2rayNG/1.10.5` (changing either may break providers/upgrades; owner has not decided).
