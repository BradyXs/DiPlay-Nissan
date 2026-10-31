# DiPlay-Nissan

DiPlay-Nissan 基于 [DiPlay](https://github.com/shihabal3amri/DiPlay) 和 [DiPlay-Legacy-Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android)，专门为**日产 Android 4.4.3 车机**定制开发。当前主要适配 **2019 款日产逍客的 ARMv7 车机**，支持无线及 USB CarPlay 连接、本机音视频播放、方向盘媒体按键和诊断日志导出。CarPlay 导航、媒体和通话音量可分别调节；导航与媒体可分别选择车机音频通道，通话音频固定使用 Android 通话流 `0`。其他车机的兼容性尚未验证。

项目名称为 DiPlay-Nissan；车机中显示的应用名称及发布的 APK 文件名使用 **DiPlay**。Android 包名保持不变，以支持覆盖安装。

## 版本与下载

- [GitHub Releases](https://github.com/BradyXs/DiPlay-Nissan/releases) 提供日产车机 APK、校验值、版本说明和源码归档。
- [项目页面](https://bradyxs.github.io/DiPlay-Nissan/) 介绍适配范围、安装和构建方式。

公开源码不包含 MFi 认证材料或 Android 签名私钥。Release 中的 APK 内含运行所需认证文件；公开下载意味着这些文件可从 APK 中提取。已有安装的覆盖升级要求包名与签名证书一致。请只安装适用于日产 Android 4.4.3 / ARMv7 的版本。

## 方向盘按键

安装并打开 DiPlay 后，在设置中开启“方控”。随后长按车机关机键，持续按住，直到车机强制关闭并重新启动；重启后再测试方向盘按键。操作时车辆应处于停车状态。

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

再分发时请保留原有版权及许可声明。详见 [LICENSE](LICENSE)、[第三方声明](docs/THIRD_PARTY_NOTICES.md)和保留的[上游说明](docs/UPSTREAM-README.zh-CN.md)。CarPlay 是 Apple 的商标；DiPlay-Nissan 是独立项目，未获 Apple 认证。
