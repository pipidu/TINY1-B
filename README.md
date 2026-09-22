# TINY1-B 热成像

面向 Infiray Tiny1-B USB 热像模组的 Android 应用。

USB 取流沿用厂商 demo 中实际能打开 Tiny1-B 的路径（`libUVCCamera` / `com.zz.infisense.camera`，targetSdk 26）。画面处理、测温和界面是本仓库的 Compose 产品：

- 实时热成像预览（UVC 256×384 YUYV 叠温，按 demo 拆成 192×256 画面 + 温度）
- 软件 ISR 超分辨率（2× / 4×，双线性，不影响实时帧率）
- 软件锐化（默认关，只作用在显示画面）
- 固定色板上下限（按设定的 °C 范围上色）
- 软件帧生成（2× / 3× 插帧，按等间隔排在两帧之间，不超过屏幕刷新；设置里开关，默认关）
- 画面旋转（0° / 90° / 180° / 270°，会记住）
- 铁红 / 白热 / 黑热 / 彩虹 / 熔岩 / 极光 / 医疗 色板
- 最高温、最低温标注
- 中心测温点 + 自定义测温点（先点「测温」，再添加 / 移动 / 删除；未连接时测温不可用）
- 拍照、录像（保存到系统相册，无麦克风）；实时底栏 快门 / 拍照 / 录像 / 测温；拍照/录像提示浮在画面上，不挤底栏
- 快门校正、色板、ISR、帧生成、画面旋转在设置里；设置可清除软件缓存
- KB 标定、USB 权限与空状态
- 亮色界面（浅底、深字、蓝色强调）；热成像色板仅作用在画面上
- 应用内检查 GitHub Releases 更新

本仓库**不包含**厂商 zip、demo 工程、demo APK、`libir_sample`。厂商 zip 仅可私下对照。

应用 ID：`com.pipidu.tiny1b`（JNI 绑定的是 Java 包名 `com.zz.infisense.camera`，不是 applicationId）。

## 构建

需要 JDK 21+ 与 Android SDK（compileSdk 35，**targetSdk 26**，与能连通的 demo 一致）。arm64-v8a。

```bash
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew :core:test
./gradlew :app:assembleRelease
```

将 **release** APK 装到带 USB OTG 的真机，连接 Tiny1-B（VID `0BDA` / PID `3901`）。设置里可检查 GitHub 更新。

更完整的模块说明见 [AGENTS.md](AGENTS.md)。
