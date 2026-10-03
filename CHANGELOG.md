# Changelog

Releases are tagged per platform (`windows-vX.Y.Z`, `macos-vX.Y.Z`, `android-vX.Y.Z`); `<platform>-latest` always points at the newest build. Versions move together across platforms.

## 0.2.0

### Desktop
- Single instance: launching the app again raises the existing window instead of opening a second copy.
- Closing the window now quits the app (no more hidden background process); the tray icon is removed.
- Receiver stops automatically when you stop streaming on the phone.

### Android
- Stopping the stream tells the desktop, which stops its receiver and clears the pairing.

### Fixes
- Restarting the stream on the phone while the desktop kept running produced no audio; the desktop now resets its packet ordering for each new session.

### Protocol
- New `BYE` packet (`0x05`, phone to PC). Update both apps together; an older phone app simply never sends it.

## 0.1.1
- Initial public release.
