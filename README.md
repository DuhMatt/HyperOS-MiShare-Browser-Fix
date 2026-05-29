# HyperOS MiShare Browser Fix

当前版本：`1.1`

## 中文说明

这是一个 LSPosed 模块，用来修复 HyperOS / MIUI 里 MiShare（小米互传）接收网页链接时，被强制绕到小米浏览器或小米应用商店的问题。

默认情况下，MiShare 收到网页链接后不会把原始 URL 交给用户设置的第三方浏览器。它通常会优先拉起小米浏览器；如果系统检测不到小米浏览器，就会跳到小米应用商店里的小米浏览器下载页，例如：

```text
market://details?id=com.android.browser
```

这个模块做的事就是在这条链路中把原始链接找回来，然后交给用户自己的默认浏览器。

从 `1.1` 开始，模块也会处理设置里的“管理小米路由”入口：当 Wi-Fi 详情页把路由器后台地址强制交给小米浏览器时，模块会改为使用用户设置的默认浏览器打开，例如 `http://192.168.1.1`。

### 功能

- Hook MiShare (`com.miui.mishare.connectivity`) 的链接打开流程。
- 拦截指向小米浏览器 / 小米应用商店的网页跳转。
- 从 MiShare 的 `TapRecvData` / `TapData` 对象字段里恢复原始 URL。
- 修复设置 Wi-Fi 页面“管理小米路由”强制打开小米浏览器的问题。
- 用系统默认浏览器打开恢复后的链接。
- 尽量不影响非网页 Intent，比如文件、电话、短信、地图和应用私有 scheme。

### 版本更新

#### 1.1

- 新增对设置 Wi-Fi 详情页“管理小米路由”的支持。
- 当系统尝试用小米浏览器打开 `miwifi.com` 或私有网关地址时，改用系统默认浏览器。
- 已在 Via (`mark.via`) 默认浏览器和 `http://192.168.1.1` 路由器后台入口上验证。

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
- 设置 (`com.android.settings`)，用于 Wi-Fi 详情页的“小米路由”入口

### 安装

普通用户建议直接到 [Releases](https://github.com/DuhMatt/HyperOS-MiShare-Browser-Fix/releases) 下载已签名的 APK。

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

Current version: `1.1`

This is an LSPosed module for a small but annoying HyperOS / MIUI behavior: Mi Share may route received web links through Xiaomi Browser or Xiaomi Market instead of handing the original URL to the user's default browser.

By default, Mi Share does not pass the original URL to the third-party browser selected by the user. It usually tries to open Xiaomi Browser first. If Xiaomi Browser is not detected, it falls back to the Xiaomi Browser download page in Xiaomi Market, for example:

```text
market://details?id=com.android.browser
```

This module recovers the original URL from Mi Share's internal data and sends that URL to the default browser instead.

Starting with `1.1`, it also handles the "Manage Xiaomi router" entry in Wi-Fi details. If Settings tries to open the router admin page through Xiaomi Browser, the module redirects it to the user's default browser instead, for example `http://192.168.1.1`.

### Features

- Hooks Mi Share (`com.miui.mishare.connectivity`) link-opening flows.
- Intercepts web redirects aimed at Xiaomi Browser or Xiaomi Market.
- Recovers the original URL from Mi Share's `TapRecvData` / `TapData` object fields.
- Fixes the Wi-Fi details "Manage Xiaomi router" entry when it is forced to Xiaomi Browser.
- Opens the recovered URL with the system default browser.
- Tries to leave non-web intents alone, including files, phone links, SMS, maps, and app-specific schemes.

### Changelog

#### 1.1

- Added support for the Wi-Fi details "Manage Xiaomi router" entry.
- Redirects `miwifi.com` and private gateway router admin URLs to the system default browser when Xiaomi Browser is forced.
- Verified with Via (`mark.via`) as the default browser and `http://192.168.1.1` as the router admin entry.

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
- Settings (`com.android.settings`), for the Wi-Fi details Xiaomi router entry

### Install

For normal use, download the signed APK from [Releases](https://github.com/DuhMatt/HyperOS-MiShare-Browser-Fix/releases).

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
