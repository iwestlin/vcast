# Vcast

Vcast 是一个 Android DLNA/UPnP Media Renderer。启动服务后，同一局域网内的播放器可以把媒体投到这台手机；Vcast 会按投屏 URL 正则匹配任务，并把命中的任务转发到指定 HTTP endpoint。

## 功能

- DLNA MediaRenderer：AVTransport、RenderingControl、ConnectionManager
- SSDP 广播、HTTP/SOAP/GENA、前台 Service
- 自定义设备名
- 按投屏 URL 正则匹配任务，`*` 表示全部
- 转发 `{room, passkey, title?, extra?}` JSON
- 投屏日志和 HTTP 响应日志
- 无默认 endpoint、passkey 或 keyword

## 设置

| 字段 | 说明 |
| --- | --- |
| Device name | 局域网内显示的设备名 |
| Regex for cast URL | 匹配投屏 URL 的正则，忽略大小写；`*` 表示全部 |
| Task endpoint | 接收任务的 HTTP 地址 |
| Passkey | 可选，转发时放入 `passkey` 字段 |

修改设置后需要停止并重新启动服务才会生效。

## 转发格式

`room` 是命中的投屏 URL。`title` 仅在媒体元数据包含 `dc:title` 时发送。`extra` 包含原始 DIDL metadata 及常见解析字段。

```json
{
  "room": "http://example.com/video.mp4",
  "passkey": "",
  "title": "Example",
  "extra": {
    "metadata": "<DIDL-Lite ...>",
    "creator": "Example Creator",
    "artist": "Example Artist",
    "duration": "0:01:30.000"
  }
}
```

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
git tag -a v1.0.0 -m "Vcast v1.0.0"
git push origin v1.0.0
```

## 包名

`com.vcast`
