# Minis Assist Hook

轻量入口重定向 Xposed/LSPosed 模块：**把小米超级小爱（`com.miui.voiceassist`）的系统唤起
改道给 Minis（`com.openminis.app`）**。

> 仓库：<https://github.com/xiki45/minis-assist-hook>
> 配套 Minis 源码（提供 `MainActivityVoiceAssist` 入口）：
> <https://github.com/xiki45/OpenMinis>（fork，默认分支 `feature/system-assist`）

## 两条线

同一个 `applicationId`（`com.openminis.hook`）下并行维护两条产品线，靠 **versionName 后缀**和
**Release 标签前缀**区分，互不覆盖对方的发布记录：

| 分支 | Release 标签 / 资产 | versionName | 做的事 |
|---|---|---|---|
| `main` | `v2.x` · `Minis.Assist.Hook_2.x.apk` | `2.5` | 只改道：手势唤起直接弹 Minis，小爱浮窗不出现 |
| `feature/relay-share`（默认分支） | `relay-v2.x` · `Minis.Assist.Hook_2.x-relay.apk` | `2.6.1-relay` | 转发：小爱照常起来，用户发的内容（文字 / 图片 / 文件）被 hook 交给 Minis，随后收掉小爱浮窗 |

两条线共用 `applicationId`，所以 **versionCode 划区间**，跨线不会撞号（2026-11-23 起）：

| 线 | versionCode | 例 |
|---|---|---|
| `main` | `major*10000 + minor*100 + patch` | `2.5.0` → `20500` |
| `feature/relay-share` | `1000000 + 同一公式` | `2.6.1-relay` → `1020601` |

于是 relay 永远压得住 main，`adb install -r` 一定能覆盖安装；反过来把 main 装到 relay 上
属于降级安装，会被系统拒绝，要 `adb install -r -d` 或先卸载。装的是哪条线，看系统
「应用信息」里的版本号后缀即可（设置页「关于」卡片也会同时显示 versionName 和 versionCode）。

## 转发线（`feature/relay-share`）

不改唤起目标，只在**小爱进程内部**接管"用户到底发了什么"，再投递给 Minis 的
`ShareReceiverActivity`：

- **文字**：`UiManager.onAsrResult` / `onPartial` → `ACTION_SEND text/plain`；
  打字发送走 `x20.i0#startQuery(QueryParams, String, boolean)`；
- **图片 / 文件**：`sh0.n1#startFileUpload` 等四个多模态入口 → 把 Uri 复制进小爱自己的
  cache 目录，用它的 `FileProvider` 授权给 Minis，`ACTION_SEND(_MULTIPLE)` + `EXTRA_STREAM`；
- 附件先发、文字 450ms 后到，靠 Minis 侧 `SharedShareStore` 的 300s 合并窗口拼成一条；
- 投递完成后 `stopService(com.miui.voiceassist.VoiceService)` 收掉小爱浮窗，避免和 Minis 重叠。

每次转发由 `AssistRelay` 判定，开关存在本 App 的 SharedPreferences 里，由
`content://com.openminis.hook.config`（`RelayConfigProvider`）暴露给跑在小爱进程里的 hook，
所以**改开关即时生效，不用重启小爱**。设置页（`RelaySettingsActivity`）覆盖：

| 分组 | 开关 | 默认 |
|---|---|---|
| 总开关 | 启用转发 | 开 |
| 转发渠道 | 双击小白条 / 长按电源键 | 开 |
| | 长按小白条 / 数字助手（ubikitouch）/ 小爱识屏 | 关 |
| 「+」菜单入口 | 相机 / 相册 / 文档 / 识屏 | 开 |
| | 屏幕翻译 / 小爱陪伴 | 关 |
| 投递行为 | 转发图片文件 / 转发后收掉小爱 / 转发小爱的答复 | 开 |
| | 转发卡片·H5 指令 | 关 |
| 外观 | 跟随系统取色（Monet，需 Android 12+） | 开 |

设置页整页是代码画的（模块 APK 一直是无资源构建），结构为「状态总览卡 + 分组卡片 + 开关行」：
顶部总览卡显示转发中 / 已暂停、渠道与入口的开启计数，总开关就在卡里；关掉总开关时下面所有分组
整体置灰并禁用，避免误点。配色优先读系统的动态色板（Monet / Material You，`android.R.color.system_accent1_*`
与 `system_neutral1_*`，API 31+，跟随壁纸取色），系统不支持或手动关掉时退回内置的深浅两套；
开关是自绘的 Material 3 药丸（系统 `Switch` 在 HyperOS 上不吃 `trackTintList`，强调色出不来）。
页脚可一键复制 ADB 命令、点击配置快照可整段复制。

「屏幕翻译」「小爱陪伴」是两个自成一体的小爱模式（全屏翻译 / 视频陪伴），默认不接管：
转发会把用户的话送走、又把小爱收掉，等于打断正在用的功能。关掉时靠两道闸门拦住——
输入模块的入口回调（`gf0.d0.onMoreItem*Click`）记下本次会话从哪进来，投递前再查一次
`checkTranslationIsOpen()` / `isStandByMode()` 兜底。

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
| relay-v2.6.1 (versionName 2.6.1-relay · versionCode 1020601) | 设置页重做：顶部状态总览卡（转发中 / 已暂停 + 渠道·入口计数 + 总开关）、分组卡片与状态栏安全区、自绘 M3 药丸开关、Monet 动态取色（API 31+，可关）、新增「外观」「关于」分组与配置快照点击复制、总开关关闭时其余分组整体置灰；`versionCode` 8 |
| relay-v2.6 (versionName 2.6-relay) | 转发线首个发布：文字 / 图片 / 文件转发给 Minis，投递后收掉小爱浮窗；每条渠道与「+」菜单六个入口各有开关，设置页改版（卡片分组、状态色开关、安全区、版本号）；「屏幕翻译」「小爱陪伴」默认关闭 |
