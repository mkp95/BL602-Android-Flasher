# BL602 UART Tool — no-Gradle Android source

This project is built by GitHub Actions using Android SDK command-line build tools directly. It contains no Gradle files or Android Studio project configuration. Java source and XML resources only.

## Build in GitHub

1. Open **Actions** and select **Build BL602 Android APK**.
2. Run the workflow (or push a commit).
3. Download `BL602-UART-Tool-debug` from the workflow run's Artifacts section.

The workflow installs Android SDK platform 35 and build-tools 35.0.0, compiles Java with javac, packages DEX with d8, and signs a debug APK.

## Local command-line build

Install JDK 17, Android SDK platform 35, and build-tools 35.0.0, set `ANDROID_HOME`, then run:

```sh
chmod +x build.sh
./build.sh
```

Output: `dist/BL602-UART-Tool-debug.apk`

## Current feature status

- USB Host serial connection using usb-serial-for-android (CH340/CH341 support)
- Custom baud-rate entry; 8-N-1
- UART text monitor with send/receive
- Copy all, paste to command box, select text, clear terminal
- Multiple firmware BIN selection UI
- DTR and RTS toggles where adapter and board wiring expose them

**BL602 flash protocol is not implemented in this starter.** The flash action is intentionally fail-safe and sends no erase/write commands. A functional flasher still needs the BL602 ROM handshake, matched BL602 eflash loader and flash parameters, loader upload, erase/write protocol, address handling and verification. No upstream loader binaries are bundled here.

## USB wiring

- CH340 TXD -> target UART RX
- CH340 RXD -> target UART TX
- GND -> GND
- Use 3.3 V UART logic. Never feed 5 V logic into BL602 UART pins.
- DTR/RTS only operate reset/boot if the board routes those signals appropriately.

## Dependency

The build downloads `usb-serial-for-android` 3.8.1 from JitPack at build time. The application itself can then run offline. This is the only third-party runtime library in this scaffold.

The project is an initial scaffold, not a completed flashing utility.
