# Provito

A private, mobile-first web browser for Android. Powered by **Brave Search**,
Mozilla's independent **GeckoView engine** (the Firefox engine, not the system
WebView), strict tracking protection, and an unsafe-site shield — with **no
telemetry, no history, no accounts, and no tracking of any kind**.

## Privacy features

| Feature | How it works |
|---|---|
| Ad + tracker blocking | GeckoView's strict Enhanced Tracking Protection blocks trackers, cryptominers, fingerprinters and social trackers at the engine level, plus query-parameter stripping and cookie purging. |
| Brave Search | Default engine for everything typed in the omnibox and on the home page. |
| No history | No browsing history database, no session persistence — tabs live only in memory. |
| No cookies that follow you | Third-party cookies are permanently disabled; first-party cookies are wiped on exit (default on). |
| Hardened engine | Private sessions (no disk writes), no autofill or login saving, `file://` limited to the app's own assets, popups open as tabs instead of windows, permissions denied by default. |
| Permission lockdown | Geolocation, camera, and microphone requests are denied automatically. |
| Fingerprint resistance | GeckoView fingerprinting protection (normal + private browsing), Global Privacy Control, generic mobile user agent (no device model), autofill disabled. |
| Data wipe on exit | Cookies, caches, and histories cleared when the app is destroyed (toggleable in the menu). |
| Auto-updates | Checks the public GitHub Releases API (anonymous GET) for a newer release and asks: *"Would you like to update? This update has: ..."* with the release notes, then downloads and installs the APK. Opt-out in the menu. |
| Onion mode (IP hiding) | Routes all browsing over the free, open-source Tor network via the [Orbot](https://github.com/guardianproject/orbot) app - sites see a Tor exit IP, never yours. Remote DNS via Tor, so no DNS leak. A custom trusted proxy can be configured instead. We deliberately do not ship random "free VPN API" providers: a free anonymous VPN would still see your traffic. |
| Unsafe-site shield | Every navigation is checked locally against **six open-source lists**: Phishing Army (extended), malware-filter phishing-filter, malware-filter URLhaus (abuse.ch mirror), OpenPhish free feed, Spam404, and stamparm/blackbook - plus a bundled offline starter list. Known malware sites are hard-blocked with no bypass; known phishing sites get a full-screen warning and can only be opened after clicking "I continue to unsafe content" - which remembers that site for the session, in RAM only, never persisted. |
| No network calls by the app itself | The only outbound requests Provito makes are to GitHub (release checks) and the unsafe-site list sources - both opt-in/opt-out, both anonymous, and routed through the active proxy (Tor) when enabled. |

## Project structure

```
app/src/main/
├── java/com/provito/browser/
│   ├── MainActivity.kt     # Browser shell, tabs, GeckoSession delegates
│   ├── Engine.kt           # GeckoRuntime + content-blocking configuration
│   ├── ProxyManager.kt     # Tor/Orbot and custom proxy (Gecko prefs)
│   ├── UnsafeSites.kt      # Six-source unsafe-site shield
│   ├── Updater.kt          # GitHub Releases update flow
│   ├── SplashView.kt       # Startup animation
│   └── SetupView.kt        # First-run wizard
├── assets/
│   ├── home.html           # Start page (Brave search)
│   ├── tabs.html           # Tab switcher (provito:// actions)
│   ├── settings.html       # Settings (provito:// actions)
│   └── unsafe.html         # Blocked-site warning
└── res/                    # Layouts, icons, menu
```

## Build & run

Requirements: Android Studio (Koala or newer) or command-line SDK with
platform 35.

```bash
# Android Studio: File > Open > Provito, then Run

# or CLI:
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

## Using it

- Type a query in the omnibox -> **Brave Search**. Type a URL -> goes there.
- Bottom bar: back / forward / home / refresh / tabs / menu.
- Shield icon (top left) -> live privacy stats.
- Menu -> update unsafe-site lists, toggle auto-updates, clear data, wipe on exit.

## Releasing

Tag a release and CI does the rest (`.github/workflows/release.yml`):

```bash
git tag v1.0.1
git push origin v1.0.1
```

The workflow builds one APK per ABI (arm64-v8a, armeabi-v7a) with the tag as
the version, verifies each archive and signature, publishes them with a
`SHA256SUMS` file, and re-downloads the assets to confirm they match. The app
then auto-detects the release on next launch and downloads the APK matching the
device's ABI.

APKs are signed with a persistent release key stored as repo secrets
(`PROVITO_KEYSTORE_B64` etc.) so Android accepts in-place updates between
releases. Note: pre-v1.1.0 builds were debug-signed by throwaway CI
keystores; you need to uninstall once if you installed one of those, after
which every future release updates cleanly.

## Honest disclosure

This app is **vibecoded**. It was built end-to-end with an AI coding agent
(opencode) from prompts, not from a human hand-writing every line. We are not
hiding it - honesty beats marketing. That means:

- Code was generated quickly and should be reviewed like any other code.
- Security claims are best-effort, not audited. If you find a hole, open an
  issue or PR.
- If you want to improve it, you are welcome - it is fully open source (MIT).

## Security notes / known limitations

- The rendering engine is **GeckoView 140 (Firefox ESR)**, bundled in the APK,
  so pages render with Mozilla's engine rather than the device's WebView.
- APKs are large (arm64 ~190 MB, armv7 ~150 MB) because each bundles the
  engine's native code. One APK per ABI is published; the updater picks the
  matching one. `SHA256SUMS` is attached to every release.
- Unsafe-site downloads are plain anonymous HTTPS GETs; there is no
  cryptographic signature on the list content (a compromised source could
  inject bad rules, but cannot execute code).
- Provito's own pages use `provito://` navigation instead of a JavaScript
  bridge, so no JS interface is exposed to web content.
- Update APKs are downloaded from GitHub Releases over HTTPS and installed by
  Android, which rejects any APK not signed with Provito's release key. The
  in-app updater does not verify `SHA256SUMS` itself; check it manually if you
  download an APK by hand.
- Cleartext HTTP browsing is allowed (like Chrome) so legacy sites work; HTTPS
  is always preferred and mixed content is never allowed.
- Unsafe-site bypass choices live in RAM only and reset when the app dies.

## License

MIT - see [LICENSE](LICENSE).
