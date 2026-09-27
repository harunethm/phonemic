# Phone Mic — Desktop Receiver

Turns the audio stream from the **Phone Mic** Android app into a real microphone on
your Windows or macOS computer, so it works in Discord, games, or any app that expects
a microphone.

Most people don't need this repo at all — grab a prebuilt build from the
**[Phone Mic website](https://harunethm.github.io/phonemic/)** or the
[Releases page](https://github.com/harunethm/phonemic/releases) instead. The rest of
this README is for building it yourself from source.

## Before you start

You'll need a virtual audio cable installed first, so the received audio has
somewhere to appear as a microphone:

- **Windows**: [VB-Audio Virtual Cable](https://vb-audio.com/Cable/) (free)
- **macOS**: [BlackHole](https://existential.audio/blackhole/) (free, `brew install blackhole-2ch`)

See the [landing page](https://harunethm.github.io/phonemic/) for step-by-step install
instructions for either one.

## Building from source

Requires JDK 21 (or 17+).

Run without packaging, straight from this folder:

```
./gradlew run
```

(Windows: `.\gradlew.bat run` in PowerShell, or `gradlew.bat run` in cmd.exe.)

### Package a standalone build

- **Windows** (`build-exe.bat`): bundles a Java runtime via `jpackage` so end users
  don't need a JDK installed.
  ```
  set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
  build-exe.bat
  ```
  Output: `dist\PhoneMic-Desktop\PhoneMic-Desktop.exe`

- **macOS** (`build-dmg.sh`): same idea, packages a `.dmg`.
  ```
  JAVA_HOME=/path/to/jdk-21 ./build-dmg.sh
  ```

## Using it

1. Launch the app. In **Output device**, pick your virtual cable's input side
   (`CABLE Input (VB-Audio Virtual Cable)` on Windows, `BlackHole 2ch` on macOS).
2. Set **Listen port** to match the phone app (default `5005`).
3. Click **Start** — the status line goes "Listening..." then "Receiving..." once the
   phone starts streaming.
4. In your OS sound settings (or directly in Discord/your game), set the virtual
   cable's output side (`CABLE Output` / `BlackHole 2ch`) as your microphone.

## Good to know

- Phone and desktop must be on the same WiFi network. Find this machine's local IP via
  `ipconfig` (Windows) or `ifconfig` / System Settings → Wi-Fi (macOS) and enter it in
  the phone app.
- Audio is fixed at 48kHz / mono / 16-bit PCM to match the Android app — both sides
  need to agree, so don't change one without the other.
- Transport is UDP with no retransmission, trading a little robustness for lower
  latency. On a congested WiFi network you may hear brief dropouts rather than delay.
