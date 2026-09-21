# TINY1-B 热成像

面向 Infiray Tiny1-B USB 热像模组的 Android 应用。

- 实时热成像预览（USB UVC）
- 软件 ISR 超分辨率
- 铁红 / 白热 / 黑热 / 彩虹 / 熔岩 / 极光 / 医疗 色板
- 最高温、最低温标注
- 中心测温点 + 自定义测温点（添加 / 移动 / 删除）
- 快门校正、USB 权限与空状态

本仓库是独立产品，**不包含**厂商 demo 工程、demo APK 或示例资源。厂商 zip 仅可私下对照。

## 构建

需要 JDK 21+ 与 Android SDK（compileSdk 35）。

```bash
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew :core:test
./gradlew :app:assembleDebug
```

将 **release** APK 装到 **ARM64** 真机，用 USB OTG 连接 Tiny1-B（VID `0BDA` / PID `3901`）。设置里可检查 GitHub 更新。

更完整的模块说明见 [AGENTS.md](AGENTS.md)。
