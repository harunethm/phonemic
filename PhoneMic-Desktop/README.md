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

To use the phone's camera as a webcam:

- **Windows 11**: nothing to install up front. Click **Install webcam** in the app's Camera
  section once (one admin prompt). That adds **Phone Mic Camera**, which every app sees:
  the Windows Camera app, browsers, and apps like Discord and Zoom.
- **Windows 10**: install [OBS Studio](https://obsproject.com/) (free). Phone Mic writes into
  OBS's built-in "OBS Virtual Camera"; OBS itself doesn't need to be running.
- **macOS** (13+, OBS 30+): install OBS, open it once, click **Start Virtual Camera**
  (approve the system extension if asked), then **Stop Virtual Camera** and quit OBS.

Without either, the camera still works as a live preview inside the app (with a pop-out
window you can screen-share or capture).

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
  Output: `dist\PhoneMic-Desktop\PhoneMic-Desktop.exe`. A local build then also installs it
  for you (no admin): copied to `%LOCALAPPDATA%\Programs\Phone Mic`, with **Phone Mic**
  shortcuts on the Desktop and in the Start menu. Skip that with `set NO_INSTALL=1`; remove
  it with `powershell -File install-local.ps1 -Uninstall`. CI only builds.

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
- Camera video arrives as H.264 over **TCP** on the same port number, so the firewall
  needs to allow the app for TCP as well as UDP. Decoding uses FFmpeg (LGPL build, bundled
  via JavaCPP).

## Camera

With **Send camera** on in the phone app, the QR panel turns into a live preview once the
video connects (click it, or **Pop out**, for a resizable window). The **Camera** line says
where the video goes, e.g. `→ Phone Mic Camera + OBS Virtual Camera`, or `preview only`.
**Mirror** flips it horizontally, **Rotate** adds a quarter turn (both are remembered). If
it says the virtual camera is busy, OBS's own **Start Virtual Camera** is on; stop it.

### Phone Mic Camera (Windows 11)

A real webcam built on Windows 11's virtual camera API (`native/windows/phonemic-vcam.cpp`),
so no OBS is needed. Apps list it as **Phone Mic Camera (Windows Virtual Camera)**. It exists
while the receiver runs and shows a plain placeholder until the phone's video arrives. Always
1280×720 at 30 fps; other phone resolutions are scaled to fit.

- **Install / update**: the **Install webcam** (or **Update webcam**) button, or
  `PhoneMic-Desktop.exe --install-webcam`. One admin prompt: the DLL is copied to
  `C:\Program Files\Phone Mic\` and registered for the whole machine, because Windows' camera
  service (not the app) loads it.
- **Remove**: `PhoneMic-Desktop.exe --uninstall-webcam` (one admin prompt).
- Frames reach the camera service over a loopback-only TCP port, 47654.

### Developer checks (no phone needed)

```
./gradlew run --args="--video-selftest"   # encode, stream over loopback TCP, decode: prints PASS/FAIL
./gradlew run --args="--vcam-test"        # every virtual camera for 60 s; reads Phone Mic Camera back: PASS/FAIL
```

`--vcam-test` needs the webcam installed. Building the DLL needs Visual Studio Build Tools
with C++ (`native\windows\build-vcam.bat`); `build-exe.bat` does it automatically. From
source, set `PHONEMIC_VCAM_DLL=path\to\phonemic-vcam.dll`. On macOS, `./gradlew run` needs
the helper built first (`build-dmg.sh` does it) and `PHONEMIC_VCAM_HELPER=/path/to/phonemic-vcam` set.
