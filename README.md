# Home Assistant Mobile BLE Proxy

[![License](https://img.shields.io/github/license/zen3515/homeassistant-mobile-ble-proxy.svg?style=for-the-badge&color=yellow)](LICENSE)
![GitHub all releases](https://img.shields.io/github/downloads/zen3515/homeassistant-mobile-ble-proxy/total?style=for-the-badge&logo=appveyor)

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
  The app switches to targeted Android `ScanFilter` entries built from the saved `Managed Target Devices` list if Lock-Screen Scanning is enabled for them.
  This is important because broad unfiltered scans are not reliable on locked devices on stock Android and Xiaomi firmware.

- Lock-screen target learning:
  If `Auto-add matched devices` is enabled and at least one advertisement filter is enabled, the app can learn exact MAC targets from matched advertisements while the screen is on.

## Home Assistant Flow

1. Install the app.
2. Grant Bluetooth, location, and notification permissions.
3. Set battery mode to unrestricted and enable any Xiaomi-specific `Autostart` / `No restrictions` behavior.
4. Start the proxy from the home screen.
5. If you want encrypted ESPHome API transport, keep the generated key or replace it with your own 32-byte base64 key.
6. If your BLE device requires a PIN or passkey, pair it on the Android phone first and complete the system pairing dialog there. The proxy reuses Android's stored bond on reconnect.
7. Add the device in Home Assistant through the ESPHome integration.
8. If you use WireGuard or another VPN, set mDNS interface mode to `VPN` so discovery is advertised on that transport.
9. If you need reliable scanning while the screen is locked:
   Create one or more advertisement filters.
   Enable `Auto-add matched devices` or manually add exact managed target devices with Lock-Screen Scanning enabled.
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

- `Managed Target Devices`:
  A consolidated list of exact MAC addresses and/or exact device names. These determine behavior based on two toggles:
  - **Lock-Screen Scanning**: Used to build Android hardware scan filters when the screen is off.
  - **Auto-Pair Device**: Forces the Android OS to immediately prompt for Bluetooth pairing/bonding natively upon connection, preventing GATT cache drops on strict BLE devices.

- `Auto-add matched devices`:
  When enabled, advertisements that match at least one enabled advertisement filter can add an exact managed target device automatically.
  If no enabled advertisement filters exist, this feature stays idle and adds nothing.

- `Bluetooth MAC address`:
  Identity reported by the proxy.
  By default the app generates and persists a stable locally administered MAC address.

- `ESPHome API encryption key`:
  Base64-encoded 32-byte key for ESPHome Noise transport.
  Empty value means plaintext API transport.

- `Verbose GATT packet logs`:
  Keeps normal GATT state logs enabled while optionally adding per-packet notify data lines to the runtime log.
  Default is `off` because it gets noisy quickly.

- `mDNS advertise interface`:
  Controls which transport NSD/mDNS registration should prefer.
  `VPN` is useful when you want discovery on WireGuard instead of local Wi-Fi.

- `Auto start on boot`:
  Starts the foreground proxy service after device boot.

## Normal Operating Notes

- Screen-on broad discovery works without a saved target list.
- Screen-off scanning is intentionally target-based. Save exact devices via `Managed Target Devices` if you expect the proxy to keep seeing them while locked.
- For Xiaomi devices, battery restrictions are often the difference between working and failing background BLE behavior.
- Pair, unpair, and clear-cache behavior depends on Android OEM BLE stack behavior.
- For strict Bluetooth devices (like certain motorcycle stacks) that require a PIN or passkey, you can enable `Auto-Pair Device` in the target list so the proxy triggers an OS-level pairing prompt natively before Home Assistant drops the connection. After Android stores the bond, the proxy safely avoids redundant discoveries.

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
- Managed Target Devices configuration for lock-screen scanning and immediate auto-pairing

Known practical limitation:

- Broad BLE discovery while the device is locked is not reliable on stock Android. Use exact Lock-Screen Scanning toggles for locked scanning.
