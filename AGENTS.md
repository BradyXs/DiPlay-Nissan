# AGENTS.md

Instructions for coding agents that work on DiPlay-Nissan.

## Project and compatibility

- This repository is the active DiPlay-Nissan application source. The firmware project and NissanHeadunitManager are sibling projects; firmware worktrees include historical or experimental snapshots. Check the current branch, working tree and actual firmware recipe instead of assuming those snapshots are current.
- Target the Nissan HSAE NTDA3_P350 Android 4.4.3 / API 19 ARMv7 unit. Keep ordinary firmware and other supported devices working when adding Nissan-specific behavior. Verify device-specific assumptions against code and logs; distinguish static analysis, successful builds and owner-reported vehicle results.
- Keep the original CarPlay receive/decode path as the working baseline. The OEM native video work is an experiment; do not replace the main path with it unless explicitly requested.
- Steering-wheel buttons, telephony, Siri, microphone and Bluetooth coordination cross firmware and app boundaries. Preserve existing calls and other phones when considering routing changes. Do not claim that a firmware signature or a successful build proves runtime behavior.

## Build and authentication

- Every installable APK or app bundle produced for this user, including a car-test or firmware APK, must include the authorized offline MFi assets `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`. Do not deliver source-only APKs as installable builds. The local asset directory is `.private/runtime-assets`; supply it with `DIPLAY_AUTH_ASSETS_DIR`, and never commit or print the asset contents, signing secrets or diagnostic logs.
- For a Nissan Android 4.4 debug candidate use `DIPLAY_AUTH_ASSETS_DIR="$PWD/.private/runtime-assets" sh ./gradlew -PnissanAndroid44Profile=true -PnissanAndroid44Abi=armeabi-v7a -Pandroid.injected.testOnly=false :mobile:assembleStandaloneDebug`. The output is `mobile/build/outputs/apk/debug/mobile-debug.apk`. For other variants see `docs/BUILD.md` and `mobile/build.gradle.kts`.
- Confirm the actual package name, version, certificate, API, ABI, offline MFi assets and APK SHA-256 before replacing a firmware resource. The debug, production and release variants may have different identities; matching package name and signing certificate is required for ordinary update installation.
- Check current versions and source state from the working tree rather than copying version numbers from old docs. Preserve unrelated changes and never commit generated APKs, keys, firmware or user logs to this source repository.

## Checks

- Build the applicable standalone variant when an installable build is requested; report build results and the limits of any offline checks. For compile-only work use a compile task and do not deliver the resulting source-only APK.
- When tests or implementation verification are requested, choose the relevant unit tests and lint tasks for the change. The broader project checks include `:shared:testDebugUnitTest`, `:common:testDebugUnitTest`, `:home:testDebugUnitTest`, `:mobile:lintDebug`, `:home:lintDebug` and `:maphost:lintDebug`. An offline check does not replace validation on the target head unit.

## Adding or moving a setting

The Settings screen is grouped by driver goal, not by implementation.
`SettingsCategory`, `SettingsSection` and `SettingsInformationArchitecture` at the top of
`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` define the groups.
`SettingsLayoutPolicyTest` fails when a section has no category or has more than one.

### Choose the category

Ask which goal the driver has when they look for the setting. Use the first row that fits.

| Category | Put a setting here when it controls… | Examples |
| --- | --- | --- |
| Connection | how the iPhone connects and how DiPlay starts | connection setup, connect on open, start with the car, USB permissions, car hotspot automation, iPhone choice, Android permissions |
| Display | how CarPlay looks on the head-unit screen | day/night mode, picture, size, resolution, frame rate, dock, system bars, multi-window resolution |
| Audio | what the driver hears | media and navigation streams, music buffer |
| Navigation | location and turn-by-turn guidance | location to iPhone, BYD HUD and cluster guidance |
| Vehicle | how CarPlay fits this car and its driver | driving side, wheel keys (Siri, BYD joystick and map zoom), car button, gestures that conflict with the head unit |
| Diagnostics | troubleshooting evidence | diagnostic reports |
| Advanced | experimental, firmware-specific or risky behavior | dashboard map, split screen, screen rotation, side panel, HEVC video, audio focus, audio channel mapping, buffered music, vehicle data |
| Overview | nothing new | see "Overview" below |

A setting goes to Advanced when it is experimental, depends on specific firmware, or is an opt-in that can break sound, video or the connection on some head units.
A control that fixes a common problem and is safe at its default (for example the audio stream choice) stays in its category.
Exception: a gated control that only its own audience sees, and that completes an everyday goal, stays in that goal's category.
Example: auto car hotspot needs ADB but appears only for car-hotspot users, so it is in Connection.

Mark an experimental setting or card with the title suffix ` (experimental)`, never `· experimental`, a dash, or an `Experimental …` prefix.
Each locale MUST use its existing form: `(تجريبي)`, `(experimental)`, `(экспериментально)`, `(експериментально)`, `（实验性）`, `（實驗性）`.

### Add the code

1. To extend an existing card, add the control inside the `filteredSection(...)` block of a card in the correct category.
2. For a new card, add a `SettingsSection` entry and map it to exactly one category in `SettingsInformationArchitecture.sectionsByCategory`.
3. Build the new card with `filteredSection(content, SettingsSection.X, …)` inside `allSettingsSections()`.
   A plain `section(...)` call there MUST NOT be used: it renders on every category page.
4. A setting MUST NOT appear in two categories.
   Overview quick settings are the only duplicates. They MUST use the same persistence as the full control. Overview SHOULD NOT have more than four quick settings.
5. If the change reconnects CarPlay or applies at the next connection, the description MUST say so.
   A setting that only takes effect at the next connection MUST call `markReconnectNeeded()` after it saves.
   This shows the "Reconnect now" bar. It MUST NOT drop a running session without the driver's consent.
   Reconnect at once (`reconnectIfRunning()`) only after a dialog button that says "Apply and reconnect", or when the description says that the change reconnects CarPlay.
6. Add each new string to `common/src/main/res/values/` and to every `values-xx` locale folder.
   New Settings copy SHOULD use the `settings_` prefix. `SettingsTranslationsTest` fails when a `settings_*` string has no translation.
7. A control that opens a choice uses `button()` with the text `"Title · Value"`.
   That form renders as a setting row with the value and a chevron.
8. When you move a control between categories, update `AdaptiveSettingsUiTest.settingsLiveWhereDriversLookForThem`.

### Overview

Overview holds the connection status, links to the categories, quick settings, About and Language.
Do not add a new setting to Overview. Add it to its category. Then promote it to quick settings only if drivers change it often.
