package com.openminis.hook;

import android.app.Activity;
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
 * <p>如何关闭：编译期把 {@link #REDIRECT_ENABLED} 改为 false 重新构建；或通过
 * vector-cli modules disable com.openminis.hook 后 am force-stop com.miui.voiceassist。</p>
 */
public class XiaoAiRedirect implements IXposedHookLoadPackage {

    private static final String TAG = "MinisHook";

    /** v2 启用重定向；关闭时仅日志（编译期修改后重新构建）。 */
    public static final boolean REDIRECT_ENABLED = true;

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

    /** 电源键派发携带的标记 extra（实测拓扑：长按电源键的 extras 含该键）。 */
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
        hookVoiceServiceHandleIntent(lpparam.classLoader);
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
                            try {
                                maybeRedirect(param);
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
     * 仅放行已在本机验证的手势与电源键入口。未知来源交还给原生小爱。
     */
    private static boolean isAllowedTriggerSource(Intent intent) {
        if (intent == null) {
            return false;
        }
        try {
            String source = intent.getStringExtra(EXTRA_VOICE_ASSIST_START_FROM_KEY);
            return SOURCE_DOUBLE_CLICK_GESTURE.equals(source)
                    || SOURCE_LONG_PRESS_GESTURE.equals(source)
                    || SOURCE_LONG_PRESS_POWER.equals(source);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 在 ActivityThread.handleServiceArgs 回调里执行重定向逻辑。
     *
     * <p>仅在 REDIRECT_ENABLED 且命中目标时动作：防抖、从 ServiceArgsData 的 token 经
     * ActivityThread.mServices 取出 Service 实例作为 Context 启动 Minis，成功后抑制原始
     * 调用；任何失败都只打错误日志，不抑制原始调用（保持小爱可用作为兜底）。</p>
     */
    private static void maybeRedirect(XC_MethodHook.MethodHookParam param) {
        if (!REDIRECT_ENABLED) {
            return;
        }
        Object data = param.args[0];
        Intent intent = readIntentField(data);
        if (!isRedirectTarget(intent)) {
            return;
        }
        if (!isAllowedTriggerSource(intent)) {
            String msg = "[redirect] source not whitelisted, keep xiaomi fallback (source="
                    + formatDiagnosticExtraValue(intent.getStringExtra(EXTRA_VOICE_ASSIST_START_FROM_KEY))
                    + ")";
            Log.i(TAG, msg);
            XposedBridge.log(TAG + " " + msg);
            return;
        }

        int startId = -1;
        try {
            startId = XposedHelpers.getIntField(data, "startId");
        } catch (Throwable t) {
            // 忽略，startId 读不到则用 -1
        }

        // 防抖：1200ms 内重复触发只抑制不重复拉起
        long now = SystemClock.elapsedRealtime();
        if (now - sLastRedirectElapsed < DEBOUNCE_MS) {
            String msg = "[redirect] debounced";
            Log.i(TAG, msg);
            XposedBridge.log(TAG + " " + msg);
            suppressOriginal(param, startId);
            return;
        }

        // 从 ServiceArgsData 拿 Service 实例作为 Context
        Service svc = resolveService(data);
        if (svc == null) {
            logRedirectError(startId, new IllegalStateException("could not resolve Service instance"));
            return;
        }

        try {
            // [v2.1] 触发源判定：电源键唤起（原始 extras 含 app.send.wakeup.command）
            // 视为快速提问 → 不附带屏幕截图；双击小白条等其余手势 → 附带。
            boolean attachScreen = !intent.hasExtra(EXTRA_POWER_WAKEUP);

            Intent launch = new Intent();
            launch.setComponent(new ComponentName(MINIS_PACKAGE, MINIS_ACTIVITY));
            launch.setAction(ACTION_VOICE_ASSIST);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            launch.putExtra(EXTRA_ATTACH_SCREEN, attachScreen);
            svc.startActivity(launch);

            sLastRedirectElapsed = SystemClock.elapsedRealtime();
            suppressOriginal(param, startId);
            String msg = "[redirect] launched Minis, suppressed xiaomi (startId=" + startId
                    + ", attachScreen=" + attachScreen + ")";
            Log.i(TAG, msg);
            XposedBridge.log(TAG + " " + msg);
        } catch (Throwable t) {
            logRedirectError(startId, t);
        }
    }

    /** 抑制原始调用（让小爱不显示）。 */
    private static void suppressOriginal(XC_MethodHook.MethodHookParam param, int startId) {
        try {
            param.setResult(0);
        } catch (Throwable t) {
            Log.w(TAG, "[redirect] suppressOriginal failed (startId=" + startId + "): " + t);
            XposedBridge.log(TAG + " [redirect] suppressOriginal failed: " + t);
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
