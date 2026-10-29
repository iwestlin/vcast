# Vcast

简体中文 | [English](README.en.md)

[![Release](https://img.shields.io/github/v/release/iwestlin/vcast)](https://github.com/iwestlin/vcast/releases)
[![Build](https://github.com/iwestlin/vcast/actions/workflows/android.yml/badge.svg)](https://github.com/iwestlin/vcast/actions/workflows/android.yml)
[![License](https://img.shields.io/github/license/iwestlin/vcast)](LICENSE)

Vcast 是一个 Android DLNA/UPnP Media Renderer。启动服务后，同一局域网内的播放器可以把媒体投到这台手机（甚至可以在蜂窝网络下开启热点自己投给自己）；Vcast 会按投屏 URL 正则匹配任务，并把命中的任务转发到指定 HTTP endpoint。

## 截图

| 设置 | 日志 |
| --- | --- |
| <img src="docs/screenshots/screenshot-1.jpg" width="300" alt="设置界面"> | <img src="docs/screenshots/screenshot-2.jpg" width="300" alt="日志界面"> |

## 功能

- DLNA MediaRenderer：AVTransport、RenderingControl、ConnectionManager
- SSDP 广播、HTTP/SOAP/GENA、前台 Service
- 自定义设备名
- 中英文界面，可跟随系统或手动切换
- 按投屏 URL 正则匹配任务，`*` 表示全部
- 转发 `{room, passkey, title?, extra?}` JSON
- 最近投屏链接支持截断显示并一键复制
- 投屏日志和 HTTP 响应日志（关键事件高亮）
- 自定义 endpoint、passkey 或 keyword

## 快速开始

1. 安装并打开 Vcast；Android 13+ 建议授予通知权限，以便显示前台服务通知。
2. 在 Task endpoint 填写接收任务的 HTTP 地址，按需设置投屏 URL 正则和 passkey，然后启动服务。
3. 在同一局域网内打开支持 DLNA 的播放器，选择 Vcast 设备投屏。
4. 命中的任务会以 JSON POST 到你配置的 endpoint。

## 设置

| 字段 | 说明 |
| --- | --- |
| Language | 跟随系统、English 或中文 |
| Device name | 局域网内显示的设备名 |
| Regex for cast URL | 匹配投屏 URL 的正则，忽略大小写；`*` 表示全部 |
| Task endpoint | 接收任务的 HTTP 地址 |
| Passkey | 可选，转发时放入 `passkey` 字段 |

修改设备或任务设置后需要停止并重新启动服务才会生效；切换语言后重新启动服务可同步更新通知文本。

## 转发格式

`room` 是命中的投屏 URL。`title` 仅在媒体元数据包含 `dc:title` 时发送。`extra` 包含原始 DIDL metadata 及常见解析字段。

```js
{
  "room": "http://example.com/video.mp4",
  "passkey": "", // 可选，设置自定义passkey以鉴别请求是否真实
  "title": "Example",
  "extra": {
    "metadata": "<DIDL-Lite ...>",
    "creator": "Example Creator",
    "artist": "Example Artist",
    "duration": "0:01:30.000"
  }
}
```

## 常见问题

- **播放器找不到设备**：确认手机和播放器在同一局域网，且路由器未开启 AP 隔离；部分路由器需要允许组播/IGMP。
- **没有局域网也想投屏**：开启手机热点，把播放器连接到该热点，即可投屏到手机本身。
- **修改设置后不生效**：停止并重新启动服务。
- **收不到转发任务**：检查 endpoint 是否可达、投屏 URL 正则是否匹配（`*` 表示全部）、passkey 是否与接收端一致。

## 隐私

Vcast 不收集、不上传任何数据。设置仅保存在本机，投屏链接与元数据只会发送到你自行配置的 endpoint，日志仅保存在内存中。

## 构建

要求 JDK 17 和 Android SDK 35。

```bash
./gradlew assembleDebug
```

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions

- 修改 `app/**`、Gradle 配置或 workflow 后 push 到 `master`/`main`，会构建 APK 并上传 Actions artifact。
- 推送 `v*` tag 会构建并发布 GitHub Release，附件名为 `vcast.apk`。

```bash
git tag -a v1.0.1 -m "Vcast v1.0.1"
git push origin v1.0.1
```

## 包名

`com.viegg.vcast`

## 致谢

本项目灵感来自 https://github.com/xfangfang/Macast

## 许可证

[MIT](LICENSE)
