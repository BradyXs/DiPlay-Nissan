# Changelog

## DiPlay-Nissan 0.2.80

- Fall back to the iAP2 maneuver type when navigation text only contains a road name, so turn arrows are still shown.
- Read the Nissan vehicle-speed signal only while route guidance is active and include valid speed in the cluster's second line.

## DiPlay-Nissan 0.2.79

- Send Nissan cluster navigation detail through the artist field and prefer remaining distance/time for the second line.
- Use the supplied Nissan/CarPlay launch design as the static startup screen.

## DiPlay-Nissan 0.2.78

- Recheck wired VPN preparation when Android returns no TUN, request consent when needed and stop automatic retries after one unsuccessful recovery.
- Deliver Android's VPN revoke callback to the framework binder and keep the app's local service binder for its own connection.
- Record VPN preparation outcomes without logging private connection data.

## DiPlay-Nissan 0.2.77

- Preserve the pre-call media state before sending PAUSE and issue an idempotent PLAY after hangup when playback had been active, including calls spanning a wired reconnect. Keep the resume intent until PLAY is sent on a live session, and retry if the session changes during the delay.
- Add playback transition and privacy-safe route-field presence diagnostics so the next drive log can show which structured guidance fields were actually received without storing road or destination text.
- Request a short HFP grace period during a wired reconnect when the paired firmware bridge supports it; older firmware remains on its existing behavior.
- Record slow microphone `AudioRecord.startRecording()` calls and use one negotiated 20 ms frame per capture read.
- Device-specific behavior still needs validation on the target head unit.

## DiPlay-Nissan 0.2.76

- Use CarPlay `duckAudio` and `unduckAudio` as navigation-prompt boundaries. Keep the Nissan media-track duck at 0.4 until the event-driven behavior is verified on the head unit; restore after a 1-second audio tail, recover after a 30-second missing-event timeout, and reset on AirPlay session changes.

## DiPlay-Nissan 0.2.75

- Duck the DiPlay media track while CarPlay Alternate Audio navigation guidance is active, then restore the current media level when guidance ends. This replaces the previous assumption that the Nissan OEM audio policy would duck the separate media track.

## DiPlay-Nissan 0.2.74

- Keep navigation on the Nissan cluster across brief inactive updates, then restore current media after the route ends.
- Show the artist on the cluster's second media line, falling back to the album when needed.
- Format navigation as short direction/distance and road or available lane text; use only the distance reported from the vehicle to the next maneuver.
- Advertise lane-guidance messages on the Nissan profile and consume a lane description only when the phone identifies it as the visible lane guidance. Vehicle verification is pending.

## DiPlay-Nissan 0.2.73

- Return directly to the active CarPlay host when DiPlay is launched while a session is running, without rendering its home or connection page first.
- Preserve the session's first-frame state across head-unit foreground transitions, keep the connection overlay hidden for an already-rendered session, and request a fresh frame when its video surface returns.

## DiPlay-Nissan 0.2.72

- Refresh the firmware HFP session bridge immediately when a wired AirPlay session starts or ends, rather than waiting for the next three-second heartbeat.
- Keep the three-second heartbeat and trigger an immediate pulse when the firmware bridge service connects, reducing the window before the firmware can pre-arm its device-scoped HFP request gate.

## DiPlay-Nissan 0.2.63

- Renamed the repository and project page to DiPlay-Nissan. The car app, CarPlay receiver and APK file use DiPlay.
- Kept the Android package ID and signing certificate so the Nissan APK can update the installed app.

## 0.2.62

- Nissan Android 4.4.3 / ARMv7 profile with wireless and USB CarPlay support.
- Media, navigation and call audio controls, including an optional call output stream choice.
- Steering-wheel media, call and Siri controls on supported Nissan firmware.
- Local diagnostic report export.

Earlier DiPlay development history and contributor credits are available in the [DiPlay repository](https://github.com/shihabal3amri/DiPlay). DiPlay-Nissan also incorporates work from [DiPlay-Legacy-Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android). See the [third-party notices](docs/THIRD_PARTY_NOTICES.md).
