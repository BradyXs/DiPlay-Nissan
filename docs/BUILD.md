# Build DiPlay-Nissan

Build from the repository root. The main Android app is the `:mobile` module.

## Requirements

- JDK 25
- Android SDK Platform 37 and Build Tools 36.0.0
- Android NDK 25.2.9519653 (r25c)
- The Gradle wrapper included with this repository

Set `ANDROID_HOME` to your SDK directory, or put `sdk.dir=/absolute/path/to/Android/Sdk` in an untracked `local.properties` file.

## Source-only build

```sh
sh ./gradlew -PnissanAndroid44Profile=true -PnissanAndroid44Abi=armeabi-v7a :mobile:assembleDebug
```

Output: `mobile/build/outputs/apk/debug/mobile-debug.apk`. A source-only build does not contain accessory authentication assets and cannot establish a standalone CarPlay session.

## Standalone test build

Provide your own authorized runtime assets in a directory outside Git:

```text
runtime-assets/
└── offline-mfi/
    ├── identity.pk8
    └── certificate.p7b
```

Then build:

```sh
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets \
  sh ./gradlew -PnissanAndroid44Profile=true -PnissanAndroid44Abi=armeabi-v7a \
  -Pandroid.injected.testOnly=false :mobile:assembleStandaloneDebug
```

The task checks the files before packaging. It does not establish that an iPhone will accept them. An APK containing authentication assets can expose them to anyone who receives the APK. Keep your Android signing keystore separate and private. For an update installation, the package name and signing certificate must match the installed app.

## Checks

Run the repository's unit tests, lint tasks and debug builds before proposing changes. Android 4.4 compatibility and a working CarPlay session still require testing on the target head unit.
