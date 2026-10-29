# NiPlay

[简体中文](README.zh-CN.md) · [English](README.md)

NiPlay is a CarPlay receiver developed specifically for **Nissan head units running Android 4.4.3**, based on [DiPlay](https://github.com/shihabal3amri/DiPlay) and [DiPlay-Legacy-Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android). Its current target is the **ARMv7 head unit in the 2019 Nissan Qashqai**. It supports wireless and USB CarPlay connections, local audio and video playback, steering-wheel media controls, and diagnostic report export. Compatibility with other head units has not been established.

## Build

Install the JDK, Android SDK and NDK listed in the [build guide](docs/BUILD.md). A source-only APK can be built with:

```sh
ANDROID_HOME=/path/to/Android/Sdk sh ./gradlew \
  -PnissanAndroid44Profile=true \
  -PnissanAndroid44Abi=armeabi-v7a \
  :mobile:assembleDebug
```

A standalone CarPlay APK also needs locally supplied authentication assets; they are not included in this repository. See the [build guide](docs/BUILD.md) and [installation guide](docs/INSTALL.md). Never commit private keys, signing credentials or exported diagnostic reports.

## Acknowledgements and licenses

Thanks to [DiPlay](https://github.com/shihabal3amri/DiPlay) and [DiPlay-Legacy-Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android) for the open-source work this project builds upon. Thanks also to [xcertplay](https://github.com/shilapi/xcertplay) for the receiver foundation. The settings interface includes work adapted from [DiAuto](https://github.com/shihabal3amri/DiAuto).

Keep the original copyright and license notices when redistributing. See [LICENSE](LICENSE), the [third-party notices](docs/THIRD_PARTY_NOTICES.md) and the preserved [upstream README](docs/UPSTREAM-README.md). CarPlay is an Apple trademark; NiPlay is independent and is not Apple-certified.
