# Smart Door Lock: Android app (Stage 3)

This is a native Kotlin app built with Jetpack Compose and Material 3. It talks to the HC-05 over **Bluetooth Classic SPP/RFCOMM** and does not use BLE or GATT anywhere.

> **Status (Stage 3)**
> - Debug and release APKs build here with no errors: `BUILD SUCCESSFUL`, lint 0 errors and 0 warnings in both modules.
> - The protocol unit tests pass (5/5).
> - **Not yet tested on a real phone with the HC-05.** Every Bluetooth result below is a procedure for you to run.
> - Voice enrollment and verification arrive in **Stage 4**. Until then the voice screen says the model is not installed. It never shows a fake result and never sends `VOICE_OK`.

## 1. Modules and files

| Path | Purpose |
|---|---|
| `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml` | Gradle setup with exact pinned versions |
| `bluetooth/` | Android library module, with no UI |
| `bluetooth/.../SppConnectionManager.kt` | RFCOMM connect with timeout and secure-then-insecure fallback, reader coroutine, line framing, `send` and `sendAndAwait`, STATUS handshake, link-loss detection, auto-reconnect |
| `bluetooth/.../ClassicDiscovery.kt` | Paired-device list, Classic discovery (`startDiscovery` / `ACTION_FOUND`), pairing (`createBond`), Bluetooth on/off state |
| `bluetooth/.../McuProtocol.kt` | `McuCommand` (VOICE_OK, VOICE_FAIL, LOCK, STATUS) and `McuStatus` (LOCKED, WAITING_PIN, UNLOCKED, ACCESS_DENIED), plus `LineAssembler` |
| `bluetooth/.../BluetoothModels.kt` | SPP UUID, timeouts, `ConnectionState`, `BtError` with user-facing messages, `DeviceInfo` |
| `bluetooth/.../BluetoothPermissions.kt` | Runtime permissions for each Android version |
| `bluetooth/src/main/AndroidManifest.xml` | Bluetooth permissions; they merge into the app automatically |
| `bluetooth/src/test/.../McuProtocolTest.kt` | Unit tests: command bytes, strict status parsing, split, multiple and overlong lines |
| `app/` | The application |
| `app/.../SmartDoorLockApp.kt` | Owns the single connection for the whole process; remembers the last device address |
| `app/.../DoorLockViewModel.kt` | UI actions: connect, lock, status, discovery, pairing, debug console |
| `app/.../ui/MainScreen.kt` | Main screen (see below) |
| `app/.../ui/BluetoothScreen.kt` | Permissions, Bluetooth on/off, connection checklist, paired list, discovery and pairing |
| `app/.../ui/VoiceScreen.kt` | Stage 3 placeholder: honest "model not installed" screen |
| `app/.../ui/Labels.kt`, `Components.kt`, `theme/Theme.kt` | Status texts and colours, shared cards, theme |
| `app/src/main/res/xml/data_extraction_rules.xml` | Excludes all app data from cloud backup and device transfer |

`audio/`, `speaker_verification/` and `assets/` are added in Stage 4.

## 2. Exact versions

