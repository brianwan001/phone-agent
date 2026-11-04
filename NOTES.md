# NOTES — 调试与修复记录(2026-10-29 深夜)

## 结论:两大环境杀手(MIUI/HyperOS)

1. **进程冻结**:Agent 打开别的 App(如计算器)后,HyperOS 把本应用整个冻结
   (cgroup freezer)——sleep 都醒不来,截图回调不到,几分钟才解冻或永不。
   铁证:am start 拉回前台的瞬间,被"卡住"的线程与看门狗同时恢复输出。
   `dumpsys deviceidle whitelist`(已加)+ `cached_apps_freezer=disabled`(已是)都不管用,
   这是 MIUI powerkeeper 的独立层。
   → 已做对策:FGS 类型改为 `connectedDevice|specialUse` + PARTIAL_WAKE_LOCK + 30 分钟
   + Setup 第 3 步"电池豁免"引导。**手机端还需手动**:应用信息 → 省电策略 → 无限制。
   最后一次验证因无障碍反复被解绑 + 并发人工操作未能干净复现,待真机重测。

2. **无障碍绑定不稳定**:`adb shell settings put` 重启用经常不绑定/绑定后被静默解绑,
   截图报 `INTERNAL_ERROR(code=1)` 或 `NO_ACCESSIBILITY_ACCESS(code=3)`。
   → 必须由用户在 无障碍设置 里开关一次才算数;装新包也会重置绑定,装完要重新开。

## 本轮修复(全部已装机)

| # | Bug | 修复 |
|---|-----|------|
| 1 | pause 后循环尾部 finish 把"暂停"覆盖成 "Reached the step limit"(0 步报超限的元凶) | 只在 status==running 时收尾 |
| 2 | 线程真挂死时 interrupt 无效 → 僵尸 running | 每步 120s 看门狗:直接落盘 paused + 可 Resume |
| 3 | 截图 GPU copy 可能永久阻塞 | copy 移到独立线程,3s 放弃 |
| 4 | takeScreenshot binder 可能卡死 | 牺牲线程 + 超时放弃 |
| 5 | 无障碍树 BFS 无界 | visited 集合 + 1500 节点上限 |
| 6 | 服务重连后 UI 不刷新,Start 永久禁用 | 1s 轮询 |
| 7 | 后台日志被 HyperOS 限流,日志"消失"是假象 | 排障时先 am start 解冻再看 |

## 成功案例(修冻结问题之前)
- "Open the Settings app":3 步 12.1s 完成 ✓(home → 点图标 → done)
- 用户自跑 "Open Settings and go to Wi-Fi":40 步未冻结完成循环(未达成目标,超步数)

## 待办
- [ ] 手机端:应用信息 → 省电策略 → 无限制(关键,adb 做不了)
- [ ] 手机端:重新开一次无障碍开关,跑计算器任务,验证 connectedDevice FGS + 唤醒锁后不再冻结
- [ ] 若仍冻结:fallback 方案 = 计算器/目标 App 也加白名单,或提示用户开启"锁定后台"(最近任务上锁)
- [ ] 40 步上限对长任务不够,可调 maxSteps 或让模型自己判断

## 2026-10-29 20:30 补充:设备侧反自动化(adb 路线已到头)

- `am start -a AUTO_RUN` 直达入口已加(MainActivity 处理 goal extra 自动开跑),
  但 shell 来源的启动/绑定被 HyperOS 识别并对抗:进程启动数秒即被杀、
  settings-put 的无障碍绑定被自动撤销(AurogonImmobulus/SmartPower 日志)。
- **必须真人操作一次**:用户手动开无障碍开关 + 手动点 Start,MIUI 才认。
- 新增保活:无声音频循环(AudioTrack setLoopPoints(-1))+ mediaPlayback FGS 类型,
  "正在播放音乐"的进程 MIUI 不冻结。
- 传参给 am 注意:adb shell 会吃引号,--es goal 值含空格要用单引号且转义。
