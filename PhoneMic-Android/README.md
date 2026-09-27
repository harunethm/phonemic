# Phone Mic — Android

Turns your Android phone into a wireless microphone for a Windows or macOS desktop —
handy for game/Discord voice chat when a wired mic dies. Streams the phone's mic over
WiFi to the companion [PhoneMic-Desktop](../PhoneMic-Desktop) receiver app.

Most people don't need this repo at all — grab the prebuilt APK from the
**[Phone Mic website](https://harunethm.github.io/phonemic/)** or the
[Releases page](https://github.com/harunethm/phonemic/releases) instead. The rest of
this README is for building it yourself from source.

Kotlin/Compose app, package `com.scylla.tool.phonemic`.

## Building from source

1. Enable USB debugging on the phone (Settings → Developer options).
2. Connect the phone via USB, accept the debugging prompt.
3. From this folder:
   ```
   ./gradlew installDebug
   ```
4. Open **Phone Mic** on the phone.

To just build an APK without installing:

```
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk` — install manually with
`adb install app/build/outputs/apk/debug/app-debug.apk`.

## First-time setup

Before pairing, install a virtual audio cable on the desktop side — see the
[landing page](https://harunethm.github.io/phonemic/) for step-by-step instructions
(VB-Audio Virtual Cable on Windows, BlackHole on macOS).

## Using it

1. On the desktop, launch PhoneMic-Desktop — it shows a QR code, a numeric pairing
   code, and its WiFi IP.
2. On the phone, open **Phone Mic**, tap **Scan QR to pair**, and point the camera at
   the desktop's QR code (or tap **Enter details manually** to type IP/port/pairing
   code by hand).
3. Pick a capture scenario (Speaker nearby / Distant voice / Music), tap
   **Start Streaming**, and grant the microphone + notification permissions when
   prompted.
4. The desktop side switches to "Paired with `<phone IP>`" and starts receiving.

Re-pair later with **Re-pair with a PC** on the phone, or click **New code** on the
desktop first to invalidate the old pairing.

## How it works

- **Audio format**: 48kHz, mono, 16-bit PCM, fixed to match the desktop receiver — no
  format negotiation.
- **Transport**: UDP, no compression, no acks/retransmission on the audio path itself —
  favors lowest latency for real-time voice; an occasional dropped 10ms frame is
  inaudible. A lightweight control channel on the same socket handles pairing and
  connection-quality feedback, since those need to be reliable-ish.
- **Wire format**: 1-byte packet type, then a type-specific payload:
  - `HELLO` (phone → desktop, `0x01`): `[type][pinLen][pin utf8 bytes]` — sent at
    stream start and retried every 2s until acknowledged.
  - `HELLO_ACK` (desktop → phone, `0x02`): `[type]`.
  - `AUDIO` (phone → desktop, `0x03`): `[type][4-byte big-endian seq][960 bytes PCM]`
    (~965 bytes/packet, under the ~1472-byte MTU to avoid IP fragmentation).
  - `QUALITY` (desktop → phone, `0x04`): `[type][lossPercent 0-100]` — sent about once
    a second once paired, drives the Excellent/Good/Poor/Lost badge.
- **Pairing**: desktop generates a random PIN and QR (`ip:port:pin`); the phone scans
  it or the values are typed by hand. No cloud/relay server — everything stays on
  the LAN.
- Uses ML Kit for QR scanning. No internet-fetched services or accounts required.

## Troubleshooting

- **No sound on desktop**: confirm phone and desktop are on the same WiFi network (not
  phone on mobile data); double-check IP/port match on both ends; check the desktop's
  firewall isn't blocking the app's UDP port.
- **Desktop shows "Not paired"**: the phone's saved pairing code doesn't match the
  desktop's current one — usually because **New code** was clicked on the desktop, or
  the desktop app was restarted (fresh PIN every launch). Re-pair from the phone.
- **Choppy audio**: local WiFi congestion — move closer to the router or reduce other
  WiFi traffic.
- **Echo/feedback**: the app uses the `VOICE_COMMUNICATION` audio source (hardware
  echo cancellation) — for best results use headphones on the desktop side instead of
  speakers.
