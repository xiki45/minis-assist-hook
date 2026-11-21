package com.openminis.hook;

import android.app.Activity;
import android.app.Application;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 轻量入口重定向模块 —— v2：重定向模式。
 *
 * <p>针对小米超级小爱（com.miui.voiceassist）的入口组件做拦截与重定向。小米手势唤起
 * （双击小白条、长按电源键）由系统通过 startService 投递到
 * com.miui.voiceassist/com.xiaomi.voiceassistant.VoiceService，action=
 * android.intent.action.ASSIST。该投递在 ActivityThread.handleServiceArgs(ServiceArgsData)
 * 派发层可见（v1 已验证）。v2 在此层把命中目标的唤起重定向到 Minis（com.openminis.app），
 * 并抑制原始调用让小爱不弹出。</p>
 *
 * <p>保留 v1 的全部日志 hook（Activity/Service/handleReceiver/handleBindService/
 * handleIntent 发现）与日志函数；handleServiceArgs 中即使不重定向也保持原有日志输出。</p>
 *
 * <p>v2.5：拉起 Minis 时除 {@code attach_screen}/{@code trigger_id} 外，再透传本次唤起的
 * 来源枚举（{@link #EXTRA_TRIGGER_SOURCE}）与模块协议版本（{@link #EXTRA_HOOK_VERSION}），
 * 并把截图判定改为按来源枚举（{@link #attachScreenFor}）而非电源键专有 extra。</p>
 *
 * <p>如何关闭：编译期把 {@link #REDIRECT_ENABLED} 改为 false 重新构建；或通过
 * vector-cli modules disable com.openminis.hook 后 am force-stop com.miui.voiceassist。</p>
 */
public class XiaoAiRedirect implements IXposedHookLoadPackage {

    private static final String TAG = "MinisHook";

    /** v2 启用重定向；关闭时仅日志（编译期修改后重新构建）。 */
    public static final boolean REDIRECT_ENABLED = false;

    private static final String TARGET_PACKAGE = "com.miui.voiceassist";

    /** Minis 目标应用包名。 */
    private static final String MINIS_PACKAGE = "com.openminis.app";
    /** Minis 语音唤起目标 Activity。 */
    private static final String MINIS_ACTIVITY = "com.openminis.app.MainActivityVoiceAssist";

    /** 命中的唤起 action 之一（系统 ASSIST）。 */
    private static final String ACTION_ASSIST = "android.intent.action.ASSIST";
    /** 命中的唤起 action 之二（小爱自定义）。 */
    private static final String ACTION_VOICE_START = "com.miui.voiceassist.ACTION_VOICE_START_VOICEASSIST";
    /** 重定向给 Minis 的 action。 */
    private static final String ACTION_VOICE_ASSIST = "android.intent.action.VOICE_ASSIST";

    /**
     * v2.1：拉起 Minis 时附带的 extra —— 本次唤起是否应附带当前屏幕截图
     * （由 Minis 侧 AssistCapture 读取）。长按电源键视为"快速提问"不截图，
     * 双击小白条等手势视为"屏幕即现场"截图。
     */
    public static final String EXTRA_ATTACH_SCREEN = "com.openminis.hook.attach_screen";

    /**
     * [v2.4 · T-assist-trigger-id] 每次手势的唯一标记（elapsedRealtime）。
     *
     * <p>历史上 hook 与 Minis 各用一套"时间窗"去重（1200ms / 1500ms），两者不一致，
     * 落在中间的手势会被两边同时吞掉、用户端零反馈。改成"同一次唤起 = 同一个 id"：
     * Minis 只丢弃"同一个 id 的重复投递"，不同 id 的手势永远处理。</p>
     */
    public static final String EXTRA_TRIGGER_ID = "com.openminis.hook.trigger_id";

    /**
     * [v2.5 · T-hook-trigger-source] 本次唤起的**来源枚举**，原样透传
     * `voice_assist_start_from_key` 的取值。
     *
     * <p>v2.2 起这个 extra 只参与入口白名单校验 —— 用来判断"放不放行"，
     * 拉起 Minis 时却把它丢掉了。Minis 侧因此只能从 {@code attach_screen} 反推
     * "是不是电源键"，日志里也没有来源，排障要靠人肉对照时间戳。透传之后：
     * 会话元数据、日志、以及以后按来源收紧行为（例如手势条长按不自动截图）都有依据。</p>
     */
    public static final String EXTRA_TRIGGER_SOURCE = "com.openminis.hook.trigger_source";

    /**
     * [v2.5 · T-hook-version] 模块侧协议版本（整数，不是 versionName）。
     *
     * <p>Minis 侧把它写进日志：出问题时一眼看出这条唤起是哪个模块版本投递的，
     * 不必再问"你装的是几版"。以后新增 extra / 改语义就递增。</p>
     */
    public static final String EXTRA_HOOK_VERSION = "com.openminis.hook.version";

    /** 当前协议版本：1 = 透传 trigger_source + hook_version（v2.5 起）。 */
    public static final int HOOK_PROTOCOL_VERSION = 1;

    /**
     * 电源键派发携带的标记 extra（实测拓扑：长按电源键的 extras 含该键）。
     *
     * <p>v2.5 起截图判定改用来源枚举（[#SOURCE_LONG_PRESS_POWER]），这个键只留在
     * 日志里当交叉印证用；来源枚举读不到时才回退到它。</p>
     */
    private static final String EXTRA_POWER_WAKEUP = "app.send.wakeup.command";

    /** 仅用于区分小爱入口来源的已知枚举 extra。 */
    private static final String EXTRA_VOICE_ASSIST_FUNCTION_KEY = "voice_assist_function_key";
    private static final String EXTRA_TRIGGER_FROM = "triggerFrom";
    private static final String EXTRA_TRIGGER_TYPE = "triggerType";
    private static final String EXTRA_VOICE_ASSIST_START_FROM_KEY = "voice_assist_start_from_key";
    private static final String[] DIAGNOSTIC_TRIGGER_EXTRAS = {
            EXTRA_VOICE_ASSIST_FUNCTION_KEY,
            EXTRA_TRIGGER_FROM,
            EXTRA_TRIGGER_TYPE,
            EXTRA_VOICE_ASSIST_START_FROM_KEY
    };

    /** 实测可安全重定向到 Minis 的小爱入口来源。 */
    private static final String SOURCE_DOUBLE_CLICK_GESTURE = "double_click_fullscreen_gesture_line";
    private static final String SOURCE_LONG_PRESS_GESTURE = "long_press_fullscreen_gesture_line";
    private static final String SOURCE_LONG_PRESS_POWER = "long_press_power_key";

    /** 防抖窗口（毫秒）：该时间内重复触发只抑制不重复拉起。 */
    private static final long DEBOUNCE_MS = 1200L;

    /** 上次重定向时间（elapsedRealtime），用于防抖。 */
    private static long sLastRedirectElapsed = 0L;

    /**
     * [v2.3 · T-ams-accounting] `VoiceService.onStartCommand` 层的 hook 是否装上。
     *
     * <p>装上了就**不再**在 `ActivityThread.handleServiceArgs` 里 return-early：
     * 那一层是 void 方法，return-early 会连带跳过方法体末尾的
     * `serviceDoneExecuting(...)`，导致 AMS 的 executeNesting 只增不减（实测每手势 +1，
     * 20s 后 `am_anr: executing service ...VoiceService, waited 20001ms`）。</p>
     *
     * <p>没装上时退回旧行为（本层 return-early + 手动补账），保证任何 ROM 上都不会
     * 因为找不到类而失去"小爱不弹"的能力。</p>
     */
    private static volatile boolean sOnStartCommandHookActive = false;

    /** handleInvocation() 的三态返回值。 */
    private static final int INV_NOT_TARGET = 0;
    /** 命中目标但本次被防抖 / 需要抑制，不拉起。 */
    private static final int INV_SUPPRESS_ONLY = 1;
    /** 已拉起 Minis，需要抑制原生调用。 */
    private static final int INV_LAUNCHED = 2;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        Log.i(TAG, "handleLoadPackage hooking " + lpparam.packageName
                + " (process=" + lpparam.processName + ", REDIRECT_ENABLED=" + REDIRECT_ENABLED + ")");
        XposedBridge.log(TAG + " hooking " + lpparam.packageName + " (REDIRECT_ENABLED=" + REDIRECT_ENABLED + ")");

        hookActivity();
        hookService();
        hookActivityThreadReceiver(lpparam.classLoader);
        hookActivityThreadBindService(lpparam.classLoader);
        hookActivityThreadServiceArgs(lpparam.classLoader);
        hookVoiceServiceOnStartCommand(lpparam.classLoader);
        hookVoiceServiceHandleIntent(lpparam.classLoader);

        // [v3] 内容旁路：不再改道，改为把"手势唤起 + 会话内容"投给 Minis 上游的分享入口。
        AssistRelay.install(lpparam.classLoader);
        hookApplicationOnCreate();
    }

    /** [v3] 给 AssistRelay 一个 Application Context（用于 startActivity 投递）。 */
    private void hookApplicationOnCreate() {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    AssistRelay.onApplicationCreate((Application) param.thisObject);
                }
            });
        } catch (Throwable t) {
            logHookFailure("Application.onCreate", t);
        }
    }

    private void hookActivity() {
        // Activity#onCreate(Bundle)
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            logEntry("Activity.onCreate", param);
                        }
                    });
        } catch (Throwable t) {
            logHookFailure("Activity.onCreate", t);
        }

        // Activity#onNewIntent(Intent)
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onNewIntent", Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            logEntry("Activity.onNewIntent", param);
                        }
                    });
        } catch (Throwable t) {
            logHookFailure("Activity.onNewIntent", t);
        }
    }

    private void hookService() {
        // Service#onStartCommand(Intent, int, int)
        try {
            XposedHelpers.findAndHookMethod(Service.class, "onStartCommand", Intent.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            logEntry("Service.onStartCommand", param);
                        }
                    });
        } catch (Throwable t) {
            logHookFailure("Service.onStartCommand", t);
        }
    }

    /**
     * 在 ActivityThread 层观测 manifest BroadcastReceiver 的投递。
     *
     * <p>注意：v1 直接 hook BroadcastReceiver#onReceive 会因该方法是抽象方法（无方法体）
     * 而报 "is abstract: it has no body to hook"。改为 hook
     * ActivityThread.handleReceiver(ActivityThread$ReceiverData)，该方法会为每个
     * manifest 注册的 receiver 调用，再在其中读取 ReceiverData 的 intent 字段。</p>
     */
    private void hookActivityThreadReceiver(ClassLoader classLoader) {
        String hookPoint = "ActivityThread.handleReceiver";
        try {
            Class<?> clazz = XposedHelpers.findClass("android.app.ActivityThread", classLoader);
            Class<?> receiverData = findInnerDataClass("android.app.ActivityThread$ReceiverData", classLoader);
            if (clazz == null || receiverData == null) {
                logHookFailure(hookPoint, new IllegalStateException(
                        "ActivityThread or ReceiverData class not found (receiverData=" + receiverData + ")"));
                return;
            }
            XposedHelpers.findAndHookMethod("android.app.ActivityThread", classLoader,
                    "handleReceiver", receiverData, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            logReceiverData("ActivityThread.handleReceiver", param);
                        }
                    });
        } catch (Throwable t) {
            logHookFailure(hookPoint, t);
        }
    }

    /**
     * 在 ActivityThread 层观测 bindService 投递。
     *
     * <p>Service#onStartCommand 有时不会在 bindService 场景触发，系统唤起也可能走这里。
     * 注意：不要 hook onBind/onUnbind（onBind 是抽象方法，同样会报
     * "is abstract: it has no body to hook"）。</p>
     */
    private void hookActivityThreadBindService(ClassLoader classLoader) {
        String hookPoint = "ActivityThread.handleBindService";
        try {
            Class<?> clazz = XposedHelpers.findClass("android.app.ActivityThread", classLoader);
            Class<?> bindServiceData = findInnerDataClass("android.app.ActivityThread$BindServiceData", classLoader);
            if (clazz == null || bindServiceData == null) {
                logHookFailure(hookPoint, new IllegalStateException(
                        "ActivityThread or BindServiceData class not found (bindServiceData=" + bindServiceData + ")"));
                return;
            }
            XposedHelpers.findAndHookMethod("android.app.ActivityThread", classLoader,
                    "handleBindService", bindServiceData, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            logBindServiceData("ActivityThread.handleBindService", param);
                        }
                    });
        } catch (Throwable t) {
            logHookFailure(hookPoint, t);
        }
    }

    /**
     * 在 ActivityThread 层观测 Service 的 startService 启动（覆盖 onStartCommand 覆写场景）。
     *
     * <p>问题背景：目标应用 com.miui.voiceassist 的 VoiceService 覆写了 onStartCommand，
     * 子类覆写不会命中基类 android.app.Service 上的 hook。改为 hook ActivityThread.handleServiceArgs，
     * 该层对所有 Service 一视同仁（每 startService 调用一次）。仅日志，不改变行为。</p>
     */
    private void hookActivityThreadServiceArgs(ClassLoader classLoader) {
        String hookPoint = "ActivityThread.handleServiceArgs";
        try {
            Class<?> clazz = XposedHelpers.findClass("android.app.ActivityThread", classLoader);
            Class<?> serviceArgsData = findInnerDataClass("android.app.ActivityThread$ServiceArgsData", classLoader);
            if (clazz == null || serviceArgsData == null) {
                logHookFailure(hookPoint, new IllegalStateException(
                        "ActivityThread or ServiceArgsData class not found (serviceArgsData=" + serviceArgsData + ")"));
                return;
            }
            XposedHelpers.findAndHookMethod("android.app.ActivityThread", classLoader,
                    "handleServiceArgs", serviceArgsData, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            logServiceArgsData("ActivityThread.handleServiceArgs", param);
                            // [v2.3] 优先在 VoiceService.onStartCommand 层抑制：那一层
                            // return 不影响框架发 serviceDoneExecuting，AMS 记账不会泄漏。
                            // 只有那一层没装上（类/方法名对不上）才退回本层的 return-early。
                            if (sOnStartCommandHookActive) {
                                return;
                            }
                            try {
                                maybeRedirectFromServiceArgs(param);
                            } catch (Throwable t) {
                                Log.e(TAG, "[redirect] maybeRedirect crashed: " + t, t);
                                XposedBridge.log(TAG + " [redirect] maybeRedirect crashed: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            logHookFailure(hookPoint, t);
        }
    }

    /**
     * [v2.3 · 方案 A] 在 `VoiceService.onStartCommand` 层抑制小爱的 UI。
     *
     * <p>为什么不在 `ActivityThread.handleServiceArgs` 层做：那是 `void` 方法，
     * Xposed 的 `setResult(...)` 等于 return-early，会跳过方法体末尾的
     * `QueuedWork.waitToFinish()` 与 `serviceDoneExecuting(...)`。AMS 侧
     * `sendServiceArgsLocked` 已经 bump 过 executeNesting，只能靠 serviceDoneExecuting
     * 扣回 —— 不扣就永远不归零（实测每手势 +1，20s 后
     * `am_anr: executing service ...VoiceService, waited 20001ms`，随后进程被杀）。</p>
     *
     * <p>在 onStartCommand 层 `setResult(START_STICKY)` 只跳过 App 自己的这次处理，
     * 框架照样把 serviceDoneExecuting 发完，记账闭合。</p>
     */
    private void hookVoiceServiceOnStartCommand(ClassLoader classLoader) {
        String hookPoint = "VoiceService.onStartCommand";
        try {
            Class<?> vs = XposedHelpers.findClass("com.xiaomi.voiceassistant.VoiceService", classLoader);
            if (vs == null) {
                sOnStartCommandHookActive = false;
                logHookFailure(hookPoint, new IllegalStateException("VoiceService class not found"));
                return;
            }
            Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(vs, "onStartCommand",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!(param.thisObject instanceof Service)) {
                                    return;
                                }
                                Intent intent = null;
                                if (param.args != null && param.args.length > 0
                                        && param.args[0] instanceof Intent) {
                                    intent = (Intent) param.args[0];
                                }
                                int startId = -1;
                                if (param.args != null && param.args.length > 2
                                        && param.args[2] instanceof Integer) {
                                    startId = (Integer) param.args[2];
                                }
                                int r = handleInvocation(intent, (Service) param.thisObject, startId);
                                if (r != INV_NOT_TARGET) {
                                    // START_STICKY(1) 而不是 NOT_STICKY(2)：后者在
                                    // startId == lastStartId 时会置 r.stopIfKilled=true，
                                    // 改变小爱被杀后是否重启的行为，这里不碰。
                                    param.setResult(Service.START_STICKY);
                                }
                            } catch (Throwable t) {
                                Log.e(TAG, "[redirect] onStartCommand hook crashed: " + t, t);
                                XposedBridge.log(TAG + " [redirect] onStartCommand hook crashed: " + t);
                            }
                        }
                    });
            int count = (unhooks == null) ? 0 : unhooks.size();
            sOnStartCommandHookActive = count > 0;
            String msg = "[" + hookPoint + "] hooked " + count + " overload(s), serviceArgs fallback="
                    + (!sOnStartCommandHookActive);
            Log.i(TAG, msg);
            XposedBridge.log(TAG + " " + msg);
        } catch (Throwable t) {
            sOnStartCommandHookActive = false;
            logHookFailure(hookPoint, t);
        }
    }

    /**
     * hook 目标应用 com.xiaomi.voiceassistant 的 VoiceService.handleIntent 方法。
     *
     * <p>该方法可能不存在或存在多个重载，使用 hookAllMethods 全部 hook。仅日志，不改变行为。
     * findClass 失败或 0 个重载都只打日志，不抛异常。</p>
     */
    private void hookVoiceServiceHandleIntent(ClassLoader classLoader) {
        String hookPoint = "VoiceService.handleIntent";
        try {
            Class<?> vs = XposedHelpers.findClass("com.xiaomi.voiceassistant.VoiceService", classLoader);
            if (vs == null) {
                logHookFailure(hookPoint, new IllegalStateException("VoiceService class not found"));
                return;
            }
            Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(vs, "handleIntent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    logHandleIntentArgs("VoiceService.handleIntent", param);
                }
            });
            int count = (unhooks == null) ? 0 : unhooks.size();
            String msg = "[" + hookPoint + "] hooked " + count + " overload(s)";
            Log.i(TAG, msg);
            XposedBridge.log(TAG + " " + msg);
        } catch (Throwable t) {
            logHookFailure(hookPoint, t);
        }
    }

    /** 记录 ActivityThread.handleServiceArgs 的 ServiceArgsData.intent / startId / flags。 */
    private static void logServiceArgsData(String hookPoint, XC_MethodHook.MethodHookParam param) {
        try {
            Object data = param.args[0];
            if (data == null) {
                String msg = "[" + hookPoint + "] data=<null>";
                Log.i(TAG, msg);
                XposedBridge.log(TAG + " " + msg);
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append('[').append(hookPoint).append(']')
              .append(" dataClass=").append(data.getClass().getName());

            Intent intent = readIntentField(data);
            appendIntent(sb, intent);

            // 读取 startId / flags，读失败忽略
            try {
                int startId = XposedHelpers.getIntField(data, "startId");
                sb.append(", startId=").append(startId);
            } catch (Throwable t) {
                // 忽略
            }
            try {
                int flags = XposedHelpers.getIntField(data, "flags");
                sb.append(", flags=0x").append(Integer.toHexString(flags));
            } catch (Throwable t) {
                // 忽略
            }

            String msg = sb.toString();
            Log.i(TAG, msg);
            XposedBridge.log(TAG + " " + msg);
        } catch (Throwable t) {
            Log.e(TAG, "logServiceArgsData failed at " + hookPoint, t);
        }
    }

    /**
     * 判定 handleServiceArgs 的 Intent 是否命中要重定向的小爱唤起目标。
     *
     * <p>条件：intent 非空，action 为 ASSIST 或小爱自定义 VOICE_START，且 intent 的
     * component 包名为 com.miui.voiceassist。</p>
     */
    private static boolean isRedirectTarget(Intent intent) {
        if (intent == null) {
            return false;
        }
        String action = intent.getAction();
        boolean actionHit = ACTION_ASSIST.equals(action) || ACTION_VOICE_START.equals(action);
        if (!actionHit) {
            return false;
        }
        ComponentName cn = intent.getComponent();
        return cn != null && TARGET_PACKAGE.equals(cn.getPackageName());
    }

    /**
     * 读回可放行的入口来源枚举；不在白名单内一律返回 {@code null}。
     *
     * <p>返回值同时是 v2.5 透传给 Minis 的 [EXTRA_TRIGGER_SOURCE]：放行与透传用
     * 同一份判定，不会出现"校验过的来源"和"告诉 Minis 的来源"不一致。</p>
     */
    private static String readTriggerSource(Intent intent) {
        if (intent == null) {
            return null;
        }
        try {
            String source = intent.getStringExtra(EXTRA_VOICE_ASSIST_START_FROM_KEY);
            if (SOURCE_DOUBLE_CLICK_GESTURE.equals(source)
                    || SOURCE_LONG_PRESS_GESTURE.equals(source)
                    || SOURCE_LONG_PRESS_POWER.equals(source)) {
                return source;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * [v2.5] 本次是否附带当前屏幕截图。
     *
     * <p>长按电源键是"快速提问"，用户要的是立刻能说话，不要一张屏幕；双击 / 长按
     * 手势条是"屏幕即现场"，默认附带。以前只能靠 {@code app.send.wakeup.command}
     * 这个电源键专有 extra 反推，现在来源枚举已经是白名单的副产物，直接按它判定；
     * 万一枚举缺失（白名单理论上已挡住），再退回旧启发式。</p>
     */
    private static boolean attachScreenFor(Intent intent, String source) {
        if (SOURCE_LONG_PRESS_POWER.equals(source)) {
            return false;
        }
        if (SOURCE_DOUBLE_CLICK_GESTURE.equals(source) || SOURCE_LONG_PRESS_GESTURE.equals(source)) {
            return true;
        }
        return intent == null || !intent.hasExtra(EXTRA_POWER_WAKEUP);
    }

    /**
     * 在 ActivityThread.handleServiceArgs 回调里执行重定向逻辑。
     *
     * <p>仅在 REDIRECT_ENABLED 且命中目标时动作：防抖、从 ServiceArgsData 的 token 经
     * ActivityThread.mServices 取出 Service 实例作为 Context 启动 Minis，成功后抑制原始
     * 调用；任何失败都只打错误日志，不抑制原始调用（保持小爱可用作为兜底）。</p>
     */
    /** 测试/兜底入口：只在 onStartCommand 层没装上时使用（见 sOnStartCommandHookActive）。 */
    private static void maybeRedirectFromServiceArgs(XC_MethodHook.MethodHookParam param) {
        if (!REDIRECT_ENABLED) {
            return;
        }
        Object data = (param.args != null && param.args.length > 0) ? param.args[0] : null;
        Intent intent = readIntentField(data);
        int startId = -1;
        try {
            startId = XposedHelpers.getIntField(data, "startId");
        } catch (Throwable t) {
            // 读不到就用 -1（补账会跳过）
        }
        Service svc = resolveService(data);
        int r = handleInvocation(intent, svc, startId);
        if (r == INV_NOT_TARGET) {
            return;
        }
        // 命中目标：抑制并在 return-early 之前把 AMS 的账补上
        suppressOriginal(param, startId);
    }

    /**
     * 一次唤起是否要改道的唯一判断点（两层 hook 共用）。
     *
     * @param svc     触发这次派发的 Service 实例；null 表示调用方是 handleServiceArgs，
     *                需要自己从 ServiceArgsData 里解析。
     * @param startId onStartCommand / ServiceArgsData 的 startId，仅用于日志与补账。
     * @return {@link #INV_NOT_TARGET} / {@link #INV_SUPPRESS_ONLY} / {@link #INV_LAUNCHED}
     */
    private static int handleInvocation(Intent intent, Service svc, int startId) {
        if (!REDIRECT_ENABLED || !isRedirectTarget(intent)) {
            return INV_NOT_TARGET;
        }
        String source = readTriggerSource(intent);
        if (source == null) {
            String msg = "[redirect] source not whitelisted, keep xiaomi fallback (source="
                    + formatDiagnosticExtraValue(
                            intent == null ? null : intent.getStringExtra(EXTRA_VOICE_ASSIST_START_FROM_KEY))
                    + ")";
            Log.i(TAG, msg);
            XposedBridge.log(TAG + " " + msg);
            return INV_NOT_TARGET;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - sLastRedirectElapsed < DEBOUNCE_MS) {
            String msg = "[redirect] debounced (source=" + source + ")";
            Log.i(TAG, msg);
            XposedBridge.log(TAG + " " + msg);
            return INV_SUPPRESS_ONLY;
        }

        Service host = svc;
        if (host == null) {
            logRedirectError(startId, new IllegalStateException("could not resolve Service instance"));
            return INV_NOT_TARGET;
        }

        try {
            // [v2.5] 截图判定改为按来源枚举：长按电源键＝快速提问不截图，
            // 双击 / 长按手势条＝"屏幕即现场"截图（见 attachScreenFor）。
            boolean attachScreen = attachScreenFor(intent, source);
            long triggerId = now;

            Intent launch = new Intent();
            launch.setComponent(new ComponentName(MINIS_PACKAGE, MINIS_ACTIVITY));
            launch.setAction(ACTION_VOICE_ASSIST);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            launch.putExtra(EXTRA_ATTACH_SCREEN, attachScreen);
            launch.putExtra(EXTRA_TRIGGER_ID, triggerId);
            // [v2.5] 把"是哪只手势"和"哪个模块协议版本"一起交给 Minis：
            // 前者进会话元数据/日志，后者让现场日志能自证模块版本。
            launch.putExtra(EXTRA_TRIGGER_SOURCE, source);
            launch.putExtra(EXTRA_HOOK_VERSION, HOOK_PROTOCOL_VERSION);
            host.startActivity(launch);

            sLastRedirectElapsed = SystemClock.elapsedRealtime();
            String msg = "[redirect] launched Minis, suppressed xiaomi (startId=" + startId
                    + ", source=" + source
                    + ", attachScreen=" + attachScreen
                    + ", triggerId=" + triggerId
                    + ", protocol=" + HOOK_PROTOCOL_VERSION + ")";
            Log.i(TAG, msg);
            XposedBridge.log(TAG + " " + msg);
            return INV_LAUNCHED;
        } catch (Throwable t) {
            logRedirectError(startId, t);
            return INV_NOT_TARGET;
        }
    }

    /**
     * 抑制原始调用（让小爱不显示）。
     *
     * [v2.3 · T-ams-accounting] `param.setResult(...)` 会把 void 的
     * `ActivityThread.handleServiceArgs` 整个方法体跳过 —— 连同方法体末尾那句
     * `ActivityManager.getService().serviceDoneExecuting(token, SERVICE_ARGS, startId, res)`
     * 一起跳过。AMS 侧 `sendServiceArgsLocked` 已经 `bumpServiceExecutingLocked()`
     * （executeNesting+1、executingStart 刷新），只能靠 serviceDoneExecuting 扣回；
     * 不补发就会 executeNesting 只增不减，20s 后触发
     * `appNotResponding("executing service ...VoiceService")`（实测：
     * am_anr waited 20001ms，进程随后被杀）。
     *
     * 这里在 return-early 之前手动补发同一句，把 AMS 的记账闭合。
     * 补发失败只记日志，行为与旧版完全一致（不引入新的失败面）。
     */
    private static void suppressOriginal(XC_MethodHook.MethodHookParam param, int startId) {
        try {
            completeServiceArgs(param, startId);
            param.setResult(0);
        } catch (Throwable t) {
            Log.w(TAG, "[redirect] suppressOriginal failed (startId=" + startId + "): " + t);
            XposedBridge.log(TAG + " [redirect] suppressOriginal failed: " + t);
        }
    }

    /**
     * 补发 `IActivityManager.serviceDoneExecuting(token, SERVICE_DONE_EXECUTING_START, startId, res)`。
     *
     * res 只能是 0..4（AMS `ActiveServices` 会校验，越界抛
     * IllegalArgumentException: Unknown service start result），这里用
     * START_STICKY(1)：START_NOT_STICKY 在 startId == lastStartId 时会置
     * r.stopIfKilled=true，不适合一个被我们"代跑"掉的启动。
     */
    private static void completeServiceArgs(XC_MethodHook.MethodHookParam param, int startId) {
        if (startId < 0 || param.args == null || param.args.length == 0) {
            return;
        }
        Object token;
        try {
            token = XposedHelpers.getObjectField(param.args[0], "token");
        } catch (Throwable t) {
            return;
        }
        if (!(token instanceof android.os.IBinder)) {
            return;
        }
        try {
            Object am = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityManager", null), "getService");
            if (am == null) {
                return;
            }
            XposedHelpers.callMethod(am, "serviceDoneExecuting",
                    new Class<?>[]{ android.os.IBinder.class, int.class, int.class, int.class },
                    token,
                    /* SERVICE_DONE_EXECUTING_START */ 1,
                    startId,
                    /* Service.START_STICKY */ 1);
        } catch (Throwable t) {
            Log.w(TAG, "[redirect] serviceDoneExecuting compensation failed (startId="
                    + startId + "): " + t);
            XposedBridge.log(TAG + " [redirect] serviceDoneExecuting compensation failed: " + t);
        }
    }

    /** 打一条不抑制原始调用的错误日志。 */
    private static void logRedirectError(int startId, Throwable t) {
        String msg = "[redirect] FAILED, NOT suppressed (startId=" + startId + "): " + t;
        Log.e(TAG, msg, t);
        XposedBridge.log(TAG + " " + msg);
    }

    /**
     * 从 ServiceArgsData 解析对应的 Service 实例。
     *
     * <p>读取 data.token，经 ActivityThread.currentActivityThread() 的 mServices Map
     * （Map<IBinder,Service>）以 token 为 key 取出 Service。token 读取失败或 mServices
     * 字段名失败时走兜底（遍历声明字段找以 token 为 key 的 Map），全部失败返回 null。</p>
     */
    private static Service resolveService(Object data) {
        if (data == null) {
            return null;
        }
        try {
            Object token = XposedHelpers.getObjectField(data, "token");
            if (token == null) {
                return null;
            }
            Object at = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread");
            if (at == null) {
                return null;
            }
            Object services = getServicesMap(at, token);
            if (services instanceof Map) {
                Object svc = ((Map<?, ?>) services).get(token);
                if (svc instanceof Service) {
                    return (Service) svc;
                }
            }
        } catch (Throwable t) {
            // 忽略，最终返回 null
        }
        return null;
    }

    /**
     * 取 ActivityThread 的 mServices Map；字段名读取失败时遍历声明字段找能按 token
     * get 到值的 java.util.Map 字段。
     */
    private static Object getServicesMap(Object at, Object token) {
        try {
            return XposedHelpers.getObjectField(at, "mServices");
        } catch (Throwable t) {
            // 字段名兜底
            try {
                for (Field f : at.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    Object v = f.get(at);
                    if (v instanceof Map && ((Map<?, ?>) v).get(token) != null) {
                        return v;
                    }
                }
            } catch (Throwable t2) {
                // 忽略
            }
            return null;
        }
    }

    /** 打印 VoiceService.handleIntent 的逐参数类型名；若某参数是 Intent 则追加其关键信息。 */
    private static void logHandleIntentArgs(String hookPoint, XC_MethodHook.MethodHookParam param) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append('[').append(hookPoint).append(']');
            Object[] args = param.args;
            if (args == null || args.length == 0) {
                sb.append(" no args");
            } else {
                sb.append(" args[");
                for (int i = 0; i < args.length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    Object a = args[i];
                    sb.append(i).append('=').append((a == null) ? "<null>" : a.getClass().getName());
                    if (a instanceof Intent) {
                        sb.append('{');
                        appendIntent(sb, (Intent) a);
                        sb.append('}');
                    }
                }
                sb.append(']');
            }
            String msg = sb.toString();
            Log.i(TAG, msg);
            XposedBridge.log(TAG + " " + msg);
        } catch (Throwable t) {
            Log.e(TAG, "logHandleIntentArgs failed at " + hookPoint, t);
        }
    }

    /** 从 classLoader 或系统默认 classLoader 解析内部数据类。返回 null 表示失败。 */
    private Class<?> findInnerDataClass(String name, ClassLoader classLoader) {
        // 优先使用传入的 classLoader
        if (classLoader != null) {
            try {
                return XposedHelpers.findClass(name, classLoader);
            } catch (Throwable t) {
                logHookFailure("findClass(" + name + ") via classLoader", t);
            }
        }
        // 兜底：用系统默认 classLoader
        try {
            return XposedHelpers.findClass(name, null);
        } catch (Throwable t) {
            logHookFailure("findClass(" + name + ") via null", t);
            return null;
        }
    }

    /** 记录 ActivityThread.handleReceiver 的 ReceiverData.intent / component。 */
    private static void logReceiverData(String hookPoint, XC_MethodHook.MethodHookParam param) {
        try {
            Object data = param.args[0];
            if (data == null) {
                String msg = "[" + hookPoint + "] data=<null>";
                Log.i(TAG, msg);
                XposedBridge.log(TAG + " " + msg);
                return;
            }

            Intent intent = readIntentField(data);
            if (intent == null) {
                String msg = "[" + hookPoint + "] data class=" + data.getClass().getName()
                        + " has no readable 'intent' field. Declared fields: " + declaredFieldNames(data);
                Log.i(TAG, msg);
                XposedBridge.log(TAG + " " + msg);
                logReceiverComponent(data, hookPoint);
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append('[').append(hookPoint).append(']')
              .append(" dataClass=").append(data.getClass().getName());
            appendIntent(sb, intent);
            logReceiverComponent(data, hookPoint);
            String msg = sb.toString();
            Log.i(TAG, msg);
            XposedBridge.log(TAG + " " + msg);
        } catch (Throwable t) {
            Log.e(TAG, "logReceiverData failed at " + hookPoint, t);
        }
    }

    /** 记录 ActivityThread.handleBindService 的 BindServiceData.intent / component。 */
    private static void logBindServiceData(String hookPoint, XC_MethodHook.MethodHookParam param) {
        try {
            Object data = param.args[0];
            if (data == null) {
                String msg = "[" + hookPoint + "] data=<null>";
                Log.i(TAG, msg);
                XposedBridge.log(TAG + " " + msg);
                return;
            }

            Intent intent = readIntentField(data);
            if (intent == null) {
                String msg = "[" + hookPoint + "] data class=" + data.getClass().getName()
                        + " has no readable 'intent' field. Declared fields: " + declaredFieldNames(data);
                Log.i(TAG, msg);
                XposedBridge.log(TAG + " " + msg);
                logBindComponent(data, hookPoint);
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append('[').append(hookPoint).append(']')
              .append(" dataClass=").append(data.getClass().getName());
            appendIntent(sb, intent);
            String msg = sb.toString();
            Log.i(TAG, msg);
            XposedBridge.log(TAG + " " + msg);
            logBindComponent(data, hookPoint);
        } catch (Throwable t) {
            Log.e(TAG, "logBindServiceData failed at " + hookPoint, t);
        }
    }

    /** 尝试从 data 对象读取 intent 字段（兼容不同版本字段名，主名 'intent'）。 */
    private static Intent readIntentField(Object data) {
        if (data == null) {
            return null;
        }
        // 首选 'intent'
        try {
            Object v = XposedHelpers.getObjectField(data, "intent");
            if (v instanceof Intent) {
                return (Intent) v;
            }
        } catch (Throwable t) {
            // 忽略，尝试兜底
        }
        // 兜底：遍历所有声明字段找 Intent 类型的值
        try {
            for (Field f : data.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(data);
                if (v instanceof Intent) {
                    return (Intent) v;
                }
            }
        } catch (Throwable t) {
            // 忽略
        }
        return null;
    }

    /** 列出 data 对象的所有声明字段名（便于下一轮适配）。 */
    private static String declaredFieldNames(Object data) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        try {
            for (Field f : data.getClass().getDeclaredFields()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(f.getName());
            }
        } catch (Throwable t) {
            sb.append("?");
        }
        sb.append(']');
        return sb.toString();
    }

    /** 尝试读取 ReceiverData.component 字段并打印。 */
    private static void logReceiverComponent(Object data, String hookPoint) {
        try {
            Object c = XposedHelpers.getObjectField(data, "component");
            if (c instanceof ComponentName) {
                String msg = "[" + hookPoint + "] component=" + ((ComponentName) c).flattenToShortString();
                Log.i(TAG, msg);
                XposedBridge.log(TAG + " " + msg);
            }
        } catch (Throwable t) {
            // 无 component 字段或读取失败，忽略
        }
    }

    /** 尝试读取 BindServiceData 的组件信息并打印（BindServiceData 有 token 等，component 视版本而定）。 */
    private static void logBindComponent(Object data, String hookPoint) {
        try {
            Object c = XposedHelpers.getObjectField(data, "component");
            if (c instanceof ComponentName) {
                String msg = "[" + hookPoint + "] component=" + ((ComponentName) c).flattenToShortString();
                Log.i(TAG, msg);
                XposedBridge.log(TAG + " " + msg);
            }
        } catch (Throwable t) {
            // 无 component 字段或读取失败，忽略
        }
    }

    /** 把 intent 的 action / component / flags / extrasKeys 追加到 StringBuilder。 */
    private static void appendIntent(StringBuilder sb, Intent intent) {
        if (intent == null) {
            sb.append(", intent=<null>");
            return;
        }
        sb.append(", intent.action=").append(intent.getAction());
        if (intent.getComponent() != null) {
            sb.append(", intent.component=").append(intent.getComponent().flattenToShortString());
        }
        sb.append(", intent.flags=0x").append(Integer.toHexString(intent.getFlags()));
        sb.append(", intent.extrasKeys=").append(extrasKeys(intent));
        appendTriggerExtraValues(sb, intent);
    }

    /**
     * Logs only source-enum extras from redirect candidates; never dumps arbitrary Bundle content.
     */
    private static void appendTriggerExtraValues(StringBuilder sb, Intent intent) {
        if (!isRedirectTarget(intent)) {
            return;
        }
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return;
        }
        for (String key : DIAGNOSTIC_TRIGGER_EXTRAS) {
            if (!extras.containsKey(key)) {
                continue;
            }
            sb.append(", extra.").append(key).append('=')
                    .append(formatDiagnosticExtraValue(extras.get(key)));
        }
    }

    private static String formatDiagnosticExtraValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "<" + value.getClass().getSimpleName() + ">";
    }

    /**
     * 记录被调用对象（thisObject）的类名/组件名，以及 intent 的 action / component / flags / extras keys。
     * 不打印 extras 大对象本身。
     */
    private static void logEntry(String hookPoint, XC_MethodHook.MethodHookParam param) {
        try {
            String component = componentName(param.thisObject);
            Intent intent = findIntent(param.args);

            StringBuilder sb = new StringBuilder();
            sb.append('[').append(hookPoint).append("] component=").append(component);

            if (intent != null) {
                sb.append(", action=").append(intent.getAction());
                if (intent.getComponent() != null) {
                    sb.append(", component=").append(intent.getComponent().flattenToShortString());
                }
                sb.append(", flags=0x").append(Integer.toHexString(intent.getFlags()));
                sb.append(", extrasKeys=").append(extrasKeys(intent));
            } else {
                sb.append(", intent=<null>");
            }

            String msg = sb.toString();
            Log.i(TAG, msg);
            XposedBridge.log(TAG + " " + msg);
        } catch (Throwable t) {
            Log.e(TAG, "logEntry failed at " + hookPoint, t);
        }
    }

    private static String componentName(Object obj) {
        if (obj == null) {
            return "<null>";
        }
        return obj.getClass().getName();
    }

    private static Intent findIntent(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof Intent) {
                return (Intent) arg;
            }
        }
        return null;
    }

    private static String extrasKeys(Intent intent) {
        if (intent == null || intent.getExtras() == null) {
            return "<none>";
        }
        Set<String> keys = intent.getExtras().keySet();
        return keys == null ? "<empty>" : keys.toString();
    }

    private static void logHookFailure(String hookPoint, Throwable t) {
        String msg = "Failed to hook " + hookPoint + ": " + t;
        Log.e(TAG, msg);
        XposedBridge.log(TAG + " " + msg);
    }
}
