# Android Phone Agent

**Let an AI model use a real Android phone. No extra hardware: the phone is its own eyes and hands.**

Android port of [**ESP32 iPhone Agent by Henry Yuan**](https://github.com/onehumanbeing/esp32-iphone-agent) (MIT). Read the [NOTICE](NOTICE) — if you build on this, credit both projects.

简体中文说明见 [README.zh-CN.md](README.zh-CN.md)。

## How it differs from the iOS original

The original needs a $7 ESP32-S3 because iOS is locked down: the board is the AirPlay receiver (eyes) and a USB HID mouse (hands), while an app on the phone is the brain. Android has native APIs for all three, so **no ESP32 is needed**:

| | iOS original | This Android port |
|---|---|---|
| Eyes | AirPlay mirror → ESP32 → BLE → app | `AccessibilityService.takeScreenshot()` (Android 11+) |
| Hands | ESP32 USB HID mouse + AssistiveTouch | `AccessibilityService.dispatchGesture()` + global actions |
| Typing | HID keyboard (ASCII only) | `ACTION_SET_TEXT` (Unicode supported) |
| Brain | iOS app + OpenAI-compatible vision API | Same (Kotlin, Jetpack Compose) |
| Status | Dynamic Island (Live Activity) | Foreground-service notification with a Stop button |
| Review | Run replay + export trace | Same (per-step JPEGs + `run.json`, export as zip) |
| Hardware | ESP32-S3 required | **None** |

## Requirements

- Android 11 (API 30) or later — `takeScreenshot` needs it.
- Any OpenAI-compatible vision endpoint (cloud API, vLLM, SGLang, llama.cpp, Ollama…).
- Android Studio (to build once). No special hardware, no root.

## Quick start

### 1. Build and install

```bash
# in Android Studio: open this folder, press Run
# or on the command line:
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. Set up (once, in the app)

1. **Let me see and touch the screen** — enable *Phone Agent* in Settings → Accessibility. The service only acts while a task runs.
2. **Give me a brain** — enter your endpoint and API key (stored in EncryptedSharedPreferences), tap *Fetch models*, pick a vision model. Optionally set a separate planner model.

### 3. Give it a task

Type a goal (for example *"Open Settings and turn on Wi-Fi"*) and press **Start**. The agent plans, then loops: screenshot → model → `tap/swipe/type/…` → wait for the screen to settle → repeat until `done`/`fail`. Stop it from the notification or the app.

## Model and prompts

- Any OpenAI-compatible chat API that accepts images works. The app lists the endpoint's models; you can also type a model ID and set a separate planner model.
- Prompts are plain text in `app/src/main/assets/prompts/` (ported and adapted from the original): `actor.md` (action syntax the engine parses), `planner.md`, `app-rules.md`. Edit, rebuild, done.
- Coordinates are integers 0–1000 relative to the screenshot, scaled to the real display.

## Project layout

```
app/src/main/java/tech/handsy/phoneagent/
  accessibility/  AgentAccessibilityService — screenshots, gestures, typing, navigation
  agent/          AgentEngine (the loop), AgentAction + parser, prompts loader, status bus
  ai/             OpenAIClient (chat completions with images, model list)
  service/        AgentService — foreground status notification, stop/resume
  store/          SettingsStore (encrypted), RunStore (run traces)
  ui/             Compose screens: setup, task, live status, runs + replay, export
app/src/main/assets/prompts/   planner / actor / app-rules (plain text, bundled)
```

## Limitations

- **`key` is limited.** Android offers no key-injection API to accessibility services: `enter` is sent as a newline in the focused field, `backspace` deletes a character via `ACTION_SET_TEXT`. (The iOS original gets real keys from the ESP32 HID keyboard; a possible Android equivalent is an ESP32/BLE keyboard — see roadmap.)
- **Secure screens are invisible.** Some banking apps, incognito browser tabs and the notification shade can flag themselves `FLAG_SECURE`; screenshots come back black there.
- **Accessibility visibility varies.** Some apps hide content from accessibility services; the model sees only the pixels, which is usually still enough.
- Each step takes a few seconds, mostly the model. Screenshot + settle detection add ~1–2 s.
- The run shares the screen with you: don't touch the phone mid-run.

## Roadmap

- ESP32/BLE HID keyboard support for full `key` combos (parity with the original's hands)
- Several actions per model call already supported (up to 6 `DO` lines) — add parallel pointer-free batching hints
- Accessibility-tree hints alongside pixels for faster, cheaper grounding
- Widgets / Shortcuts equivalents (App Actions, quick tiles) to start saved tasks

## Responsible use

Only automate devices and accounts you own or are allowed to use, and respect the terms of the apps involved. The agent acts with your hands: watch it, and keep the stop button within reach.

## Acknowledgements

[ESP32 iPhone Agent by Henry Yuan](https://github.com/onehumanbeing/esp32-iphone-agent) — the original project, prompts and run-trace design. [NimBLE-Arduino](https://github.com/h2zero/NimBLE-Arduino), [UxPlay](https://github.com/FDH2/UxPlay) live in the original's firmware, not needed here.

## License

[MIT](LICENSE).
