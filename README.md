# Home Assistant Mobile BLE Proxy

Android app that behaves like an ESPHome Bluetooth proxy and exposes the ESPHome native API on `6053/tcp`.

It is designed for Home Assistant users who want to run BLE proxying on an Android phone instead of on an ESP32.

## What The App Does

- Runs as a foreground service with a persistent notification.
- Hosts an ESPHome-compatible native API server.
- Supports plaintext or Noise-encrypted ESPHome API transport.
- Forwards BLE advertisements to Home Assistant.
- Supports BLE GATT connections through the ESPHome Bluetooth proxy API.
- Supports pair, unpair, and clear-cache requests through Android BLE APIs.
- Publishes `_esphomelib._tcp.local` mDNS/NSD advertisements.
- Lets you choose which network transport mDNS should use: `auto`, `wifi`, `cellular`, `vpn`, or `disabled`.
- Generates a stable default Bluetooth MAC identity and a stable default ESPHome API encryption key.
- Provides runtime logs in-app with copy, clear, and wrap toggle support.

## BLE Scanning Modes

- Foreground, screen on:
  The app uses broad callback-based BLE scanning. This is the best mode for discovery and initial learning.

- Background, screen on:
  The app keeps running through a foreground service, persistent notification, and partial wake lock. This is suitable for normal proxy use while another app is open.

- Screen off / device locked:
  The app switches to targeted Android `ScanFilter` entries built from the saved `Lock-Screen Scan Targets` list.
  This is important because broad unfiltered scans are not reliable on locked devices on stock Android and Xiaomi firmware.

- Lock-screen target learning:
  If `Auto-add matched devices` is enabled and at least one advertisement filter is enabled, the app can learn exact MAC targets from matched advertisements while the screen is on.

## Home Assistant Flow

1. Install the app.
2. Grant Bluetooth, location, and notification permissions.
3. Set battery mode to unrestricted and enable any Xiaomi-specific `Autostart` / `No restrictions` behavior.
4. Start the proxy from the home screen.
5. If you want encrypted ESPHome API transport, keep the generated key or replace it with your own 32-byte base64 key.
6. Add the device in Home Assistant through the ESPHome integration.
7. If you use WireGuard or another VPN, set mDNS interface mode to `VPN` so discovery is advertised on that transport.
8. If you need reliable scanning while the screen is locked:
   Create one or more advertisement filters.
   Enable `Auto-add matched devices` or manually add exact lock-screen targets.
   Let the app see the devices while the screen is on.
   Lock the phone after those targets are saved.

There is no Home Assistant URL or Home Assistant API token in the normal flow. Home Assistant talks to the app over the ESPHome API directly.

## Configuration Reference

- `ESPHome node name`:
  Internal ESPHome device name used by the API server and mDNS.

- `Friendly name`:
  Human-readable label shown in Home Assistant.

- `API port`:
  TCP port used by the ESPHome API server. Default is `6053`.

- `Scanner mode`:
  `Passive` uses lower-power scanning.
  `Active` uses low-latency scanning and is usually better for aggressive discovery.

- `Ad flush interval (ms)`:
  How often queued BLE advertisement updates are flushed to connected clients.

- `Ad dedup window (ms)`:
  Per-address deduplication window for discovery traffic.
  `0` disables dedup.

- `Discovery throttle interval (ms)`:
  Limits how often the same address is forwarded during discovery traffic.
  Devices unseen for 30 minutes are treated as rediscovered and forwarded immediately.

- `Watchdog check interval (ms)`:
  How often the app evaluates scanner health.

- `Low-rate checks before restart`:
  How many consecutive poor-health samples are required before scanner recovery triggers.

- `Advertisement filters`:
  Regex-based rules applied with OR logic.
  If no enabled filters exist, the app forwards all advertisements.

- `Lock-Screen Scan Targets`:
  Exact MAC addresses and/or exact device names used to build Android hardware scan filters when the screen is off.

- `Auto-add matched devices`:
  When enabled, advertisements that match at least one enabled advertisement filter can add an exact lock-screen target automatically.
  If no enabled advertisement filters exist, this feature stays idle and adds nothing.

- `Bluetooth MAC address`:
  Identity reported by the proxy.
  By default the app generates and persists a stable locally administered MAC address.

- `ESPHome API encryption key`:
  Base64-encoded 32-byte key for ESPHome Noise transport.
  Empty value means plaintext API transport.

- `mDNS advertise interface`:
  Controls which transport NSD/mDNS registration should prefer.
  `VPN` is useful when you want discovery on WireGuard instead of local Wi-Fi.

- `Auto start on boot`:
  Starts the foreground proxy service after device boot.

## Normal Operating Notes

- Screen-on broad discovery works without a saved target list.
- Screen-off scanning is intentionally target-based. Save exact devices if you expect the proxy to keep seeing them while locked.
- For Xiaomi devices, battery restrictions are often the difference between working and failing background BLE behavior.
- Pair, unpair, and clear-cache behavior depends on Android OEM BLE stack behavior.

## Screenshots

![home-2](https://github.com/user-attachments/assets/99c78bca-ed2b-403e-9414-6885be5fc779)
![home-1](https://github.com/user-attachments/assets/e2958a42-77da-4189-9a54-74df290732f9)
![setting-2](https://github.com/user-attachments/assets/5b655ca0-168e-4487-9a55-5f5b22b2b147)
![setting-1](https://github.com/user-attachments/assets/3cdcd84e-dd7c-4768-97b1-80f61ccbb920)
![filter-1](https://github.com/user-attachments/assets/af25f9fe-4f77-4657-9d35-440102067935)
![lock-scan-1](https://github.com/user-attachments/assets/e612c5ef-b538-4e4f-9a6e-1144e0436792)

## Local Build

Requirements:

- Android SDK
- Java 17 or newer
- `JAVA_HOME` configured

Examples:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:lintDebug
./gradlew :app:assembleRelease
```

You can override the Android version fields at build time:

```bash
./gradlew :app:assembleRelease \
  -PreleaseVersionName=2026.0.3 \
  -PreleaseVersionCode=20260003
```

## GitHub Release Flow

The repository includes a tag-driven GitHub Actions workflow.

- Create a tag like `v2026.0.3`
- Push the tag
- The workflow derives:
  `versionName=2026.0.3`
  `versionCode=20260003`
- It builds the Android release APK
- It creates a GitHub release for that tag
- It uploads the APK and a SHA-256 checksum file

Current behavior:

- The workflow expects Android signing secrets and produces a signed installable release APK.

Required GitHub Actions repository secrets:

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

Generate the base64 keystore payload with:

```bash
base64 -w 0 android-release.jks > android-release.jks.base64
```

## Project Status

Implemented:

- ESPHome API hello, ping, disconnect, device info, and entity listing completion
- BLE advertisement forwarding
- BLE GATT connect, discover, read, write, descriptor access, and notifications
- BLE pair, unpair, and clear-cache requests
- mDNS interface selection
- Runtime log viewer and copy support
- Lock-screen target management and auto-learning

Known practical limitation:

- Broad BLE discovery while the device is locked is not reliable on stock Android. Use exact lock-screen scan targets for locked scanning.
