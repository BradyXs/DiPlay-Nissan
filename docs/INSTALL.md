# Install and connect

These instructions target a 2019 Nissan Qashqai head unit running Android 4.4.3. Other models and firmware may behave differently.

## Install

Install a Nissan ARMv7 APK built with `testOnly=false`. Updating an existing installation requires the same package name and Android signing certificate. A standalone CarPlay build also requires locally supplied authentication assets; see [BUILD.md](BUILD.md).

## Wireless

1. Pair the iPhone in the Nissan Bluetooth settings.
2. Enable Wi-Fi on the iPhone and head unit, and connect them through a suitable local wireless network or the car's hotspot.
3. Open DiPlay, choose the paired phone and start a wireless connection. Follow the prompts on the phone and head unit.
4. In DiPlay audio settings, choose whether CarPlay audio plays through DiPlay or the car's Bluetooth system.

## USB

Use a data-capable USB cable and port. Select USB in DiPlay and approve the USB, iPhone trust and local network prompts when they appear.

If DiPlay reports that iPhone USB is busy or its USB permission was not granted, exit the car's OEM iPod player and reconnect the cable. If the OEM iPod app or service keeps occupying USB and is confirmed as the cause, use [Headunit Manager](https://github.com/BradyXs/headunit-manager) → **App management** to disable that iPod app or service. Re-enable the same item to restore OEM iPod playback. Disabling it may affect the car's native USB/iPod media, so only change a confirmed iPod component. This is a manual action; DiPlay does not toggle it automatically with the wired session. The [platform-signed Manager APK](https://github.com/BradyXs/headunit-manager/releases/latest) works only with matching firmware signatures.

## Diagnostics

When a problem occurs, export a report from **Settings → Diagnostics**. Review the file before sharing it publicly; reports can include device and connection details. DiPlay does not upload reports automatically. See [PRIVACY.md](PRIVACY.md).
