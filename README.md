# Hermes Watch

Push-to-talk control of your self-hosted [Hermes agent](https://hermes-agent.nousresearch.com) from a Wear OS smartwatch (Galaxy Watch Ultra, Pixel Watch, etc.).

Hold the mic → speak → Hermes runs your command → the reply is spoken back on your wrist.

## What it does

- **On-device speech recognition** — background STT, no Google recognition screen popping up
- **Speaks the reply back** on the watch (TTS), plus haptics and live state animations
- **Runs under an isolated Hermes profile** (`watch` by default) so watch commands never touch your main agent's memory — your PC, phone, and Discord sessions stay walled off
- **Per-app-launch session** — conversation context lives while the app is open, resets when you close it
- **Dynamic profile list** — the app fetches your Hermes profiles from the backend, so you pick from your real profiles, never a hardcoded list
- **Swipe navigation** — mic page → profile page → setup page
- **Full-screen scrollable replies** — long answers are readable

## How it works (read this first)

```
Wear watch  ──cleartext HTTP──▶  FastAPI backend  ──hermes -z──▶  Hermes agent
 (speech)       bearer token       + /api/v1/command                (isolated profile)
                                    + /api/v1/profiles
```

- The watch runs `SpeechRecognizer` locally to capture speech, then POSTs the text to your backend.
- The backend verifies a bearer token, then shells out to `hermes -p <profile> --continue <session> -z "<text>"` and returns the reply, which the watch reads aloud.
- Replies get a "keep it to 1-3 short sentences, plain text, TTS-friendly" nudge so you're not listening to paragraphs.

### Networking — Tailscale is required

The watch and the Hermes server must be able to reach each other. This project is built for a **private tailnet**:

- **The server runs the backend** on your Tailscale network.
- **The watch runs NO Tailscale of its own.** On most Wear OS watches there's no independent internet — the watch rides the phone's Bluetooth/Wi-Fi bridge. **The phone is the tailnet node**; the watch rides the phone's bridge into the tailnet, then talks directly to the server's Tailscale IP (no phone app, no push service).
- **So: the server AND the phone (paired to the watch) must both be on the same Tailscale network**, and you point the watch at the server's Tailscale IP (e.g. `http://100.x.y.z:8650`).

> Without the phone on the same tailnet, the watch can't route to the server. There's no FCM/APNs push and no public cloud — this is a bring-your-own-network, self-hosted tool.

## Requirements

- A **Wear OS 5+** watch
- An Android phone paired to the watch (for the BT/Wi-Fi bridge)
- **Tailscale** on the server and the paired phone, both on the same tailnet
- A machine running your own **Hermes** install
- A server (could be the same machine) running the included **FastAPI backend**
- Android SDK + Java 17 to build the watch app

## Setup

### 1. Run the backend

On the same machine as Hermes (or any box that can reach it), put the backend somewhere, then:

```bash
cd backend
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
cp .env.example backend.env
# edit backend.env and set HERMES_WATCH_TOKEN to your own long random value
.venv/bin/uvicorn main:app --host 0.0.0.0 --port 8650
```

Make sure `hermes` is on the PATH of the user running the service.

**Create the `watch` Hermes profile.** The backend defaults to `profile=watch` (an isolated profile so the watch never touches your main agent's memory). Hermes does **not** auto-create profiles — you must make it once:

```bash
hermes profile create watch
```

If you skip this, every request fails with `Error: Profile 'watch' does not exist`. You can use any profile name (set it in the app's setup page or `-PbackendToken` build flag), but `watch` is the sensible default for isolation.

> **Where does the token come from?** You generate it — there's nothing to find. Run `openssl rand -hex 32`, put that in `backend.env` as `HERMES_WATCH_TOKEN`, then use the **same value** in the watch app (see below). The watch sends it as `Authorization: Bearer <token>`; the backend rejects anything that doesn't match.

> **Bind it to your tailnet, not 0.0.0.0.** The example above binds all interfaces for simplicity. For a production setup, bind to your Tailscale IP (`--host 100.x.y.z`) so the backend isn't reachable from the public internet. Your token is your only auth — don't expose the port publicly.

For systemd, see `backend/hermes-watch.service` (adjust the paths; `%h` = your home directory).

### 2. Build the watch app (with your config baked in)

Bake your backend URL + token straight into the APK so **install = configured** — no typing on a watch:

```bash
cd wear
./gradlew :app:assembleDebug \
  -PbackendUrl="http://100.x.y.z:8650" \
  -PbackendToken="YOUR-TOKEN"
# output: app/build/outputs/apk/debug/app-debug.apk
```

Install that APK on your watch (e.g. `adb install` over the watch's wireless debugging, or sideload via Wear OS). It opens straight to the mic — done.

> Your URL + token are compiled into **your** APK only; nothing is committed to git, and each user builds their own. Skip the flags and the app falls back to an in-app setup page where you can type the URL/token instead (also handy for pointing at a different backend later without rebuilding).

### 3. Connect

Open the app. It lands on the **setup page** if no token was baked in — enter your backend URL and token, Save. Otherwise it opens straight to the mic. Swipe to the profile page to pick which Hermes profile you talk to, then hold the mic and speak.

## Security

- The backend uses a bearer token (`HERMES_WATCH_TOKEN`) that you generate. It is **never shipped** in a public APK — each user builds their own with their own token.
- All command and profile traffic is bearer-authenticated.
- Watch commands run under a **dedicated, isolated Hermes profile** (`watch` by default) so the watch cannot read or write your main agent's memory.
- **Transport is cleartext HTTP** (the Wear bridge is plain HTTP). This is fine on a private tailnet, but **do not** expose the backend to the public internet — bind it to your Tailscale IP and keep the token long and random.
- The backend shells out to `hermes -z`. It runs whatever you speak, so the token + tailnet binding are your only guards. Keep both tight.

## Project layout

```
backend/   FastAPI service + Hermes runner + tests
wear/      Wear OS app (Kotlin + Compose)
```

## License

MIT
