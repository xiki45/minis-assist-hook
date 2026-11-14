# Minis Assist Hook

轻量入口重定向 Xposed/LSPosed 模块：**把小米超级小爱（`com.miui.voiceassist`）的系统唤起
改道给 Minis（`com.openminis.app`）**。

> 仓库：<https://github.com/xiki45/minis-assist-hook>
> 配套 Minis 源码（提供 `MainActivityVoiceAssist` 入口）：
> <https://github.com/xiki45/OpenMinis>（fork，默认分支 `feature/system-assist`）

## 解决什么问题

HyperOS（Redmi Note 14 5G / dali 已验证）在框架层封锁第三方助理：

- 手势条 / 电源键的"语音助手"派发被硬编码到超级小爱的私有通道，
  **不跟默认助理角色走**，改 `settings secure assistant` / RoleManager / entity_config 均无效；
- system_server 拒绝绑定任何第三方 VIS 会话服务（ChatGPT 同样失败）。

本模块不跟封锁对抗，而是在**小爱进程内部**把唤起投递拦截改道。

## 工作原理（v2）

实测拓扑（v1 系列日志发现）：双击小白条、长按电源键这两条手势，最终都由系统
`startService` 投递同一个 intent：

```
action   = android.intent.action.ASSIST
component= com.miui.voiceassist/com.xiaomi.voiceassistant.VoiceService
```

（双击小白条 extras 带 `triggerFrom/triggerType`；电源键带 `key_action/long_press_event_time`。）

模块 hook `android.app.ActivityThread.handleServiceArgs`（派发层，不受子类覆写影响）：

1. 命中上述目标 intent → 用 `Service` 实例作为 Context 拉起
   `com.openminis.app/.MainActivityVoiceAssist`（VOICE_ASSIST 全屏入口）；
2. 抑制原始调用，小爱浮窗不显示（v2.3 起抑制点在 `onStartCommand` 层，见下）；
3. 1200ms 防抖；任何一步失败则**不抑制**，小爱兜底照常可用。

**抑制落点（v2.3）**：`handleServiceArgs` 是 void 方法，在派发层 return-early 会连带跳过
方法体末尾的 `serviceDoneExecuting(...)`，AMS 的 `executeNesting` 只增不减（实测每手势 +1，
20s 后 `am_anr: executing service ...VoiceService, waited 20001ms`）。因此 v2.3 起改在
`VoiceService.onStartCommand` 层抑制（`param.setResult(START_STICKY)`，避免 NOT_STICKY 的
`stopIfKilled` 改变小爱被杀后是否重启），派发层只记账；该 hook 没能装上时退回旧路径并手动补账，
保证任何 ROM 上都不会因找不到类而失去"小爱不弹"。

**触发源判定（v2.1，v2.5 起按来源枚举）**：按唤起来源决定拉起 Minis 时是否附带屏幕截图：

| 触发 | `voice_assist_start_from_key` | `attach_screen` | Minis 行为 |
|---|---|---|---|
| 长按电源键 | `long_press_power_key` | `false` | 快速提问，不附带屏幕截图 |
| 双击小白条 | `double_click_fullscreen_gesture_line` | `true` | 唤起时自动附当前屏幕截图 |
| 长按手势条 | `long_press_fullscreen_gesture_line` | `true` | 同上 |

v2.1 靠"extras 里有没有 `app.send.wakeup.command`"反推电源键，v2.5 起直接用白名单校验
产出的来源枚举判定（`attachScreenFor()`），电源键那个 extra 只留在日志里做交叉印证，
枚举意外缺失时才回退旧启发式。

