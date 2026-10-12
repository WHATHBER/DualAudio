<div align="center">

<img src="docs/icon.png" width="128" alt="DualAudio">

# DualAudio · 双路音频

**让蓝牙耳机和 USB 声卡（有线耳机）同时出声；有线那一路的硬件音量可独立调节，蓝牙音量用系统音量键控制**

基于 Android 12+ 原生「组合音频设备路由」· Material 3 界面 · 需 Root

[![Build APK](https://github.com/WHATHBER/DualAudio/actions/workflows/build.yml/badge.svg)](https://github.com/WHATHBER/DualAudio/actions/workflows/build.yml)

</div>

---

> ## ⚠️ 设备兼容性 — 安装前必读
>
> 本应用**目前仅在 小米 14 Ultra（HyperOS 3.0 / Android 16）上验证可用**。
>
> **其他机型均未测试，大概率无法使用。** 该能力依赖厂商音频 HAL 是否实现输出流复用：
> 即使底层 API 返回成功，也可能只有一路出声，或完全没有效果。非上述设备请谨慎尝试，不要期待开箱即用。

---

## 这是什么

Android 音频策略默认只把媒体音频送到**一个**输出设备，插上 USB 声卡就会顶掉蓝牙，反之亦然。

DualAudio 通过调用 Android 12 引入的**组合音频设备路由**（Combined Audio Device Routing）系统 API，让同一路媒体音频**同时**输出到蓝牙 A2DP 耳机和 USB 声卡，并绕开系统限制，实现**两路音量独立可调**。

这个能力在系统层面一直存在，但没有厂商把它开放给用户，市面上也没有现成应用实现它。

## 功能特性

- **双路同时输出** —— 蓝牙耳机 + USB 声卡 播放同一路媒体音频
- **USB 声卡硬件音量独立调节** —— 解决「有线那一路声音大到炸耳朵」且音量键调不动的问题
- **蓝牙音量交给系统音量键** —— 原因见下方「为什么没有蓝牙音量滑块」一节
- **Material 3 深色界面** —— 设备不在线时自动折叠对应卡片，不显示无效控件
- **开启前校验** —— 点击开关先验证设备是否就绪，失败回滚并提示原因，杜绝连点堆积命令
- **完整命令行工具** —— 逻辑层可脱离 App 独立运行，便于调试与二次开发

## 界面

<!-- 欢迎补充界面截图，放到 docs/screenshot.png 后替换下方路径 -->

| 开关与音量 | 设备状态 |
|:---:|:---:|
| *(待补充截图)* | *(待补充截图)* |

## 技术原理

这一节是项目的核心，四个关键点都是在小米 14 Ultra 上实测得出的。

### 1. 路由：组合音频设备路由

Android 12 (API 31) 引入：

```java
AudioManager.setPreferredDevicesForStrategy(
        AudioProductStrategy strategy,
        List<AudioDeviceAttributes> devices);
```

它是 `@SystemApi` 且要求 `MODIFY_AUDIO_ROUTING` 权限（`signature|privileged`），普通应用不可调用。

**突破口**：以 root (`uid=0`) 运行时，`ActivityManagerService.checkComponentPermission` 会对 root 直接返回 `GRANTED`，权限检查自然通过。这就是整个方案成立的前提。

### 2. 设备对象必须由 AudioDeviceInfo 构造

这是踩过的坑：手工 `new AudioDeviceAttributes(int type, String address)` 构造的对象会被 native 层拒绝，API 返回 `false`。必须：

```java
new AudioDeviceAttributes(audioDeviceInfo)   // 从系统枚举出的对象直接构造
```

原因：手工构造的对象缺少 `role` / `name` / `profiles` 等字段，native 校验不通过。

可用 `list` 命令确认设备对象是否完整（会打印 `role:output type:bt_a2dp ... profiles:[...]`）。

### 3. 媒体策略识别

遍历 `getAudioProductStrategies()`，用 `supportsAudioAttributes(USAGE_MEDIA)` 判断哪条是媒体策略。

**不要**用 `getAudioAttributes()` 判断 —— 它只返回该策略的**第一个**属性组，媒体策略的第一个属性组可能是 `USAGE_ASSISTANT`，会误导。

实测小米 HyperOS 上媒体策略为 `id=5 / STRATEGY_MEDIA`。

### 4. USB 声卡音量：绕开 Android 音频框架

Android 的媒体音量按设备存储，但**对 USB Audio Class 设备通常无效** —— 这类设备的音量由硬件增益决定，系统下发的软件音量值被 HAL 丢弃。

同时，底层按设备设置音量的 `AudioSystem.setStreamVolumeIndexAS` 在部分 ROM 上已被移除（实测 HyperOS 上抛 `NoSuchMethodException`）。

**最终方案**：直接操作 ALSA control。

```bash
tinymix -D <card> "PCM Playback Volume" <left> <right>
```

这是绕过整个 Android 音频栈、直写声卡寄存器的做法，必定生效。先执行 `tinymix -D <card>` 查看控件名与取值范围。

> 顺带一提，`AudioManager.setStreamVolume` 只作用于「主设备」，所以即使音量键能调蓝牙，也永远调不到 USB 声卡 —— 这就是它炸耳朵的根因。

### 5. 为什么没有蓝牙音量滑块

手机上蓝牙耳机的音量由 **AVRCP 绝对音量**同步，通道是「手机 → 耳机」。这个同步有两个坑：

**① `setDeviceVolumeBehavior` 对蓝牙的含义完全不同。** AudioService 里的实际逻辑是：

```java
if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
    // 不是"设置音量行为"，而是"是否启用绝对音量同步"
    avrcpSupportsAbsoluteVolume(device.getAddress(),
            deviceVolumeBehavior == AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE);
    return;
}
```

传入任何非 `ABSOLUTE` 的值都会**关闭绝对音量同步**，之后手机侧音量再也传不到耳机 —— 表现为「音量键和程序都调不动蓝牙」。

**② 双路输出时，系统会在约 1 秒后重算音量。** 设好的值会先正常写入，随后被按多设备规则改回。这正是用「独占 → 设置 → 恢复双路」那套流程改蓝牙音量的副作用：路由切换触发了重算。

经实测，这条路上稳定可控的只有系统自身的音量键，因此 App 不做蓝牙音量滑块。逻辑层保留了 `vol bt <0-100>` 命令，但受上述限制，不保证在所有机型上生效。

> 作为补偿，App 启动时会自动确认蓝牙的绝对音量处于开启状态（`status fix`），否则连音量键都会失效。

### 6. App 架构：通过 su 调用 app_process

App 进程本身不是 root，无法直接调用 SystemApi。因此：

```
App (UI) ──su -c──▶ app_process 运行 core/MultiAudio.dex ──▶ 系统音频 API
```

`core/MultiAudio.dex` 打包在 APK 的 `assets/` 中，首次启动自动部署到 `/data/local/tmp/`。

## 环境要求

| 项目 | 要求 |
|---|---|
| Android | 12+（API 31+，组合音频路由的最低版本） |
| Root | KernelSU 或 Magisk |
| 输出设备 | USB Audio Class 声卡/耳机 + 蓝牙 A2DP 耳机 |
| 构建 | 见「从源码构建」 |

## 安装

1. 下载 [Releases](../../releases) 中的 `DualAudio.apk`
2. 安装（首次需允许「未知来源」；HyperOS 可能拦截，选择「继续安装」）
3. 打开 App，在 KernelSU / Magisk 中授予 **root 权限**
4. 连上蓝牙耳机、插上 USB 声卡
5. 打开「双路同时输出」开关

> 若提示「USB 声卡未插入」或「蓝牙未连接」，请确认对应设备确实已连接且显示在系统蓝牙/音频设置中。

## 命令行使用

仓库 `scripts/` 下提供了全部命令行工具，适合调试或在不装 App 的情况下使用。

```bash
# 把 core/MultiAudio.dex 和需要的脚本放到同一目录
adb push core/MultiAudio.dex /data/local/tmp/
adb shell su -c "app_process -Djava.class.path=/data/local/tmp/MultiAudio.dex \
    /system/bin --nice-name=multiaudio MultiAudio list"
```

主要命令：

| 命令 | 说明 |
|---|---|
| `list` | 列出所有输出设备、产品策略与当前绑定 |
| `auto` | 自动识别设备并绑定双路输出 |
| `autoclear` | 清除绑定，恢复单设备 |
| `vol usb <0-100>` | 设置 USB 声卡硬件音量 |
| `vol bt <0-100>` | 设置蓝牙音量 |
| `status` | 输出结构化状态（供 App 解析） |
| `sysapi` | 列出系统全部音量相关 API 签名（用于排查） |
| `alsa [参数]` | 透传 `tinymix`，查看/调整 ALSA 控件 |

脚本 `1_probe.sh` ~ `9_crash.sh` 是各功能的封装，可直接执行。

## 已验证设备

> **目前仅有以下组合通过验证。除此之外的设备全部未测试，大概率不可用。**

| 设备 | 系统 | 结果 |
|---|---|---|
| 小米 14 Ultra | HyperOS 3.0（Android 16） | ✅ 双路输出正常 + USB 声卡硬件音量可调 |
| OPPO Enco X3（蓝牙）+ USB-Audio CDSP 声卡 | — | ✅ |

关键变量是厂商音频 HAL 是否实现了输出流复用 —— **即使 API 返回成功，也可能只有一路出声**。

其他机型欢迎提交测试结果（机型 / 系统版本 / 是否成功 / 异常现象）。

自检方法：

```bash
# 单独设置一个设备应当也返回 true，两个一起设置返回 true 才是 API 层通过
# 最终能否双路出声仍需实听验证
```

## 已知限制

- **延迟**：蓝牙 A2DP 存在固有编码/传输延迟（SBC/AAC 约 150–250ms），USB 声卡近乎实时，两路会有可感知的不同步。开启耳机低延迟模式、使用 LHDC/aptX 等低延迟编解码可压到 50–100ms，但**无法完全消除**。
- **掉绑定**：蓝牙断开重连后，双路绑定失效，需重新执行一次（App 内点「重新应用」）。
- **厂商 HAL 差异**：API 返回成功不等于真正双路出声，取决于厂商音频 HAL 是否支持输出流复用。
- **音质**：双路输出时两设备会被统一到公共采样率，蓝牙侧可能被降级。
- **通话场景**：本项目只修改 `USAGE_MEDIA` 策略，不涉及通话路由。

## 常见问题

**Q: 开关打开后只有一边出声？**
API 层可能已接受（返回 `true`），但厂商 HAL 未真正复用输出流。试 `sysapi` 与 `list` 确认状态，或换编码器后重试。

**Q: 音量键还是只影响蓝牙？**
符合预期。双路输出时主设备是蓝牙，所以音量键调蓝牙；**USB 声卡不受主设备音量影响**，需在 App 内用它的滑块（走 ALSA 硬件增益）。这也是蓝牙音量不做成滑块的原因，详见「为什么没有蓝牙音量滑块」。

**Q: USB 声卡音量调不动？**
先确认 `tinymix -D <card>` 能列出 `PCM Playback Volume` 控件；若控件名不同，用 `8_usbvol.sh` 修改控件名。

**Q: 蓝牙耳机音量调不动，连音量键也失效了？**
说明耳机的 **AVRCP 绝对音量同步**被关闭了（见「为什么没有蓝牙音量滑块」）。重新打开 App 会自动修复 —— 启动时执行的 `status fix` 会把绝对音量重新打开。也可以断开蓝牙重连一次。

**Q: 重启后要重新设置吗？**
是的，绑定是运行时状态。可自行编写开机脚本，或每次开机点一下「重新应用」。

## 从源码构建

不需要 Gradle，纯命令行工具链：

```bash
export ANDROID_HOME=/path/to/android-sdk   # 需含 build-tools 与 platforms
cd app
./build.sh
# 产物：app/out/DualAudio.apk
```

依赖 JDK 11+、Android SDK build-tools（aapt2 / zipalign / apksigner / d8）。

只编译逻辑层：

```bash
javac --release 8 -d out core/MultiAudio.java
d8 --release --min-api 26 --output out out/MultiAudio.class
```

## 项目结构

```
.
├── app/                    # 图形界面（原生 Android View + 代码绘制 UI）
│   ├── AndroidManifest.xml
│   ├── build.sh            # 一键构建脚本
│   ├── res/                # 图标等资源
│   └── src/com/dualaudio/  # MainActivity / App
├── core/
│   ├── MultiAudio.java     # 逻辑层：音频路由 + 音量控制（可独立运行）
│   └── MultiAudio.dex      # 预编译产物，脚本用户可直接使用
└── scripts/                # 命令行工具与封装脚本
```

## 参与贡献

- 提交其他机型的测试结果（机型 / 系统版本 / 是否成功 / 异常现象）
- 报告兼容性问题时，请附上 `list` 与 `sysapi` 的输出
- 代码遵循现有风格：逻辑层保持零第三方依赖，界面层不引入图片资源

## 许可

[GPL-3.0](LICENSE)

## 致谢

原理验证过程中参考了 AOSP 的 [组合音频设备路由](https://source.android.com/docs/core/audio/combined-audio-routing) 文档与 `AudioPolicyManager` 源码。
