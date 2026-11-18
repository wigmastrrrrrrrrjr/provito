# Provito

A private, mobile-first web browser for Android. Powered by **Brave Search**,
built-in **ad and tracker blocking**, and hardened WebView settings — with **no
telemetry, no history, no accounts, and no tracking of any kind**.

## Privacy features

| Feature | How it works |
|---|---|
| Ad + tracker blocking | Local rule engine (`Blocker.kt`) checks every request in `shouldInterceptRequest` against a bundled open-source blocklist. Optionally updates from the MIT-licensed [StevenBlack/hosts](https://github.com/StevenBlack/hosts) list — an anonymous GET, opt-in. |
| Brave Search | Default engine for everything typed in the omnibox and on the home page. |
| No history | No browsing history database, no session persistence — tabs live only in memory. |
| No cookies that follow you | Third-party cookies are permanently disabled; first-party cookies are wiped on exit (default on). |
| Hardened WebView | Cache disabled, form data and password saving off, mixed content never allowed, popups/silent window opens blocked, `file://` and `content://` access denied. |
| Permission lockdown | Geolocation, camera, and microphone requests are denied automatically. |
| Fingerprint resistance | Generic user agent (no device model), `flagNoPersonalizedLearning` on the search bar, autofill disabled, Safe Browsing telemetry-free (not enabled). |
| Data wipe on exit | Cookies, caches, and histories cleared when the app is destroyed (toggleable in the menu). |
| Auto-updates | Checks the public GitHub Releases API (anonymous GET) for a newer release and asks: *"Would you like to update? This update has: ..."* with the release notes, then downloads and installs the APK. Opt-out in the menu. |
| Onion mode (IP hiding) | Routes all browsing over the free, open-source Tor network via the [Orbot](https://github.com/guardianproject/orbot) app - sites see a Tor exit IP, never yours. Remote DNS via Tor, so no DNS leak. A custom trusted proxy can be configured instead. We deliberately do not ship random "free VPN API" providers: a free anonymous VPN would still see your traffic. |
| Unsafe-site shield | Every navigation is checked locally against **six open-source lists**: Phishing Army (extended), malware-filter phishing-filter, malware-filter URLhaus (abuse.ch mirror), OpenPhish free feed, Spam404, and stamparm/blackbook - plus a bundled offline starter list. Known malware sites are hard-blocked with no bypass; known phishing sites get a full-screen warning and can only be opened after clicking "I continue to unsafe content" - which remembers that site for the session, in RAM only, never persisted. |
| No network calls by the app itself | The only outbound requests Provito makes are to GitHub (blocklist updates + release checks) - both opt-in/opt-out, both anonymous, and routed through the active proxy (Tor) when enabled. |

## Project structure

```
app/src/main/
├── java/com/provito/browser/
│   ├── MainActivity.kt     # Browser UI, tabs, WebView hardening
│   └── Blocker.kt          # Ad/tracker blocking engine
├── assets/
│   ├── blocklist.txt       # Bundled offline blocklist
│   └── home.html           # Provito start page (Brave search)
└── res/                    # Layouts, icons, menu
```

## Build & run

Requirements: Android Studio (Koala or newer) or command-line SDK with
platform 35.

```bash
# Android Studio: File > Open > Provito, then Run

# or CLI:
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Using it

- Type a query in the omnibox -> **Brave Search**. Type a URL -> goes there.
- Bottom bar: back / forward / home / refresh / tabs / menu.
- Shield icon (top left) -> live privacy stats.
- Menu -> update blocklists, toggle auto-updates, clear data, wipe on exit.

## Extending the blocklist

Add domains to `app/src/main/assets/blocklist.txt` (one domain per line, or
`url:` prefix for substring URL rules), or enable auto-updates to pull the
full StevenBlack list (~100k rules) weekly.

## Releasing

Tag a release and CI does the rest (`.github/workflows/release.yml`):

```bash
git tag v1.0.1
git push origin v1.0.1
```

The workflow builds the APK with the tag as the version and publishes a
GitHub release. The app then auto-detects it on next launch.

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

## License

MIT - see [LICENSE](LICENSE).
