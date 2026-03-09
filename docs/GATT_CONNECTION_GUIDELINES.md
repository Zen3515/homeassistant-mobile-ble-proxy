# GATT Connection Context and Guidelines

*This document serves as a permanent architectural decision record regarding Android BLE Proxy GATT connections, specifically regarding Auto-Pairing, Service Discovery, and Device Stability.*

## The Problem: Strict Bluetooth Stacks (e.g. Kawasaki Motorcycles)
During the development and testing of the `ManagedTargetDevice` Auto-Pairing feature for `homeassistant-mobile-ble-proxy`, we encountered a persistent bug where certain Bluetooth devices (such as the Kawasaki motorcycle stack) would instantly drop the connection exactly when Home Assistant began communicating with them.

Logs revealed the device was intentionally terminating the link (`status=8` / `GATT_CONN_TIMEOUT` / `HCI_ERR_CONN_TIMEOUT`). Connecting with standard low-energy tools (like nRF Connect) did not reproduce the issue unless specific connection steps were taken.

## Root Cause Analysis and Findings
The core issue stems from overlapping GATT operations triggered simultaneously by Home Assistant's native connection flow and Android's asynchronous BLE queue.

### 1. Concurrent `discoverServices()` calls crash Link Layers
When Home Assistant commands the proxy to connect to a device, the ESPHome API protocol natively schedules a `get_services` (Service Discovery) request for the node.
Simultaneously, our initial "Auto-Pairing" implementation was enthusiastically intercepting the connection event and calling `BluetoothGattProxyManager.requestPairing(...)`. 

**The Flaw:**
The original `requestPairing(...)` implementation was designed to *force* a secondary `discoverServices()` sweep if the target device was already bonded (as a fallback way to initialize the cache). This meant the Android OS was dispatching two concurrent `discoverServices()` sweeps while also negotiating the `MTU` size. Strict embedded BLE stacks (like Kawasaki's) could not handle these overlapping queue directives and panicked, tearing down the connection entirely.

### 2. Delaying the `MTU` Request breaks Android's AES Handshake
Our first attempted fix involved deferring the proxy's initial MTU request until *after* all Service Discoveries had finished. 
**This did not work.**
Deferring the MTU size negotiation on an already-bonded device interrupted Android's inherent low-level AES encryption handshake. Because the proxy connection is designed to transparently pass Home Assistant's API commands immediately, holding the MTU artificially backfired and caused Home Assistant to hit a startup-response timeout.

## The Architectural Solution: `createBondIfUnbonded`
The final, working solution requires the proxy to act passively regarding Service Discoveries while aggressively triggering the Android OS-level pairing intent.

- **MTU remains synchronous:** The proxy requests the required MTU immediately upon the `STATE_CONNECTED` callback. This fulfills Home Assistant's expectation and does not interrupt encryption handshakes.
- **Service Discovery is un-touched:** The proxy never manually triggers `discoverServices()` for an auto-pairing event. It trusts Home Assistant's API to prompt it when ready.
- **Safe Bonding Interception (`createBondIfUnbonded`):** The Auto-Pair interceptor in `EspHomeApiServer` now explicitly calls a restricted helper:
    1. It checks if the `BluetoothDevice` is currently `BOND_NONE`.
    2. If true, it calls `device.createBond()`. This naturally spawns the Android OS PIN/Pairing dialog while pausing standard GATT traffic, securing the link gracefully.
    3. If false (already bonded), it cleanly returns and *does nothing*. 

## Guidelines for Future Contributors
1. **Never trigger `discoverServices()` proactively on behalf of Home Assistant.** Let the ESPHome API orchestrate when services should be discovered to prevent fatal Android queue overlaps on embedded targets.
2. **Never artificially delay the MTU Request if the connection state is marked as connected.**
3. **Handle Bonding via `createBondIfUnbonded(address)`**. Do not use standard GATT interceptors to manage Android's bond state during active proxy sessions, as it creates race conditions.
