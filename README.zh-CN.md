# Android Phone Agent

**让 AI 模型使用一台真实的安卓手机。无需任何额外硬件:手机自己就是眼睛和手。**

本项目是 [**ESP32 iPhone Agent**(作者 Henry Yuan)](https://github.com/onehumanbeing/esp32-iphone-agent)(MIT)的安卓移植版,移植了其 agent 主循环、prompt 结构与运行回放(Trace)设计。二次使用请保留 LICENSE 并同时署名两个项目(见 [NOTICE](NOTICE))。

## 与 iOS 原版的区别

原版之所以需要一块 $7 的 ESP32-S3,是因为 iOS 封闭:开发板要兼任 AirPlay 接收器(眼睛)和 USB HID 鼠标(手),大脑是手机上的 App。Android 三者都有原生 API,所以**完全不需要 ESP32**:

| | iOS 原版 | 本安卓版 |
|---|---|---|
| 眼睛 | AirPlay 镜像 → ESP32 → BLE → App | `AccessibilityService.takeScreenshot()`(Android 11+) |
| 手 | ESP32 USB HID 鼠标 + AssistiveTouch | `AccessibilityService.dispatchGesture()` + 系统导航 |
| 打字 | HID 键盘(仅 ASCII) | `ACTION_SET_TEXT`(支持 Unicode) |
| 大脑 | iOS App + OpenAI 兼容视觉模型 | 相同(Kotlin + Jetpack Compose) |
| 状态展示 | 灵动岛(Live Activity) | 前台服务通知,带停止按钮 |
| 回放审查 | 逐步回放 + 导出 Trace | 相同(每步 JPEG + `run.json`,可导出 zip) |
| 硬件 | 需要 ESP32-S3 | **不需要** |

## 要求

- Android 11(API 30)及以上——`takeScreenshot` 的最低要求。
- 任意 OpenAI 兼容的视觉模型接口(云 API、vLLM、SGLang、llama.cpp、Ollama 均可)。
- Android Studio(只需构建一次)。无需特殊硬件,无需 root。

## 快速开始

### 1. 构建安装

```bash
# 用 Android Studio 打开本目录,点 Run
# 或命令行:
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. 初始化(在 App 内,一次)

1. **让我看到并操作屏幕** —— 在 系统设置 → 无障碍 中启用 *Phone Agent*。该服务只在任务运行时动作。
2. **给我一个大脑** —— 填入 endpoint 与 API key(EncryptedSharedPreferences 加密保存),点 *Fetch models* 选择视觉模型;可单独设置 planner 模型。

### 3. 下达任务

输入目标(例如 *"打开设置,打开 Wi-Fi"*),按 **Start**。Agent 会先规划,然后循环:截图 → 模型 → `tap/swipe/type/…` → 等屏幕稳定 → 重复,直到 `done`/`fail`。可从通知栏或 App 内停止。

## 模型与 Prompt

- 任何接受图片的 OpenAI 兼容 chat 接口都行。App 会拉取模型列表;也可以手填模型 ID,并单独指定 planner 模型。
- Prompt 是纯文本,位于 `app/src/main/assets/prompts/`(自原版移植并安卓化):`actor.md`(动作语法,引擎据此解析)、`planner.md`、`app-rules.md`。改完重新构建即可。
- 坐标是相对截图的 0–1000 整数,由引擎换算到真实屏幕。

## 代码结构

```
app/src/main/java/tech/handsy/phoneagent/
  accessibility/  AgentAccessibilityService —— 截图、手势、打字、系统导航
  agent/          AgentEngine(主循环)、AgentAction + 解析器、prompt 加载、状态总线
  ai/             OpenAIClient(带图 chat completions、模型列表)
  service/        AgentService —— 前台状态通知、停止/恢复
  store/          SettingsStore(加密)、RunStore(运行轨迹)
  ui/             Compose 界面:初始化、任务、实时状态、回放与导出
app/src/main/assets/prompts/   planner / actor / app-rules(纯文本,随包)
```

## 限制

- **`key` 能力有限。** Android 没有给无障碍服务提供按键注入 API:`enter` 以"在焦点输入框追加换行"实现,`backspace` 通过 `ACTION_SET_TEXT` 删一个字符。(iOS 原版靠 ESP32 HID 键盘获得真实按键;安卓侧对应方案是 ESP32/BLE 蓝牙键盘——见路线图。)
- **安全界面不可见。** 部分银行 App、浏览器无痕标签页、通知遮罩会设置 `FLAG_SECURE`,截屏为黑屏。
- **无障碍可见性因 App 而异。** 有些 App 对无障碍隐藏内容;模型只能看到像素,通常也够用。
- 每步耗时数秒,主要是模型;截图与稳定检测增加约 1–2 秒。
- 运行期间共享同一块屏幕:不要中途碰手机。

## 路线图

- 支持 ESP32/BLE HID 键盘,补全 `key` 组合键(对齐原版的"手")
- 每次模型调用多动作(最多 6 条 DO)已支持,继续优化批处理提示
- 在像素之外引入无障碍控件树提示,加速定位、降低成本
- 小部件 / 快捷磁贴 / App Actions 启动已保存的任务

## 负责任地使用

只自动化你拥有或被授权使用的设备与账号,并遵守相关 App 的条款。Agent 用的是你的手:运行时看着它,让停止按钮保持在触手可及的地方。

## 致谢

[ESP32 iPhone Agent by Henry Yuan](https://github.com/onehumanbeing/esp32-iphone-agent) —— 原项目、prompt 与运行轨迹设计均来自它。

## 许可

[MIT](LICENSE)。
