package com.openminis.hook;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * v3 · 内容旁路（A 方案：直投 Minis 上游的分享入口，不改 Minis 代码）。
 *
 * <p>不拦小爱、不抑制小爱：只在小爱进程内识别"哪只手势把它叫起来的"，
 * 然后把这次唤起（以及随后的问句 / 图片 / 文件）用 {@code ACTION_SEND} 投给
 * {@code com.openminis.app/.share.ShareReceiverActivity}——这是 OpenMinis 上游
 * 自带、exported 的入口，所以 Minis 侧一行都不用改，也不需要声明数字助理。</p>
 *
 * <p>落点：</p>
 * <ul>
 *   <li>{@code com.xiaomi.voiceassistant.VoiceService#processIntent(Intent)} —— 三条
 *       唤起路径（双击小白条 / 长按电源键 / ubikitouch 的数字助手）都从这里过，
 *       类名与方法名未混淆。</li>
 *   <li>{@code UiManager#onAll(String,String)} —— 结果层，参数是 (query, answer)。</li>
 *   <li>{@code sh0.n1#startFileUpload(Uri, …)} —— 小爱的多模态附件总入口
 *       （相册图片 / 拍照 / 识屏截图 / 文档），在那里抓本地副本。</li>
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
    /** 小爱自己的 FileProvider（cache_path 根 = cacheDir，grantUriPermissions=true）。 */
    private static final String FILE_AUTHORITY = "com.miui.voiceassist.fileprovider";

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
    /** 小爱识屏：暂定，不接管（置 true 后识屏截图会作为图片附件一起转发）。 */
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

    /** 图片 / 文件转发。 */
    public static final boolean FORWARD_MEDIA = true;
    /**
     * SpeechQueryService 通道（{@code x20.i0.startServiceQuery}）的转发开关，默认关。
     *
     * <p>实测这条通道不只承载用户输入：点小爱结果卡片上的按钮也会被包装成一条
     * query（例如"打开京东金融"），转发出去就变成一条用户没说过的话，还会顺手
     * 把小爱关掉。真人的语音走 {@code y00.p.n0}（ASR final）、打字走
     * {@code x20.i0.startQuery}，两条都已覆盖，所以这里只记录不投递。</p>
     */
    public static final boolean FORWARD_SPEECH_QUERY = false;
    /** 仅供 adb 联调：唤起 intent 里带 {@code minis_relay_attach_uri=<content uri>} 时直接挂一个附件。 */
    public static final boolean DEBUG_ATTACH_FROM_INTENT = true;

    /** 一次会话的内容转发窗口。 */
    private static final long SESSION_TTL_MS = 120_000L;
    private static volatile Context sAppContext;
    /** 宿主（小爱）的 ClassLoader：反射找 androidx 的 FileProvider 必须用它。 */
    private static volatile ClassLoader sHostLoader;
    private static volatile Session sSession;
    /** 最近一次唤起来源（即使 session 过期也保留，语音 ASR 往往晚于唤起几十秒）。 */
    private static volatile Source sLastSource = Source.OTHER;
    private static volatile long sLastSourceAt;
    /** 来源记忆窗口：语音说完之前都算同一次唤起。 */
    private static final long SOURCE_TTL_MS = 600_000L;
    /** 同一条消息的去重窗口。 */
    private static final long CONTENT_DEDUPE_MS = 30_000L;
    private static volatile long sLastContentAt;
    private static volatile String sLastContentKey;
    private static volatile boolean sInstalled;

    /* 附件缓存：用户先挂图、后提问，所以附件在"提问"之前就要记下来。 */
    /** 附件有效期：超过就丢弃，避免上一轮截图粘到下一轮对话。 */
    private static final long MEDIA_TTL_MS = 300_000L;
    /** 单个附件上限（同步复制，太大就放弃，别把 小爱 的 UI 线程卡住）。 */
    private static final long MEDIA_MAX_BYTES = 32L * 1024 * 1024;
    /** 一次最多转几个附件。 */
    private static final int MEDIA_MAX_ITEMS = 4;
    /** 附件先投、文本后投的间隔：Minis 侧两次分享会合并到同一条消息。 */
    private static final long MEDIA_TEXT_DELAY_MS = 450L;
    /** 本地副本目录（在小爱 cacheDir 下，正好落在 FileProvider 的 cache_path 根里）。 */
    private static final String MEDIA_DIR = "relay";

    private static final Object MEDIA_LOCK = new Object();
    private static final List<Media> sMedia = new ArrayList<>();
    private static int sMediaSeq;

    private AssistRelay() {
    }

    public static void install(ClassLoader cl) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        sHostLoader = cl;
        hookProcessIntent(cl);
        hookUiAll(cl);
        hookUiAsrResult(cl);
        hookNlpFunnel(cl);
        hookPreWakeup(cl);
        hookSpeechQuery(cl);
        hookMultimodalUpload(cl);
        hookMultimodalSend(cl);
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

        noteSource(source, "processIntent");
        if (!isForwardable(source)) {
            dropMedia();
            return;
        }

        // 联调入口：--es minis_relay_attach_uri content://media/...
        String debugUri = safeStringExtra(intent, "minis_relay_attach_uri");
        if (DEBUG_ATTACH_FROM_INTENT && !isEmpty(debugUri)) {
            onMediaAttached(Uri.parse(debugUri), "IMAGE", "debug");
        }

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
                        noteSource(src, "preWakeup");
                        if (!isForwardable(src)) {
                            dropMedia();
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
                                log("speechQuery source=" + src + " action=" + i.getAction()
                                        + " query=" + safeStringExtra(i, "assist_query")
                                        + " origin=" + safeStringExtra(i, "queryOrigin")
                                        + " fromH5=" + safeIntExtra(i, "voice_assist_from_h5")
                                        + " addQueryCard=" + safeBoolExtra(i, "is_add_query_card")
                                        + " fromVT=" + safeBoolExtra(i, "fromVT"));
                                noteSource(src, "speechQuery");
                                String q = i.getStringExtra("assist_query");
                                if (FORWARD_SPEECH_QUERY && !isEmpty(q)) {
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
     *
     * <p>顺序：附件先投、文本后投（{@link #MEDIA_TEXT_DELAY_MS}）。Minis 的
     * {@code SharedShareStore} 在 300s 内会把两次分享**合并**成同一条待注入记录，
     * 所以最终是"输入框里是问句 + 一张图片附件"。</p>
     */
    private static void deliverQuery(String query, String from) {
        if (!RELAY_ENABLED) {
            return;
        }
        String text = isEmpty(query) ? "" : query.trim();
        Source source = resolvedSource();
        if (!isDeliverable(source)) {
            // 这条链路用户明确说了不转发（ubikitouch / 识屏）。
            log("message skipped via " + from + " (" + source + " not forwarded)");
            return;
        }
        if (!text.isEmpty()) {
            long now = SystemClock.elapsedRealtime();
            if (text.equals(sLastContentKey) && now - sLastContentAt < CONTENT_DEDUPE_MS) {
                log("message deduped (" + from + ")");
                return;
            }
            sLastContentKey = text;
            sLastContentAt = now;
        }

        List<Media> media = takeMedia();
        if (media.isEmpty()) {
            if (text.isEmpty()) {
                return;
            }
            log("user message via " + from + ": " + text + " [" + source + "]");
            sendText(text, source);
            if (DISMISS_XIAOAI) {
                scheduleDismissXiaoAi(sAppContext, source, DISMISS_DELAY_MS);
            }
            return;
        }

        log("user message via " + from + ": "
                + (text.isEmpty() ? "(attachment only)" : text)
                + " [" + source + "] + " + media.size() + " attachment(s)");
        sendMedia(media, source);
        final Context ctx = sAppContext;
        if (text.isEmpty()) {
            if (DISMISS_XIAOAI) {
                scheduleDismissXiaoAi(ctx, source, DISMISS_DELAY_MS);
            }
            return;
        }
        final String tail = text;
        final Source tailSource = source;
        postDelayed(new Runnable() {
            @Override
            public void run() {
                sendText(tail, tailSource);
                if (DISMISS_XIAOAI) {
                    scheduleDismissXiaoAi(ctx, tailSource, DISMISS_DELAY_MS);
                }
            }
        }, MEDIA_TEXT_DELAY_MS);
    }

    private static void noteSource(Source source, String where) {
        sLastSource = source;
        sLastSourceAt = SystemClock.elapsedRealtime();
        if (isForwardable(source)) {
            sSession = new Session(source, sLastSourceAt);
            log("source noted via " + where + ": " + source);
        } else {
            sSession = null;
            log("source noted (not forwarded) via " + where + ": " + source);
        }
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

    /**
     * 消息层能不能投：和 {@link #isForwardable(Source)} 不同，这里管的是
     * "用户已经把话发给小爱了，要不要送到 Minis"。默认放行（包括来源未知时的
     * 语音兜底），只挡明确说"保持原状"的那几条链路。
     */
    private static boolean isDeliverable(Source source) {
        switch (source) {
            case UBIKITOUCH:
                return FORWARD_UBIKITOUCH;
            case SCREEN_RECOGNITION:
                return FORWARD_SCREEN_RECOGNITION;
            case GESTURE_LONG_PRESS:
                return FORWARD_GESTURE_LONG_PRESS;
            default:
                return true;
        }
    }

    /* ── 投递：ACTION_SEND → 上游 ShareReceiverActivity ───────────── */

    private static void sendText(String text, Source source) {
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
        } catch (Throwable t) {
            log("deliver failed: " + t);
        }
    }

    /**
     * 图片 / 文件投递：{@code ACTION_SEND}（多个用 {@code ACTION_SEND_MULTIPLE}）
     * + {@code EXTRA_STREAM} + {@code FLAG_GRANT_READ_URI_PERMISSION}。
     *
     * <p>Uri 是小爱自己 FileProvider 的 {@code cache_path/relay/...}，所以
     * Minis 的 {@code contentResolver.openInputStream} 才读得到；grant 显式给一次，
     * 再靠 intent flag 兜一次（Android 会把 EXTRA_STREAM 迁到 ClipData 并带上授权）。</p>
     */
    private static void sendMedia(List<Media> media, Source source) {
        Context ctx = sAppContext;
        if (ctx == null) {
            log("media skipped: no context");
            return;
        }
        try {
            boolean multiple = media.size() > 1;
            Intent send = new Intent(multiple ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND);
            send.setType(commonType(media));
            if (multiple) {
                ArrayList<Uri> uris = new ArrayList<>();
                for (Media m : media) {
                    uris.add(m.uri);
                    grantRead(ctx, m.uri);
                }
                send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            } else {
                Media m = media.get(0);
                grantRead(ctx, m.uri);
                send.putExtra(Intent.EXTRA_STREAM, m.uri);
            }
            send.setClassName(MINIS_PACKAGE, SHARE_ACTIVITY);
            send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(send);
            log("media delivered → Minis (" + source + ", " + media.size() + " item(s), "
                    + send.getType() + ", " + totalBytes(media) + " bytes)");
        } catch (Throwable t) {
            log("media deliver failed: " + t);
        }
    }

    private static void grantRead(Context ctx, Uri uri) {
        try {
            ctx.grantUriPermission(MINIS_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            log("granted read to " + MINIS_PACKAGE + ": " + uri);
        } catch (Throwable t) {
            log("grantUriPermission failed: " + t);
        }
    }

    private static String commonType(List<Media> media) {
        String type = media.get(0).mime;
        for (Media m : media) {
            if (!m.mime.equals(type)) {
                return "*/*";
            }
        }
        return isEmpty(type) ? "*/*" : type;
    }

    private static long totalBytes(List<Media> media) {
        long total = 0;
        for (Media m : media) {
            total += m.bytes;
        }
        return total;
    }

    /**
     * 延迟收掉小爱悬浮窗：stopService 后 {@code voice_assist_root} 一起消失。
     * 500ms 是为了让 ShareReceiverActivity → Minis 有个起来的窗口，避免出现"两边都不在前台"的空档。
     */
    private static void scheduleDismissXiaoAi(final Context ctx, final Source source, long delayMs) {
        if (ctx == null) {
            return;
        }
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
            }, delayMs);
        } catch (Throwable t) {
            log("schedule dismiss failed: " + t);
        }
    }

    private static void postDelayed(Runnable r, long delayMs) {
        try {
            new Handler(Looper.getMainLooper()).postDelayed(r, delayMs);
        } catch (Throwable t) {
            log("postDelayed failed: " + t);
        }
    }

    /* ── 附件：图片 / 文件 ───────────────────────────────────────────
     *
     * 小爱所有多模态附件最后都落到 {@code sh0.n1.startFileUpload(Uri, n1.b, …)}
     * —— 它的类名是 MultimodalUploadManager：把用户选的图/文档上传到小爱云端、
     * 再把 fileId 挂到输入框上。调用它的有：
     *   th0.o / th0.l           相册选图、文件选择（GetPictureViaActivity 结果）
     *   oh0.t                   拍照 / 卡片上的"传图"
     *   BaseScreenRecognitionView、qh0.n0   小爱识屏（全屏识屏 / 圈选）
     *   VoiceService            语音里带的文件（from=SOUND_RECORD）
     *
     * 我们在 before 阶段同步抓一份本地副本，原因：
     *   1. 用户是"先挂图、后提问"，附件必须比问句先到手；
     *   2. 小爱上云之后本地路径就不再可靠，越早取越安全。
     */

    private static void hookMultimodalUpload(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("sh0.n1", cl);
            int hooked = 0;
            for (final Method m : c.getDeclaredMethods()) {
                if (!"startFileUpload".equals(m.getName())) {
                    continue;
                }
                Class<?>[] types = m.getParameterTypes();
                if (types.length != 7 || types[0] != Uri.class || !types[1].isEnum()) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object kind = param.args[1];
                            onMediaAttached((Uri) param.args[0],
                                    kind instanceof Enum ? ((Enum<?>) kind).name() : String.valueOf(kind),
                                    (String) param.args[5]);
                        } catch (Throwable t) {
                            log("media attach error: " + t);
                        }
                    }
                });
                hooked++;
            }
            log("hook " + (hooked > 0 ? "ok" : "miss") + ": sh0.n1.startFileUpload(Uri,type,…)");
        } catch (Throwable t) {
            log("hook skip: sh0.n1.startFileUpload — " + t);
        }
    }

    /**
     * 带附件的"发送"：用户在输入框里发出去（或说完）时，图片/文档走的是
     * {@code sh0.j#sendMultimodalUnderstand(String query, boolean)}，
     * 而不是文本会话的 {@code x20.i0.startQuery}——所以这条必须单独挂，
     * 否则"看图 + 提问"整条消息都看不见（真机 13:57 那次就是这样丢的）。
     *
     * <p>实现类：{@code sh0.j1}（图片）、{@code sh0.u0}（文档 V2）、
     * {@code sh0.e0}、{@code sh0.u1}（网页文本上传）。query 允许为空——
     * 只发图不配文字也算一次用户消息。</p>
     */
    private static void hookMultimodalSend(ClassLoader cl) {
        hookMultimodalSend(cl, "sh0.j1", "image.send");
        hookMultimodalSend(cl, "sh0.u0", "doc.send");
        hookMultimodalSend(cl, "sh0.e0", "upload.send");
        hookMultimodalSend(cl, "sh0.u1", "web.send");
    }

    private static void hookMultimodalSend(ClassLoader cl, String className, final String label) {
        try {
            Class<?> c = XposedHelpers.findClass(className, cl);
            int hooked = 0;
            for (final Method m : c.getDeclaredMethods()) {
                if (!"sendMultimodalUnderstand".equals(m.getName())) {
                    continue;
                }
                Class<?>[] types = m.getParameterTypes();
                if (types.length != 2 || types[0] != String.class) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            String query = param.args[0] instanceof String ? (String) param.args[0] : "";
                            log(label + " send query=" + query);
                            deliverQuery(query, label);
                        } catch (Throwable t) {
                            log(label + " err " + t);
                        }
                    }
                });
                hooked++;
            }
            log("hook " + (hooked > 0 ? "ok" : "miss") + ": " + className + ".sendMultimodalUnderstand/2");
        } catch (Throwable t) {
            log("hook skip: " + className + ".sendMultimodalUnderstand — " + t);
        }
    }

    private static void onMediaAttached(Uri src, String kind, String from) {
        if (!RELAY_ENABLED || !FORWARD_MEDIA || src == null) {
            return;
        }
        Context ctx = sAppContext;
        if (ctx == null) {
            log("media attach skipped: no context");
            return;
        }
        log("media attach " + kind + " from=" + from + " uri=" + src);
        long start = SystemClock.elapsedRealtime();
        Media media = materialize(ctx, src, kind);
        if (media == null) {
            return;
        }
        synchronized (MEDIA_LOCK) {
            pruneMediaLocked();
            if (sMedia.size() >= MEDIA_MAX_ITEMS) {
                sMedia.remove(0);
            }
            sMedia.add(media);
        }
        log("media staged " + media.name + " mime=" + media.mime + " bytes=" + media.bytes
                + " copy=" + (SystemClock.elapsedRealtime() - start) + "ms uri=" + media.uri);
    }

    /**
     * 把附件复制到 {@code cacheDir/relay/}，再用小爱的 FileProvider 生成 content uri。
     *
     * <p>复制是同步的：{@code startFileUpload} 在小爱 UI 线程上跑，单张图通常几十毫秒，
     * 换来的是"附件一定比随后的问句先入队"这个顺序保证。</p>
     */
    private static Media materialize(Context ctx, Uri src, String kind) {
        ContentResolver cr = ctx.getContentResolver();
        String displayName = queryDisplayName(cr, src);
        String mime = null;
        try {
            mime = cr.getType(src);
        } catch (Throwable t) {
            log("media getType failed: " + t);
        }
        if (isEmpty(mime)) {
            mime = guessMime(displayName, kind);
        }
        String ext = extensionOf(mime, displayName);

        File dir = new File(ctx.getCacheDir(), MEDIA_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            log("media dir create failed: " + dir);
            return null;
        }
        String name;
        synchronized (MEDIA_LOCK) {
            name = "relay-" + System.currentTimeMillis() + "-" + (sMediaSeq++)
                    + (isEmpty(ext) ? "" : "." + ext);
        }
        File dest = new File(dir, name);
        long copied = 0;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = cr.openInputStream(src);
            if (in == null) {
                log("media open returned null: " + src);
                return null;
            }
            out = new FileOutputStream(dest);
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) > 0) {
                copied += read;
                if (copied > MEDIA_MAX_BYTES) {
                    log("media too large (> " + MEDIA_MAX_BYTES + " bytes), dropped: " + src);
                    copied = 0;
                    break;
                }
                out.write(buf, 0, read);
            }
            out.flush();
        } catch (Throwable t) {
            log("media copy failed: " + t);
            copied = 0;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
        if (copied <= 0) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            return null;
        }
        Uri outUri = fileProviderUri(ctx, dest);
        if (outUri == null) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            return null;
        }
        return new Media(outUri, mime, name, SystemClock.elapsedRealtime(), copied, displayName);
    }

    /**
     * {@code androidx.core.content.FileProvider.getUriForFile(ctx, authority, file)}。
     *
     * <p>模块只依赖 Xposed API，编译期没有 androidx，所以走反射；失败时按
     * FileProvider 的规则手工拼一个（cache_path 根就是 cacheDir，路径为
     * {@code /cache_path/<相对路径>}）。</p>
     */
    private static Uri fileProviderUri(Context ctx, File file) {
        try {
            ClassLoader loader = sHostLoader;
            Class<?> provider = loader != null
                    ? Class.forName("androidx.core.content.FileProvider", false, loader)
                    : Class.forName("androidx.core.content.FileProvider");
            Method getter = provider.getMethod("getUriForFile", Context.class, String.class, File.class);
            Object uri = getter.invoke(null, ctx, FILE_AUTHORITY, file);
            if (uri instanceof Uri) {
                return (Uri) uri;
            }
        } catch (Throwable t) {
            log("FileProvider reflection failed: " + t);
        }
        try {
            String base = ctx.getCacheDir().getCanonicalPath();
            String path = file.getCanonicalPath();
            if (!path.startsWith(base + "/")) {
                return null;
            }
            String relative = path.substring(base.length() + 1);
            return Uri.parse("content://" + FILE_AUTHORITY + "/cache_path/" + Uri.encode(relative, "/"));
        } catch (Throwable t) {
            log("file uri fallback failed: " + t);
            return null;
        }
    }

    private static String queryDisplayName(ContentResolver cr, Uri src) {
        Cursor cursor = null;
        try {
            cursor = cr.query(src, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Throwable t) {
            log("media display name failed: " + t);
        } finally {
            closeQuietly(cursor);
        }
        String fallback = src.getLastPathSegment();
        return isEmpty(fallback) ? null : fallback;
    }

    private static String guessMime(String displayName, String kind) {
        if (!isEmpty(displayName)) {
            int dot = displayName.lastIndexOf('.');
            if (dot >= 0 && dot < displayName.length() - 1) {
                String ext = displayName.substring(dot + 1).toLowerCase();
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (!isEmpty(mime)) {
                    return mime;
                }
            }
        }
        return "DOCUMENT".equals(kind) ? "application/octet-stream" : "image/*";
    }

    private static String extensionOf(String mime, String displayName) {
        if (!isEmpty(displayName)) {
            int dot = displayName.lastIndexOf('.');
            if (dot >= 0 && dot < displayName.length() - 1) {
                String ext = displayName.substring(dot + 1).toLowerCase();
                if (ext.length() <= 6 && ext.matches("[a-z0-9]+")) {
                    return ext;
                }
            }
        }
        if (!isEmpty(mime)) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (!isEmpty(ext)) {
                return ext;
            }
            int slash = mime.indexOf('/');
            if (slash > 0 && slash < mime.length() - 1) {
                String sub = mime.substring(slash + 1).toLowerCase();
                if (sub.matches("[a-z0-9]{1,6}")) {
                    return sub;
                }
            }
        }
        return null;
    }

    private static void pruneMediaLocked() {
        long now = SystemClock.elapsedRealtime();
        for (int i = sMedia.size() - 1; i >= 0; i--) {
            if (now - sMedia.get(i).at > MEDIA_TTL_MS) {
                sMedia.remove(i);
            }
        }
    }

    private static void dropMedia() {
        synchronized (MEDIA_LOCK) {
            sMedia.clear();
        }
    }

    private static List<Media> takeMedia() {
        if (!FORWARD_MEDIA) {
            dropMedia();
            return new ArrayList<>();
        }
        synchronized (MEDIA_LOCK) {
            pruneMediaLocked();
            List<Media> out = new ArrayList<>(sMedia);
            sMedia.clear();
            return out;
        }
    }

    private static final class Media {
        final Uri uri;
        final String mime;
        final String name;
        final long at;
        final long bytes;
        final String displayName;

        Media(Uri uri, String mime, String name, long at, long bytes, String displayName) {
            this.uri = uri;
            this.mime = mime;
            this.name = name;
            this.at = at;
            this.bytes = bytes;
            this.displayName = displayName;
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
        sweepMediaCacheAsync(app);
    }

    /**
     * 清掉上一次运行留下的 relay-*.jpg / pdf：Minis 收到后会把字节复制到自己
     * 的 staging 目录，小爱 cache 里的这份只是投递用的中转，保留 30 分钟就该走了。
     */
    private static void sweepMediaCacheAsync(final Context ctx) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = new File(ctx.getCacheDir(), MEDIA_DIR);
                    File[] files = dir.listFiles();
                    if (files == null) {
                        return;
                    }
                    long now = System.currentTimeMillis();
                    int removed = 0;
                    for (File f : files) {
                        if (now - f.lastModified() > MEDIA_TTL_MS) {
                            if (f.delete()) {
                                removed++;
                            }
                        }
                    }
                    if (removed > 0) {
                        log("media cache swept: " + removed + " file(s)");
                    }
                } catch (Throwable t) {
                    log("media cache sweep failed: " + t);
                }
            }
        }, "minis-relay-sweep");
        t.setDaemon(true);
        t.start();
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

    private static int safeIntExtra(Intent intent, String key) {
        try {
            return intent.getIntExtra(key, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String safeBoolExtra(Intent intent, String key) {
        try {
            return String.valueOf(intent.getBooleanExtra(key, false));
        } catch (Throwable t) {
            return "?";
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

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + " " + msg);
    }
}
