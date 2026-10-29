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

## Diagnostics

When a problem occurs, export a report from **Settings → Diagnostics**. Review the file before sharing it publicly; reports can include device and connection details. DiPlay does not upload reports automatically. See [PRIVACY.md](PRIVACY.md).
