# HyperOS MiShare Browser Fix

## 中文说明

这是一个 LSPosed 模块，用来修复 HyperOS / MIUI 里 MiShare（小米互传）接收网页链接时，被强制绕到小米浏览器或小米应用商店的问题。

我遇到的情况是：MiShare 收到一个网页链接后，没有把原始 URL 直接交给默认浏览器，而是先变成类似这样的商店跳转：

```text
market://details?id=com.android.browser
```

之后小米应用商店会拉起我设置的第三方浏览器，但传过去的地址只剩：

```text
https://
```

也就是说，浏览器确实打开了，链接没了。这个模块做的事就是在这条链路中把原始链接找回来，然后交给用户自己的默认浏览器。

### 功能

- Hook MiShare (`com.miui.mishare.connectivity`) 的链接打开流程。
- 拦截指向小米浏览器 / 小米应用商店的网页跳转。
- 从 MiShare 的 `TapRecvData` / `TapData` 对象字段里恢复原始 URL。
- 用系统默认浏览器打开恢复后的链接。
- 尽量不影响非网页 Intent，比如文件、电话、短信、地图和应用私有 scheme。

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

实测可用的恢复路径：

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

### 安装

普通用户建议直接到 [Releases](https://github.com/MMMMatt/HyperOS-MiShare-Browser-Fix/releases) 下载已签名的 APK。

安装后，在 LSPosed 中启用模块，选择推荐作用域，然后重启手机。只重启相关作用域应用有时也够，但重启最省心。

### 构建

Debug 构建：

```bash
./gradlew assembleDebug
```

Release 构建：

```bash
./gradlew assembleRelease
```

默认生成的 release APK 没有签名。如果要自己分发，需要自行签名。

### 调试

常用日志 tag：

```text
HyperOSBrowserFix_Main
HyperOSBrowserFix_Intent
HyperOSBrowserFix_Resolver
```

比较有用的日志：

```text
Cached Mi Share URL
Recovered URL from object field
Recovered original URL from Mi Share cache
Redirecting to
```

### 注意事项

HyperOS / MIUI 的内部实现经常变化。这个模块现在能处理我手上的设备和系统版本，但其他版本可能会换类名、字段名，或者换一条跳转链路。如果失效，通常需要重新看 LSPosed / logcat 日志定位。

## English

This is an LSPosed module for a small but annoying HyperOS / MIUI behavior: Mi Share may route received web links through Xiaomi Browser or Xiaomi Market instead of handing the original URL to the user's default browser.

The problem I ran into looked like this. Mi Share received a normal web link, but before opening it, the link was converted into a Xiaomi Market fallback:

```text
market://details?id=com.android.browser
```

Xiaomi Market then launched my third-party browser, but the URL passed to the browser was only:

```text
https://
```

So the browser opened, but the actual link was gone. This module recovers the original URL from Mi Share's internal data and sends that URL to the default browser.

### Features

- Hooks Mi Share (`com.miui.mishare.connectivity`) link-opening flows.
- Intercepts web redirects aimed at Xiaomi Browser or Xiaomi Market.
- Recovers the original URL from Mi Share's `TapRecvData` / `TapData` object fields.
- Opens the recovered URL with the system default browser.
- Tries to leave non-web intents alone, including files, phone links, SMS, maps, and app-specific schemes.

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

Observed recovery path:

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

### Install

For normal use, download the signed APK from [Releases](https://github.com/MMMMatt/HyperOS-MiShare-Browser-Fix/releases).

After installing it, enable the module in LSPosed, select the recommended scope, then reboot the phone. Restarting the scoped apps may also be enough in some cases, but a reboot is the least fussy option.

### Build

Debug build:

```bash
./gradlew assembleDebug
```

Release build:

```bash
./gradlew assembleRelease
```

The default release APK is unsigned. If you want to distribute your own build, sign it with your own keystore.

### Debugging

Useful log tags:

```text
HyperOSBrowserFix_Main
HyperOSBrowserFix_Intent
HyperOSBrowserFix_Resolver
```

Useful log lines:

```text
Cached Mi Share URL
Recovered URL from object field
Recovered original URL from Mi Share cache
Redirecting to
```

### Notes

HyperOS and MIUI internals change often. This module works on the device and build listed above, but other versions may use different class names, field names, or redirect flows. If it breaks, the next step is to inspect LSPosed / logcat output and adjust the hook.

## License

MIT