| Item | Version |
|---|---|
| Gradle (wrapper) | 9.9.1 |
| Android Gradle Plugin | 9.5.1 (built-in Kotlin) |
| Kotlin / Compose compiler plugin | 2.4.21 |
| JDK | 17 or newer (the build here used Android Studio's bundled JBR 25) |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 (Android 8.0+) |
| Compose BOM | 2026.09.00 (Material 3, UI, material-icons-core) |
| androidx.core:core-ktx | 1.19.1 |
| androidx.activity:activity-compose | 1.13.0 |
| androidx.lifecycle (runtime-ktx, runtime-compose, viewmodel-compose) | 2.11.0 |
| kotlinx-coroutines-android | 1.11.0 |
| junit | 4.13.2 |

## 3. Build and install

### Android Studio
1. **File → Open** and choose the `Android/` folder. Studio creates `local.properties` with your SDK path.
2. Wait for Gradle sync to finish, then **Run ▶** on a real phone. The emulator has no Bluetooth Classic radio.

### Command line
```bash
gradlew assembleDebug
gradlew :bluetooth:testDebugUnitTest
gradlew lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- The output is `app/build/outputs/apk/debug/app-debug.apk`.
- `assembleRelease` produces an unsigned APK. To install a release build, sign it with your own key (**Build → Generate Signed APK**).

## 4. How the Bluetooth part works

**Three separate levels** are shown on both screens. "Connected" is never shown unless the RFCOMM socket really opened.
1. **Paired.** The device is bonded with the phone (Android Settings, or *Find new devices → Pair* in the app).
2. **RFCOMM socket open.** This uses `createRfcommSocketToServiceRecord(00001101-0000-1000-8000-00805F9B34FB)`.
   - If that fails, the app tries `createInsecureRfcommSocketToServiceRecord`.
   - The connect attempt is limited by a 12 s watchdog.
3. **Serial communication confirmed.** Right after connecting, the app sends `STATUS\n` and waits 3 s for a valid reply.
   - If no reply comes, the screen shows *"Link open, no response"* and explains the likely causes: wrong HC-05 baud rate, TX/RX swapped, or the firmware not running.

### Data rules
- Every command is ASCII followed by `\n`.
- Incoming bytes are reassembled into lines. Partial, multiple, CRLF and overlong lines are all handled.
- Only exact tokens are accepted as status.
- The keypad PIN never passes through the app.

### Link loss and reconnection
- The app detects link loss from read errors, write errors, `ACTION_ACL_DISCONNECTED` and Bluetooth being turned off.
- Door status then becomes *Unknown*. The firmware keeps the door locked.
- After an **unexpected** loss the app retries 3 times, waiting 2 s, 4 s and 6 s.
- A user **Disconnect** never reconnects automatically.

### Errors and their messages

| Error | When |
|---|---|
| Bluetooth not supported | The phone has no adapter |
| Bluetooth disabled | The adapter is off. A *Turn on Bluetooth* button is shown. |
| Permission denied | Nearby devices or Location was refused. *Open app settings* is offered once it has been denied permanently. |
| Device not paired | The chosen device is not bonded |
| Device unavailable | The address is invalid or the device is unknown |
| Connection failed | Both RFCOMM attempts failed (powered off, out of range, or already connected to another phone) |
| Timeout | No RFCOMM answer within 12 s |
| Socket closed | The link dropped after connecting |
| Write failed | A send failed because the socket closed |
| No MCU response | The socket is open but the ATmega32 did not answer STATUS |
| Discovery failed | Discovery could not start. On Android ≤ 11 this usually means Location is off. |
| Pairing failed | Wrong PIN or pairing cancelled |

## 5. Permissions

| Android version | Runtime permissions | Why |
|---|---|---|
| 12+ (API 31+) | `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (`neverForLocation`) | Paired list, pairing, RFCOMM, discovery |
| 8–11 (API 26–30) | `ACCESS_FINE_LOCATION` (+ install-time `BLUETOOTH`, `BLUETOOTH_ADMIN`) | Android requires Location to run Classic discovery on these versions |

`RECORD_AUDIO` is added in Stage 4.

## 6. Main screen

The main screen shows:
- the project name;
- Bluetooth connection status;
- voice authentication status;
- door status from the last MCU reply, with its time;
- a device status card with the three-level checklist.

It has these buttons:
- **Start Voice Verification.** Enabled only when connected. Opens the voice screen.
- **Lock Door.** Sends `LOCK` and waits for `LOCKED`.
- **Refresh status.** Sends `STATUS`.
- **Bluetooth devices**, **Disconnect**, and *Reconnect to last device*.

### Debug builds only: protocol test console
- Four buttons send raw `VOICE_OK`, `VOICE_FAIL`, `LOCK` and `STATUS`, and a TX/RX traffic log is shown.
- This exists so you can run the firmware tests (FW-02 … FW-15) before Stage 4.
- It cannot unlock the door, because the ATmega32 still requires the physical keypad PIN. Anyone with a generic serial-terminal app could send the same text, which is why changing the HC-05 pairing PIN matters.
- The console is hidden in release builds (`BuildConfig.DEBUG`).

## 7. Tests for Stage 3

Fill in **Actual** and **P/F** only after running each test on a real phone and the real lock.

| ID | Objective | Setup | Steps | Expected | Actual | P/F |
|---|---|---|---|---|---|---|
| AND-01 | Bluetooth permission | Fresh install, Android 12+ | Open *Bluetooth devices* → *Grant permission* | System dialog for Nearby devices; after allowing, the paired list appears | | |
| AND-02 | Permission denied | Fresh install | Deny twice | Message shown and *Open app settings* button appears; no crash | | |
| AND-03 | Bluetooth off | Turn Bluetooth off | Open *Bluetooth devices* | "Bluetooth is off" with a *Turn on* button; system prompt works | | |
| AND-04 | Pairing | HC-05 powered, not paired | *Search* → HC-05 → *Pair*, enter 1234 or 0000 | Moves to the paired list; "Paired with HC-05" | | |
| AND-05 | Connection | Paired, firmware running | *Connect* | Connecting… then Connected; all 3 checklist items ticked; door shows Locked | | |
| AND-06 | Wrong baud / no MCU | Disconnect HC-05 TXD from PD0 | *Connect* | "Link open, no response" warning, not "Connected" | | |
| AND-07 | Connection failed | HC-05 unpowered | *Connect* | "Connection error" or "Timeout" with a clear message within ~12–25 s | | |
| AND-08 | Disconnection | Connected | *Disconnect* | Not connected; door Unknown; no auto-reconnect | | |
| AND-09 | Unexpected link loss + reconnect | Connected | Power-cycle the HC-05 | Connection error, then Reconnecting 1/3…; reconnects once the HC-05 is back | | |
| AND-10 | Lock command | Connected | *Lock Door* | Snackbar "Door locked."; traffic shows `→ LOCK`, `← LOCKED` | | |
| AND-11 | Status | Connected | *Refresh status* | `→ STATUS`, `← LOCKED` (or the current state) | | |
| AND-12 | VOICE_OK path (debug) | Connected, debug build | Console `VOICE_OK`, then type the PIN + `#` on the keypad | App shows Waiting for PIN, then Unlocked, then Locked after 5 s | | |
| AND-13 | VOICE_FAIL (debug) | Connected | Console `VOICE_FAIL` | Door: Access denied; keypad does nothing | | |
| AND-14 | Rotation / background | Connected | Rotate the phone; switch apps and come back | Connection stays up; state preserved | | |
| AND-15 | Bluetooth turned off while connected | Connected | Turn off Bluetooth from quick settings | "Bluetooth is turned off" error; no reconnect loop | | |

## 8. Known limitations
- The app keeps the connection only while its process is alive. There is no foreground service, so Android may close it after a long time in the background.
- No hidden-API fallback (`createRfcommSocket(int)` by reflection) is used. If a particular HC-05 refuses both the secure and insecure SPP sockets, record the error message from the traffic log.
- The HC-05 link is protected only by Bluetooth pairing. Change the default pairing PIN (see `Firmware/README.md`, section 12).
