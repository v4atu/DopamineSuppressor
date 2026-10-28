# DopamineSuppressor

An Android app that enforces a **daily time limit per app**. When an app reaches its limit, it is sent back to the home screen until midnight.

## How it works

- An **accessibility service** detects which app is in the foreground. It only listens to window-change events and does **not** read screen content.
- Usage is counted from Android's **UsageStats**, so the numbers match what the system reports.
- Counters reset at local midnight.
- Everything stays on your device: the app declares no network permission and has no analytics.

## Requirements

Android 10 (API 29) or newer.

## Install

1. Download the APK from the [GitHub Releases](https://github.com/v4atu/DopamineSuppressor/releases) page.
2. [Verify the signing certificate](#verify-the-signing-certificate).
3. Install the APK and grant the [permissions](#permissions).

## Verify the signing certificate

Every official release is signed with the same key. Before installing, check that the APK's certificate matches this fingerprint:

| | |
| :- | :- |
| SHA-256 | `DB:7C:25:45:37:FF:02:35:B6:58:2D:C0:46:76:FA:12:A8:15:C3:11:4A:E3:0B:A5:50:15:42:0F:DB:85:4A:5A` |

**On a computer**, with `apksigner` from the Android SDK build-tools:

```sh
apksigner verify --print-certs DopamineSuppressor.apk
```

The `Signer #1 certificate SHA-256 digest` line must read (lowercase hex, no colons):

```
db7c254537ff0235b6582dc04676fa12a815c3114ae30ba55015420fdb854a5a
```

**On the phone**, you can use an app such as [AppVerifier](https://github.com/soupslurpr/AppVerifier): provide the package name `com.v4atu.dopaminesuppressor` and the SHA-256 above, then select the APK.

If the fingerprint does not match, **do not install the APK**. Android also refuses to update an installed app with a build signed by a different key. An APK you build yourself will have a different fingerprint.

## Permissions

| Permission | Required | Why |
| :- | :- | :- |
| Usage access | Yes | Reads how long each app was used today. |
| Accessibility service | Yes | Detects the foreground app and sends it home when the limit is reached. |
| Battery optimization exemption | No | Helps keep the service alive. |

If Android won't let you enable the accessibility service for a sideloaded app, go to Settings → Apps → DopamineSuppressor → ⋮ menu → **Allow restricted settings**, then try again.

## Usage

Tap an app in the list, set the limit in hours and minutes, and save. Each limit can be switched off or removed. The list shows today's usage next to each limit.

## Build from source

```sh
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:testDebugUnitTest    # unit tests
./gradlew :app:assembleRelease      # release APK
```

The release build is signed only if a `keystore.properties` file (git-ignored) exists in the project root with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without it the release APK is unsigned.

## Project structure

Package `com.v4atu.dopaminesuppressor`:

- `core/` – pure Kotlin tracking logic (no Android APIs), unit-tested on the JVM.
- `data/` – limits storage (Preferences DataStore) and UsageStats reading.
- `guard/` – `GuardService`, the accessibility service that applies the limits.
- `ui/` – main screen, permission rows and limit editor.

Contributor and coding-agent notes are in [AGENTS.md](AGENTS.md).

## License

[MIT](LICENSE)
