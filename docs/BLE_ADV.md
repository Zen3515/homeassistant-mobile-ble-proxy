# BLE Advertisement Proxy (`ble_adv`)

## What it's for

Some BLE devices have no GATT control characteristic at all — they are controlled purely by
broadcasting specifically-crafted advertising packets at them (e.g. the FanLamp Pro / LampSmart
Pro family of ceiling fan and light controllers). Home Assistant's
[`ha-ble-adv`](https://github.com/NicoIIT/esphome-ble_adv_proxy) integration talks to this class
of device through an ESPHome node that exposes a small set of custom "user services" for sending
and receiving raw advertisement bytes.

This app can act as that ESPHome node. `BleAdvProxyManager` and `BleAdvertiseManager`
(`app/src/main/java/.../proxy/`) are an Android re-implementation of the relevant parts of
[`NicoIIT/esphome-ble_adv_proxy`](https://github.com/NicoIIT/esphome-ble_adv_proxy)'s
`ble_adv_proxy.cpp`, so the same Home Assistant integration works against a phone instead of an
ESP32.

This is a distinct feature from the app's normal BLE-proxy behavior (forwarding discovered
advertisements for entity tracking, and GATT proxying — see
[`GATT_CONNECTION_GUIDELINES.md`](GATT_CONNECTION_GUIDELINES.md)). Plain advertisement forwarding
works regardless of this setting; `ble_adv` additionally lets Home Assistant *transmit* raw
advertising payloads through the phone's radio.

## Enabling it

It is off by default, so a plain Bluetooth proxy install exposes nothing extra to Home Assistant.
Turn on `BLE advertisement proxy (ble_adv)` in the app's settings screen. Once enabled, the ESPHome
node additionally advertises:

- A `text_sensor` (object id `ble_adv_proxy_name`) identifying the adapter to `ha-ble-adv`.
- Three ESPHome "user services" (`EXECUTE_SERVICE_REQUEST` entities) that `ha-ble-adv` calls:
  `setup_svc_v0`, `adv_svc`, `adv_svc_v1`.

Add the integration in Home Assistant via **Settings > Devices & Services > BLE ADV**, pointing it
at this device. If Home Assistant ever reports the adapter briefly disappearing (e.g. after the
app was killed and restarted), reload it from the same integration page.

## Services Home Assistant calls (command path)

Defined in `EspHomeApiServer.BLE_ADV_SERVICES` and dispatched from `handleExecuteService`:

- **`setup_svc_v0(ignored_duration, ignored_cids, ignored_macs)`**
  Configures `BleAdvProxyManager`: the receive-side dedup window (ms), a set of BLE company IDs to
  ignore, and a set of MAC addresses to ignore. Must be called once before the proxy will forward
  any received raw advertisement — this is the `setup_done` gate.

- **`adv_svc(raw, duration)`** (v0, legacy)
  Broadcasts the given raw hex advertisement payload, split into `REPEAT_NB` (3) repeats spread
  over `duration` ms. Also registers `raw` as a receive-side dupe so the proxy doesn't immediately
  echo its own outgoing command back to Home Assistant as an incoming event.

- **`adv_svc_v1(raw, duration, repeat, ignored_advs, ignored_duration)`**
  Same idea, with explicit `repeat` count and an explicit list of payloads (`ignored_advs`) to
  register as dupes, each expiring after `ignored_duration` ms.

## Receiving advertisements (device -> Home Assistant)

1. The BLE scanner callback (which runs on Android's main thread) calls
   `EspHomeApiServer.publishAdvertisement`, which does a cheap pre-filter in
   `handleBleAdvRawRecv` and hands the packet to a bounded channel (`bleAdvEvents`, capacity 512).
   This hop off the main thread exists so the eventual socket write never happens on the callback
   thread (see [[android-ble-main-thread-gotcha]] — writing sockets from a BLE callback throws
   `NetworkOnMainThreadException`).
2. A dedicated coroutine (`bleAdvEventJob`) drains that channel and calls
   `processBleAdvRawRecv`, which asks `BleAdvProxyManager.onRawRecv` to apply, in order:
   the company-ID ignore list, the MAC ignore list, then the dedup window (default 20s, or
   whatever `setup_svc_v0` configured).
3. Surviving packets are broadcast to every client subscribed to Home Assistant services as the
   custom event `esphome.ble_adv.raw_adv`, with fields `raw` (hex payload) and `orig` (source MAC).

This only runs at all when `bleAdvProxyEnabled` is true, `setup_svc_v0` has been called, and at
least one client has subscribed to Home Assistant services.

## Sending advertisements (Home Assistant -> device)

`BleAdvertiseManager.enqueue` queues raw hex payloads and a background pump
(`pump()`/`sendPacketAndWait`) transmits them one at a time:

- **Coalescing**: if the same payload is re-enqueued while already queued (Home Assistant tends to
  resend commands back-to-back), the pending window is extended instead of restarting the
  advertise/stop cycle.
- **Scan/advertise mutual exclusion**: BLE scanning is paused for the duration of a send burst
  (`onPauseScanning`/`onResumeScanning`, wired to `scannerEngine.stop()`/`start()`), because a
  continuously running scan competes with advertising for radio time and can starve outgoing
  packets on shared antennas.
- **Window sizing**: Android's advertising interval floor is 100ms
  (`ADVERTISE_MODE_LOW_LATENCY`), so the ESP32's literal per-repeat timing doesn't translate
  directly. Instead the manager advertises once across a single window sized from
  `duration * repeat`, clamped to 350ms–3000ms, so several advertising events actually fit inside
  it.
- **Framing**: Android's public advertiser API has no raw-bytes mode — `AdvertiseData` is always
  reserialized from structured fields, and the platform unconditionally injects its own 3-byte
  Flags AD structure. Two framings are used, matched to what the target hardware actually accepts:
  - For exactly-31-byte payloads (the FanLamp Pro / LampSmart Pro command size), the trailing 26
    bytes are re-encoded as a list of thirteen 16-bit service UUIDs and sent as a **connectable
    `ADV_IND`** — reproducing the framing the vendor Android app itself uses, since a 4-byte
    manufacturer-data header wouldn't fit under the 31-byte limit alongside Android's Flags
    structure, but a 2-byte service-UUID-list header does (see
    `BleAdvertiseManager.buildServiceUuidAdvertiseData` for the byte-order math). See also
    [[fanlamp-android-adv-framing]].
  - Anything else falls back to reconstructing manufacturer data / service data / service UUIDs
    from the payload's AD structures and sends a **non-connectable `ADV_NONCONN_IND`**
    (`buildAdvertiseData`). This is not guaranteed to be a byte-for-byte reproduction of arbitrary
    input, only of the AD types the vendor devices actually use.

## Where to look in code

| Concern | File |
| --- | --- |
| ESPHome service registration, `EXECUTE_SERVICE_REQUEST` dispatch, receive-event wiring | `proxy/EspHomeApiServer.kt` |
| Ignore lists, dedup window, `setup_done` gate | `proxy/BleAdvProxyManager.kt` |
| Send queue, coalescing, scan pause/resume, AD framing | `proxy/BleAdvertiseManager.kt` |
| The `bleAdvProxyEnabled` setting itself | `proxy/ProxySettings.kt`, `proxy/SettingsRepository.kt` (persists as `ble_adv_proxy_enabled`), `proxy/ProxySettingsJsonCodec.kt` |
| Settings-screen toggle and its description text | `MainActivity.kt` (search for `ble_adv`) |
