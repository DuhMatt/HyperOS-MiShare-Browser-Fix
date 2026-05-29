# HyperOS MiShare Browser Fix

## 中文说明

这是一个 LSPosed 模块，用来修复 HyperOS / MIUI 中 MiShare（小米互传）分享网页链接时强制经过小米浏览器或小米应用商店的问题。

在某些设备上，如果小米浏览器不可用、未安装，或用户不希望使用小米浏览器，MiShare 接收到网页链接后可能会把原始链接转换成小米应用商店的跳转，例如：

```text
market://details?id=com.android.browser
```

随后小米应用商店再启动用户设置的第三方浏览器，但传给浏览器的地址只剩：

```text
https://
```

这个模块会从 MiShare 的内部数据中恢复原始分享链接，并用用户设置的默认浏览器打开真实 URL。

### 功能

- Hook MiShare (`com.miui.mishare.connectivity`) 的链接打开流程。
- 识别指向小米浏览器 / 小米应用商店的重定向 Intent。
- 从 MiShare 的 `TapRecvData` / `TapData` 对象字段中恢复原始分享链接。
- 使用系统默认浏览器打开恢复后的链接。
- 尽量避免影响非网页 Intent，例如文件、电话、短信、地图和应用私有 scheme。

### 已测试环境

以下信息来自实机 ADB 读取：

| 项目 | 值 |
| --- | --- |
| 厂商 | Xiaomi |
| 设备型号 | `25128PNA1C` |
| 设备代号 | `nezha` |
| Android 版本 | `16` |
| Android SDK | `36` |
| HyperOS 版本名 | `OS3.0` |
| HyperOS 增量版本 | `OS3.0.307.0.WPACNXM` |
| MIUI UI 版本名 | `V816` |
| 系统构建版本 | `16OS3.1.260514.221906302.QCPECN.S` |
| LSPosed | 已测试 |
| 测试浏览器 | Via (`mark.via`) |

实测可工作的恢复路径：

```text
点击 MiShare 接收通知
-> tap_recv_data
-> com.miui.mishare.tap.TapData.h
-> 原始 https 链接
-> 默认浏览器
```

### 使用要求

- 已 root 的 Android 设备
- LSPosed
- 带 MiShare / 小米互传的 HyperOS 或 MIUI 设备

推荐 LSPosed 作用域：

- 系统框架 (`android`)
- 小米互传 / MiShare (`com.miui.mishare.connectivity`)
- 小米应用商店 (`com.xiaomi.market`)
- 小米浏览器 (`com.android.browser`)，如果设备上存在

### 构建

```bash
./gradlew assembleDebug
```

Debug APK 输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release 构建：

```bash
./gradlew assembleRelease
```

默认 release APK 未签名。正式分发前请自行签名。

### 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

安装后在 LSPosed 中启用模块，选择推荐作用域，然后重启手机，或至少重启相关作用域应用。

### 调试

常用日志 tag：

```text
HyperOSBrowserFix_Main
HyperOSBrowserFix_Intent
HyperOSBrowserFix_Resolver
```

关键日志：

```text
Cached Mi Share URL
Recovered URL from object field
Recovered original URL from Mi Share cache
Redirecting to
```

### 注意事项

HyperOS / MIUI 内部实现经常变化。这个模块使用了防御式 hook 和反射扫描，但不同系统版本可能仍需要适配新的类名、字段名或跳转链路。

## English

This is an LSPosed module that prevents HyperOS / MIUI Mi Share from forcing shared web links through Xiaomi Browser or Xiaomi Market.

On some devices, when Xiaomi Browser is unavailable, uninstalled, or simply not the browser the user wants to use, Mi Share may convert a received web URL into a Xiaomi Market fallback such as:

```text
market://details?id=com.android.browser
```

Xiaomi Market may then launch the user's third-party browser with only:

```text
https://
```

This module recovers the original shared URL from Mi Share's internal data and opens the real URL with the user's default browser.

### Features

- Hooks Mi Share (`com.miui.mishare.connectivity`) link-opening flows.
- Detects Xiaomi Browser / Xiaomi Market redirection intents.
- Recovers the original shared URL from Mi Share's `TapRecvData` / `TapData` object fields.
- Opens the recovered URL with the system default browser.
- Tries to avoid touching non-web intents such as files, phone links, SMS, maps, and app-specific schemes.

### Tested Environment

The following values were read from a real device over ADB:

| Item | Value |
| --- | --- |
| Manufacturer | Xiaomi |
| Device model | `25128PNA1C` |
| Device codename | `nezha` |
| Android version | `16` |
| Android SDK | `36` |
| HyperOS version name | `OS3.0` |
| HyperOS incremental version | `OS3.0.307.0.WPACNXM` |
| MIUI UI version name | `V816` |
| System build version | `16OS3.1.260514.221906302.QCPECN.S` |
| LSPosed | Tested |
| Browser tested | Via (`mark.via`) |

Observed working recovery path:

```text
Mi Share notification click
-> tap_recv_data
-> com.miui.mishare.tap.TapData.h
-> original https URL
-> default browser
```

### Requirements

- Rooted Android device
- LSPosed
- HyperOS or MIUI device with Mi Share

Recommended LSPosed scope:

- System Framework (`android`)
- Mi Share (`com.miui.mishare.connectivity`)
- Xiaomi Market (`com.xiaomi.market`)
- Xiaomi Browser (`com.android.browser`), if present on the device

### Build

```bash
./gradlew assembleDebug
```

The debug APK will be generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release build:

```bash
./gradlew assembleRelease
```

The default release APK is unsigned. Sign it before distributing a production release.

### Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then enable the module in LSPosed, select the recommended scope, and reboot the phone or restart the scoped apps.

### Debugging

Useful log tags:

```text
HyperOSBrowserFix_Main
HyperOSBrowserFix_Intent
HyperOSBrowserFix_Resolver
```

Helpful log patterns:

```text
Cached Mi Share URL
Recovered URL from object field
Recovered original URL from Mi Share cache
Redirecting to
```

### Notes

HyperOS and MIUI internals change often. This module uses defensive hooks and reflection, but some versions may require additional handling for new class names, field names, or redirection flows.

## License

MIT
