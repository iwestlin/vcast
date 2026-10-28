# Vcast

English | [简体中文](README.md)

Vcast is an Android DLNA/UPnP Media Renderer. Once the service is running, players on the same LAN can cast media to this phone. Vcast matches casts by a URL regex and forwards matching tasks to a configured HTTP endpoint.

## Features

- DLNA MediaRenderer: AVTransport, RenderingControl, ConnectionManager
- SSDP advertising, HTTP/SOAP/GENA, foreground service
- Custom device name
- English and Chinese UI, following the system or manually switchable
- Match casts by cast URL regex; `*` matches everything
- Forward `{room, passkey, title?, extra?}` JSON
- Last cast link is truncated and can be copied with one tap
- Cast log and HTTP response log, with key events highlighted
- No default endpoint, passkey, or keyword

## Settings

| Field | Description |
| --- | --- |
| Language | Follow system, English, or Chinese |
| Device name | Name shown on the local network |
| Regex for cast URL | Case-insensitive regex for the cast URL; `*` matches everything |
| Task endpoint | HTTP URL that receives matched tasks |
| Passkey | Optional; sent as the `passkey` field |

Restart the service after changing device or task settings. Restart the service after changing the language to update notification text.

## Payload

`room` is the matched cast URL. `title` is sent only when the media metadata contains `dc:title`. `extra` contains the raw DIDL metadata and common parsed fields.

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

## Build

Requires JDK 17 and Android SDK 35.

```bash
./gradlew assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions

- Pushing changes to `app/**`, Gradle configuration, or the workflow on `master`/`main` builds the APK and uploads it as an Actions artifact.
- Pushing a `v*` tag builds and publishes a GitHub Release with `vcast.apk` as the asset.

```bash
git tag -a v1.0.1 -m "Vcast v1.0.1"
git push origin v1.0.1
```

## Package

`com.viegg.vcast`