Minis 侧（[xiki45/OpenMinis](https://github.com/xiki45/OpenMinis) `feature/system-assist`
分支的 `AssistCapture`）读取该 extra；**extra 缺失时默认截图**（兼容旧模块 /
标准框架路线）。模块本身不做截图——无障碍服务实例在 Minis 进程内，本模块只传信号。

### 拉起 Minis 时透传的 extras

模块只"投递信号"，所有决策都在 Minis 侧。当前协议版本 **1**（`HOOK_PROTOCOL_VERSION`）：

| extra | 类型 | 起始版本 | 含义 |
|---|---|---|---|
| `com.openminis.hook.attach_screen` | `boolean` | v2.1 | 本次是否附带当前屏幕截图（电源键 `false`，手势 `true`） |
| `com.openminis.hook.trigger_id` | `long` | v2.4 | 本次唤起的唯一标记（`elapsedRealtime`），同一次唤起重复投递时 id 相同，供 Minis 精确去重 |
| `com.openminis.hook.trigger_source` | `String` | v2.5 | 来源枚举，原样透传 `voice_assist_start_from_key`（见上表三值） |
| `com.openminis.hook.version` | `int` | v2.5 | 模块侧协议版本，Minis 写进日志，现场日志可自证模块版本 |

`trigger_source` / `version` 是 v2.5 新增：此前来源枚举只用于"放不放行"，拉起时被丢弃，
Minis 只能从 `attach_screen` 反推手势类型，日志里没有来源、排障要靠人肉对时间戳。
透传后会话元数据、日志以及后续按来源收紧行为（例如"长按手势条不自动截图"）都有依据。
旧版 Minis 忽略这两个 extra，不影响兼容。

**入口来源白名单（v2.2）**：命中上述目标 intent 后，还需校验唤起来源
（extras 的 `voice_assist_start_from_key`）是否为本机实测可安全重定向的入口：

| 入口来源 | `voice_assist_start_from_key` | 是否重定向 |
|---|---|---|
| 双击小白条 | `double_click_fullscreen_gesture_line` | ✅ |
| 长按手势条 | `long_press_fullscreen_gesture_line` | ✅ |
| 长按电源键 | `long_press_power_key` | ✅ |
| 其它 / 未知 | 不匹配以上 | ❌ 交还原生小爱兜底 |

未知来源时模块**不抑制、不拉起**，只记一行日志后让小爱照常处理，避免误伤其它
唤起路径。同时日志只输出已知来源枚举 extra（`voice_assist_function_key` /
`triggerFrom` / `triggerType` / `voice_assist_start_from_key`），不 dump 任意
Bundle 内容。

不杀小爱进程、不碰其它组件；MiPush 等无关 service 启动不受影响。

- 日志 TAG：`MinisHook`（logcat + XposedBridge 双通道）
- 关键常量：`XiaoAiRedirect.REDIRECT_ENABLED`

## 构建

要求：JDK 17、Android SDK（compileSdk 35）。Gradle 由仓库自带 wrapper 提供。

```sh
./gradlew :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

Xposed API 依赖仓库 `https://api.xposed.info/`（compileOnly，运行时由框架提供）。

## 部署（Vector / LSPosed 框架，CLI 方式）

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell su -c "/data/adb/lspd/cli modules enable com.openminis.hook"
adb shell su -c "/data/adb/lspd/cli scope add com.openminis.hook com.miui.voiceassist/0"
adb shell am force-stop com.miui.voiceassist   # 下次唤起即生效
```

自测（root 模拟手势派发，等效于双击小白条）：

```sh
adb shell su -c "am startservice -a android.intent.action.ASSIST \
  -n com.miui.voiceassist/com.xiaomi.voiceassistant.VoiceService"
# 预期：Minis 全屏弹出，小爱浮窗不出现
```

## 关闭 / 回滚

```sh
adb shell su -c "/data/adb/lspd/cli modules disable com.openminis.hook"
adb shell am force-stop com.miui.voiceassist
```

或编译期把 `REDIRECT_ENABLED` 改回 `false`（退回纯日志模式）。

## 版本历史

| 版本 | 内容 |
|------|------|
| v1 | 日志发现：Activity/Service/Receiver 入口观测（Receiver 抽象方法无法 hook） |
| v1.5 | 补 `ActivityThread.handleReceiver/handleBindService` 派发层观测 |
| v1.6 | 补 `ActivityThread.handleServiceArgs`，定位到唯一汇聚点 |
| v2 (versionName 2.0) | 重定向上线：拦截改道 + 抑制 + 防抖 + 失败兜底 |
| v2.1 (versionName 2.1) | 触发源判定：拉起时附 `com.openminis.hook.attach_screen` extra（电源键不截图、小白条手势截图） |
| v2.2 (versionName 2.2) | 入口来源白名单：仅 `voice_assist_start_from_key` 命中实测三种入口才重定向，未知来源交还小爱兜底；触发源诊断日志只输出来源枚举 extra |
| v2.3 (versionName 2.3) | 抑制落点下移到 `VoiceService.onStartCommand`，修掉派发层 return-early 造成的 `executeNesting` 泄漏与 `waited 20001ms` ANR；找不到该类时自动退回旧路径 |
| v2.4 (versionName 2.4) | 透传 `com.openminis.hook.trigger_id`（每次唤起唯一 id），替代两边各自的时间窗去重 |
| v2.5 (versionName 2.5) | 透传 `com.openminis.hook.trigger_source` + `com.openminis.hook.version`（协议版本 1）；`attach_screen` 改由来源枚举判定；日志补 `source=/protocol=` |
