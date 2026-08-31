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
2. `param.setResult(0)` 抑制原始调用，小爱浮窗不显示；
3. 1200ms 防抖；任何一步失败则**不抑制**，小爱兜底照常可用。

**触发源判定（v2.1）**：按原始 intent 的 extras 区分唤起来源，并在拉起 intent 上附加
`com.openminis.hook.attach_screen`（Boolean）供 Minis 侧决定是否截屏：

| 触发 | 判定依据 | attach_screen | Minis 行为 |
|---|---|---|---|
| 长按电源键 | extras 含 `app.send.wakeup.command` | `false` | 快速提问，不附带屏幕截图 |
| 双击小白条等手势 | 其余命中 | `true` | 唤起时自动附当前屏幕截图 |

Minis 侧（[xiki45/OpenMinis](https://github.com/xiki45/OpenMinis) `feature/system-assist`
分支的 `AssistCapture`）读取该 extra；**extra 缺失时默认截图**（兼容旧模块 /
标准框架路线）。模块本身不做截图——无障碍服务实例在 Minis 进程内，本模块只传信号。

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
