package com.openminis.hook;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * v3 · 内容旁路（A 方案：直投 Minis 上游的分享入口，不改 Minis 代码）。
 *
 * <p>不拦小爱、不抑制小爱：只在小爱进程内识别"哪只手势把它叫起来的"，
 * 然后把这次唤起（以及随后的问句/答复）用 {@code ACTION_SEND} 投给
 * {@code com.openminis.app/.share.ShareReceiverActivity}——这是 OpenMinis 上游
 * 自带、exported 的入口，所以 Minis 侧一行都不用改，也不需要声明数字助理。</p>
 *
 * <p>落点：</p>
 * <ul>
 *   <li>{@code com.xiaomi.voiceassistant.VoiceService#processIntent(Intent)} —— 三条
 *       唤起路径（双击小白条 / 长按电源键 / ubikitouch 的数字助手）都从这里过，
 *       类名与方法名未混淆。</li>
 *   <li>{@code com.xiaomi.voiceassistant.UiManager#onAll(String,String)} —— 结果层，
 *       参数是 (query, answer)，类名与方法名同样未混淆。</li>
 * </ul>
 */
public final class AssistRelay {

    private static final String TAG = "MinisHook";

    private static final String MINIS_PACKAGE = "com.openminis.app";
    /** 小爱包名 / 承载悬浮窗的 Service。 */
    private static final String TARGET_PACKAGE = "com.miui.voiceassist";
    private static final String VOICE_SERVICE = "com.xiaomi.voiceassistant.VoiceService";
    /** 上游 OpenMinis 自带的分享入口（manifest 里 exported=true，收 SEND/SEND_MULTIPLE/VIEW）。 */
    private static final String SHARE_ACTIVITY = "com.openminis.app.share.ShareReceiverActivity";

    /** 总开关。 */
    public static final boolean RELAY_ENABLED = true;
    /** 双击小白条：转发。 */
    public static final boolean FORWARD_DOUBLE_CLICK = true;
    /** 长按电源键：转发。 */
    public static final boolean FORWARD_POWER_LONG_PRESS = true;
    /** 长按小白条：默认不转发（需求只点名两个手势）。 */
    public static final boolean FORWARD_GESTURE_LONG_PRESS = false;
    /** ubikitouch 的数字助手：保持原状，不转发。 */
    public static final boolean FORWARD_UBIKITOUCH = false;
    /** 小爱识屏：暂定，不接管。 */
    public static final boolean FORWARD_SCREEN_RECOGNITION = false;

    /**
     * 转发完成后退出小爱悬浮 UI：让 Minis 成为唯一前台，避免和小爱浮窗重叠。
     *
     * <p>做法是 {@code stopService(VoiceService)}——实测悬浮窗 {@code voice_assist_root}
     * 随 Service 一起消失，下次手势唤起时系统会重新 startForegroundService。</p>
     */
    public static final boolean DISMISS_XIAOAI = true;
    /** 投递后延迟多久再收小爱（先让极分享窗口/Minins 起来）。 */
    private static final long DISMISS_DELAY_MS = 500L;

    /**
     * 是否继续转发"会话内容"（问句 / 小爱答复）。
     *
     * <p>默认关：小爱已经被我们关掉了，它不会再说出答案；开启只在"不退出小爱"的模式下有意义。</p>
     */
    public static final boolean FORWARD_CONTENT = true;

    /** 一次会话的内容转发窗口。 */
    private static final long SESSION_TTL_MS = 120_000L;
    /** 唤起去抖。 */
    private static final long WAKE_DEBOUNCE_MS = 600L;

    private static volatile Context sAppContext;
    private static volatile Session sSession;
    /** 最近一次唤起来源（即使 session 过期也保留，语音 ASR 往往晚于唤起几十秒）。 */
    private static volatile Source sLastSource = Source.OTHER;
    private static volatile long sLastSourceAt;
    /** 来源记忆窗口：语音说完之前都算同一次唤起。 */
    private static final long SOURCE_TTL_MS = 600_000L;
    /** 同一条消息的去重窗口。 */
    private static final long CONTENT_DEDUPE_MS = 30_000L;
    private static volatile long sLastContentAt;
    private static volatile long sLastWakeAt;
    private static volatile String sLastContentKey;
    private static volatile boolean sInstalled;

    private AssistRelay() {
    }

    public static void install(ClassLoader cl) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        hookProcessIntent(cl);
        hookUiAll(cl);
        hookUiAsrResult(cl);
        hookNlpFunnel(cl);
        hookPreWakeup(cl);
        hookSpeechQuery(cl);
    }

    /* ── 入口层 ───────────────────────────────────────────────────── */

    private static void hookProcessIntent(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.xiaomi.voiceassistant.VoiceService", cl,
                    "processIntent", Intent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                onWake((Intent) param.args[0]);
                            } catch (Throwable t) {
                                log("relay wake error: " + t);
                            }
                        }
                    });
            log("hook ok: VoiceService.processIntent");
        } catch (Throwable t) {
            log("hook FAILED: VoiceService.processIntent — " + t);
        }
    }

    /* ── 内容层 ───────────────────────────────────────────────────── */

    private static void hookUiAll(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.xiaomi.voiceassistant.UiManager", cl,
                    "onAll", String.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                onContent((String) param.args[0], (String) param.args[1]);
                            } catch (Throwable t) {
                                log("relay content error: " + t);
                            }
                        }
                    });
            log("hook ok: UiManager.onAll(String,String)");
        } catch (Throwable t) {
            log("hook FAILED: UiManager.onAll(String,String) — " + t);
        }

        // 4 参重载才是引擎主链路（query, isOnlineTimeout, source, requestId）
        try {
            XposedHelpers.findAndHookMethod("com.xiaomi.voiceassistant.UiManager", cl,
                    "onAll", String.class, boolean.class, String.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                onContent((String) param.args[0], null);
                            } catch (Throwable t) {
                                log("relay content(4) error: " + t);
                            }
                        }
                    });
            log("hook ok: UiManager.onAll(String,boolean,String,String)");
        } catch (Throwable t) {
            log("hook FAILED: UiManager.onAll(4) — " + t);
        }

        // 流式中间结果：只打日志，便于确认引擎链路是否活着
        try {
            XposedHelpers.findAndHookMethod("com.xiaomi.voiceassistant.UiManager", cl,
                    "onPartial", String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            log("onPartial: " + param.args[0]);
                        }
                    });
        } catch (Throwable t) {
            log("hook skip: UiManager.onPartial — " + t);
        }
    }

    private static void hookUiAsrResult(ClassLoader cl) {
        try {
            Class<?> ui = XposedHelpers.findClass("com.xiaomi.voiceassistant.UiManager", cl);
            Method target = null;
            for (Method m : ui.getDeclaredMethods()) {
                if ("onAsrResult".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                log("hook skip: UiManager.onAsrResult not found");
                return;
            }
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        onAsrModel(param.args[0]);
                    } catch (Throwable t) {
                        log("relay asr error: " + t);
                    }
                }
            });
            log("hook ok: UiManager.onAsrResult(" + target.getParameterTypes()[0].getName() + ")");
        } catch (Throwable t) {
            log("hook FAILED: UiManager.onAsrResult — " + t);
        }
    }

    /* ── 唤起 ─────────────────────────────────────────────────────── */

    private static void onWake(Intent intent) {
        if (!RELAY_ENABLED || intent == null) {
            return;
        }
        Source source = classify(intent);
        log("wake source=" + source + " startFrom="
                + safeStringExtra(intent, "voice_assist_start_from_key")
                + " triggerFrom=" + safeStringExtra(intent, "triggerFrom")
                + " action=" + intent.getAction());

        if (!isForwardable(source)) {
            sSession = null;
            return;
        }
        noteSource(source, "processIntent");

        // 唤起本身不投递：只记 session，等"用户真正发给小爱的那句话"。
        // 例外：文字输入会随 intent 一起进来（input_content / assist_query）。
        String typed = safeStringExtra(intent, "input_content");
        if (isEmpty(typed)) {
            typed = safeStringExtra(intent, "assist_query");
        }
        if (!isEmpty(typed)) {
            deliverQuery(typed, "typed");
        } else {
            log("session opened (" + source + "), waiting for user message");
        }
    }

    /**
     * 真正的"用户消息"入口：文本走 {@code x20.i0.startQuery/sendNlpRequest(QueryParams)}，
     * 语音走 {@code y00.p.n0(u20.b)}（ASR final）。混淆类按"类名+方法名+参数个数"探测。
     */
    private static void hookNlpFunnel(ClassLoader cl) {
        hookByName(cl, "x20.i0", "startQuery", 1, "startQuery");
        hookByName(cl, "x20.i0", "sendNlpRequest", 1, "sendNlpRequest");
        hookByName(cl, "y00.p", "n0", 1, "asrFinal(n0)");
        hookByName(cl, "y00.h", "onAll", 2, "engine.onAll(2)");
        hookByName(cl, "y00.h", "onAll", 4, "engine.onAll(4)");
    }

    private static void hookByName(ClassLoader cl, String className, String methodName,
                                   int argCount, final String label) {
        try {
            Class<?> c = XposedHelpers.findClass(className, cl);
            int hooked = 0;
            for (final Method m : c.getDeclaredMethods()) {
                if (!methodName.equals(m.getName()) || m.getParameterTypes().length != argCount) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            String q = extractQuery(param.args);
                            if (q != null) {
                                log(label + " query=" + q);
                                deliverQuery(q, label);
                            } else {
                                log(label + " (no query field)");
                            }
                        } catch (Throwable t) {
                            log(label + " err " + t);
                        }
                    }
                });
                hooked++;
            }
            log("hook " + (hooked > 0 ? "ok" : "miss") + ": " + className + "." + methodName + "/" + argCount);
        } catch (Throwable t) {
            log("hook skip: " + className + "." + methodName + " — " + t);
        }
    }

    /** 从参数里捞 query：字符串直接用，其它对象试 getQuery()。 */
    private static String extractQuery(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object a : args) {
            if (a == null) {
                continue;
            }
            if (a instanceof String) {
                String str = (String) a;
                if (!isEmpty(str) && str.length() > 1) {
                    return str;
                }
                continue;
            }
            String q = callString(a, "getQuery");
            if (!isEmpty(q)) {
                return q;
            }
        }
        return null;
    }

    /** 电源键/语音走的是 pre_wakeup 通道，不经过 processIntent，需要单独记来源。 */
    private static void hookPreWakeup(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("ad0.c", cl, "handle", Intent.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Intent i = (Intent) param.args[0];
                        Source src = classify(i);
                        log("preWakeup source=" + src + " extras="
                                + safeStringExtra(i, "voice_assist_start_from_key"));
                        if (isForwardable(src)) {
                            noteSource(src, "preWakeup");
                        }
                    } catch (Throwable t) {
                        log("preWakeup err " + t);
                    }
                }
            });
            log("hook ok: ad0.c.handle(Intent)");
        } catch (Throwable t) {
            log("hook skip: ad0.c.handle — " + t);
        }
    }

    /** 语音/H5 查询通道：action=com.miui.voiceassist.query，extras 里直接带 assist_query。 */
    private static void hookSpeechQuery(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.xiaomi.voiceassistant.SpeechQueryService", cl,
                    "onStartCommand", Intent.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Intent i = (Intent) param.args[0];
                                Source src = classify(i);
                                log("speechQuery source=" + src + " action=" + i.getAction());
                                if (isForwardable(src)) {
                                    noteSource(src, "speechQuery");
                                }
                                String q = i.getStringExtra("assist_query");
                                if (!isEmpty(q)) {
                                    deliverQuery(q, "speechQuery");
                                }
                            } catch (Throwable t) {
                                log("speechQuery err " + t);
                            }
                        }
                    });
            log("hook ok: SpeechQueryService.onStartCommand");
        } catch (Throwable t) {
            log("hook skip: SpeechQueryService.onStartCommand — " + t);
        }
    }

    /* ── 内容：用户发给小爱的那句话 ─────────────────────────────── */

    /** UiManager.onAll(query, answer)：旧/浮窗路径的引擎结果层。 */
    private static void onContent(String query, String answer) {
        if (!FORWARD_CONTENT || isEmpty(query)) {
            return;
        }
        deliverQuery(query, "nlp");
    }

    /** UiManager.onAsrResult(&lt;u20.b&gt;)：兜底。 */
    private static void onAsrModel(Object model) {
        if (model == null || !FORWARD_CONTENT) {
            return;
        }
        String query = callString(model, "getQuery");
        if (!isEmpty(query)) {
            deliverQuery(query, "asrResult");
        }
    }

    /**
     * 只转"用户消息"，不转小爱的答复 —— Minis 收到后自己回答。
     *
     * <p>语音场景下 ASR 结果常常比唤起晚十几秒，所以不再要求"有活跃 session"，
     * 用 {@link #resolvedSource()} 回溯最近的唤起来源。</p>
     */
    private static void deliverQuery(String query, String from) {
        if (!RELAY_ENABLED || isEmpty(query)) {
            return;
        }
        String text = query.trim();
        long now = SystemClock.elapsedRealtime();
        if (text.equals(sLastContentKey) && now - sLastContentAt < CONTENT_DEDUPE_MS) {
            log("message deduped (" + from + ")");
            return;
        }
        sLastContentKey = text;
        sLastContentAt = now;

        Source source = resolvedSource();
        log("user message via " + from + ": " + text + " [" + source + "]");
        deliver(text, source);
    }

    private static void noteSource(Source source, String where) {
        sLastSource = source;
        sLastSourceAt = SystemClock.elapsedRealtime();
        sSession = new Session(source, sLastSourceAt);
        log("source noted via " + where + ": " + source);
    }

    /** 优先用活跃 session，其次用最近 10 分钟内的来源，最后按"语音"兜底。 */
    private static Source resolvedSource() {
        long now = SystemClock.elapsedRealtime();
        Session s = sSession;
        if (s != null && now - s.startedAt <= SESSION_TTL_MS) {
            return s.source;
        }
        if (now - sLastSourceAt <= SOURCE_TTL_MS) {
            return sLastSource;
        }
        return Source.VOICE;
    }

    /* ── 投递：ACTION_SEND → 上游 ShareReceiverActivity ───────────── */

    private static void deliver(String text, Source source) {
        Context ctx = sAppContext;
        if (ctx == null) {
            log("deliver skipped: no context");
            return;
        }
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text);
            send.setClassName(MINIS_PACKAGE, SHARE_ACTIVITY);
            send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(send);
            log("delivered → Minis (" + source + ", " + text.length() + " chars)");
            if (DISMISS_XIAOAI) {
                scheduleDismissXiaoAi(ctx, source);
            }
        } catch (Throwable t) {
            log("deliver failed: " + t);
        }
    }

    /**
     * 延迟收掉小爱悬浮窗：stopService 后 {@code voice_assist_root} 一起消失。
     * 500ms 是为了让 ShareReceiverActivity → Minis 有个起来的窗口，避免出现"两边都不在前台"的空档。
     */
    private static void scheduleDismissXiaoAi(final Context ctx, final Source source) {
        try {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent stop = new Intent();
                        stop.setClassName(TARGET_PACKAGE, VOICE_SERVICE);
                        boolean ok = ctx.stopService(stop);
                        log("dismiss xiaoai (" + source + ") stopService=" + ok);
                    } catch (Throwable t) {
                        log("dismiss xiaoai failed: " + t);
                    }
                }
            }, DISMISS_DELAY_MS);
        } catch (Throwable t) {
            log("schedule dismiss failed: " + t);
        }
    }

    /* ── 分类 ─────────────────────────────────────────────────────── */

    enum Source {
        DOUBLE_CLICK_GESTURE("双击小白条"),
        GESTURE_LONG_PRESS("长按小白条"),
        POWER_LONG_PRESS("长按电源键"),
        SCREEN_RECOGNITION("小爱识屏"),
        UBIKITOUCH("数字助手"),
        VOICE("语音"),
        OTHER("其它");

        final String label;

        Source(String label) {
            this.label = label;
        }
    }

    static Source classify(Intent intent) {
        String from = safeStringExtra(intent, "voice_assist_start_from_key");
        if (from != null) {
            switch (from) {
                case "double_click_fullscreen_gesture_line":
                    return Source.DOUBLE_CLICK_GESTURE;
                case "long_press_fullscreen_gesture_line":
                    return Source.GESTURE_LONG_PRESS;
                case "long_press_power_key":
                case "long_press_power_launch_xiaoai":
                    // 电源键内部再叫一次 VoiceService 时会用这个来源串
                case "power_long_click_event":
                    return Source.POWER_LONG_PRESS;
                case "system_input_gesture":
                case "two_gesture_long_press":
                case "system_screen_capture":
                case "circle_recognition_click_back":
                    return Source.SCREEN_RECOGNITION;
                default:
                    return Source.OTHER;
            }
        }
        if (Intent.ACTION_ASSIST.equals(intent.getAction())) {
            return Source.UBIKITOUCH;
        }
        return Source.OTHER;
    }

    private static boolean isForwardable(Source source) {
        switch (source) {
            case DOUBLE_CLICK_GESTURE:
                return FORWARD_DOUBLE_CLICK;
            case POWER_LONG_PRESS:
                return FORWARD_POWER_LONG_PRESS;
            case GESTURE_LONG_PRESS:
                return FORWARD_GESTURE_LONG_PRESS;
            case UBIKITOUCH:
                return FORWARD_UBIKITOUCH;
            case SCREEN_RECOGNITION:
                return FORWARD_SCREEN_RECOGNITION;
            default:
                return false;
        }
    }

    /* ── 杂项 ─────────────────────────────────────────────────────── */

    public static void onApplicationCreate(Application app) {
        if (app == null) {
            return;
        }
        sAppContext = app;
        log("app context attached");
    }

    private static Session validSession() {
        Session s = sSession;
        if (s == null) {
            return null;
        }
        if (SystemClock.elapsedRealtime() - s.startedAt > SESSION_TTL_MS) {
            sSession = null;
            return null;
        }
        return s;
    }

    private static final class Session {
        final Source source;
        final long triggerId;
        final long startedAt;

        Session(Source source, long startedAt) {
            this.source = source;
            this.startedAt = startedAt;
            this.triggerId = startedAt;
        }
    }

    private static String safeStringExtra(Intent intent, String key) {
        try {
            return intent.getStringExtra(key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String callString(Object target, String getter) {
        try {
            Object v = XposedHelpers.callMethod(target, getter);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + " " + msg);
    }
}
