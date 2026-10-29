# NiPlay

[简体中文](README.zh-CN.md) · [English](README.md)

NiPlay 基于 [DiPlay](https://github.com/shihabal3amri/DiPlay) 和 [DiPlay-Legacy-Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android)，专门为**日产 Android 4.4.3 车机**定制开发。当前主要适配 **2019 款日产逍客的 ARMv7 车机**，支持无线及 USB CarPlay 连接、本机音视频播放、方向盘媒体按键和诊断日志导出。其他车机的兼容性尚未验证。

## 构建

先安装[构建指南](docs/BUILD.md)列出的 JDK、Android SDK 和 NDK。构建不含认证材料的源码包：

```sh
ANDROID_HOME=/path/to/Android/Sdk sh ./gradlew \
  -PnissanAndroid44Profile=true \
  -PnissanAndroid44Abi=armeabi-v7a \
  :mobile:assembleDebug
```

可独立连接 CarPlay 的安装包需要自行提供本地认证材料，仓库不包含这些文件。具体步骤见[构建指南](docs/BUILD.md)和[安装说明](docs/INSTALL.md)。不要提交私钥、签名凭据或导出的诊断日志。

## 致谢与许可

感谢 [DiPlay](https://github.com/shihabal3amri/DiPlay) 和 [DiPlay-Legacy-Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android) 的开源工作，本项目在此基础上开发；也感谢 [xcertplay](https://github.com/shilapi/xcertplay) 提供接收端基础。设置界面包含改编自 [DiAuto](https://github.com/shihabal3amri/DiAuto) 的内容。

再分发时请保留原有版权及许可声明。详见 [LICENSE](LICENSE)、[第三方声明](docs/THIRD_PARTY_NOTICES.md)和保留的[上游说明](docs/UPSTREAM-README.zh-CN.md)。CarPlay 是 Apple 的商标；NiPlay 是独立项目，未获 Apple 认证。
