# Privacy and diagnostics

The DiPlay app in the DiPlay-Nissan project performs local CarPlay authentication and uses direct USB, Bluetooth and Wi-Fi connections to the iPhone. It does not require an account or remote authentication server. Diagnostic reports are never uploaded automatically.

App preferences, selected paired-device information and bounded diagnostic logs are stored on the head unit. Uninstalling removes app-private data. Authentication and pairing material are excluded from Android backup.

Diagnostic export is initiated by the driver. Reports include app/device versions, connection transitions, Wi-Fi state and channel information, video receive/decode timing, display output and recovery events. The exporter filters protocol payloads, credential-bearing lines and common identifiers, but redaction cannot identify every vendor-specific string. Review a report before sharing it, especially in a public issue.

On Android 4.4, the system document picker or Downloads storage may be unavailable. DiPlay can save reports to app-specific external storage or private app storage and offers a view/share flow. Shared reports grant read access only to the selected file. On Android 9 and older, other apps with storage permission may be able to read reports saved on external storage.

Microphone access is used for Siri and call-audio paths when enabled. Bluetooth and Wi-Fi permissions support device discovery and transport. The optional local VPN permission carries the USB network link; it is not an internet VPN service.
