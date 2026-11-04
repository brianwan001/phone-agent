# AGENTS.md — notes for coding agents working in this repo

- Language: Kotlin, Jetpack Compose (Material 3), no DI/Retrofit — OkHttp + org.json only.
- minSdk 30, target/compile 34. `AccessibilityService.takeScreenshot` is the floor; don't suggest MediaProjection.
- The accessibility service (`accessibility/AgentAccessibilityService.kt`) is the ONLY place that touches the screen. `AgentEngine` orchestrates; `AgentService` owns the foreground notification.
- Prompts live in `app/src/main/assets/prompts/` as plain text. The action list in `actor.md` must stay in sync with `ActionParser` in `agent/AgentAction.kt` and `AgentEngine.perform`.
- Model replies are parsed from `THINK:`/`DO:` lines — never switch to JSON output without updating `ActionParser.parseBlock` and both prompts.
- Coordinates from the model are 0–1000 relative to the screenshot; convert only inside `AgentEngine.perform`.
- Keep every run on-device: traces in `files/runs/<id>/` (`run.json` + step JPEGs). The export zip must stay self-contained for another agent to review.
- API key must stay in `SettingsStore` (EncryptedSharedPreferences). Never log it, never put it in traces.
- Build: `./gradlew :app:assembleDebug`. Lint: `./gradlew :app:lintDebug`.
- This is a port of https://github.com/onehumanbeing/esp32-iphone-agent (MIT). Keep LICENSE and NOTICE intact and credit the original in docs.
