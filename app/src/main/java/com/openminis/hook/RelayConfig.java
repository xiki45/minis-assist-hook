package com.openminis.hook;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * 转发配置：模块 APK（带设置界面）写，小爱进程里的 hook（{@link AssistRelay}）读。
 *
 * <p>两个进程不同 uid，读不了对方的 SharedPreferences，所以走 {@link RelayConfigProvider}
 * 的 {@code ContentResolver.call("get")} 拿一份 JSON 快照；hook 侧带 1 s 缓存，
 * 改完开关下一次手势就生效，不用重启小爱。</p>
 *
 * <p>provider 不可用（模块没装、被冻结）时回落到这里的默认值。</p>
 */
public final class RelayConfig {

    public static final String AUTHORITY = "com.openminis.hook.config";
    public static final String PREFS = "relay_config";
    public static final String KEY_JSON = "json";
    public static final String METHOD_GET = "get";
    public static final String METHOD_SET = "set";
    public static final String BUNDLE_JSON = "json";

    /* 默认值 = v2.6 里写死的常量，行为完全一致。 */
    public boolean relayEnabled = true;
    public boolean doubleClick = true;
    public boolean powerLongPress = true;
    public boolean gestureLongPress = false;
    public boolean ubikitouch = false;
    public boolean screenRecognition = false;
    public boolean media = true;
    public boolean dismiss = true;
    public boolean content = true;
    public boolean speechQuery = false;

    public static RelayConfig defaults() {
        return new RelayConfig();
    }

    public static RelayConfig fromJson(String json) {
        RelayConfig c = defaults();
        if (json == null) {
            return c;
        }
        try {
            JSONObject o = new JSONObject(json);
            c.relayEnabled = o.optBoolean("relayEnabled", c.relayEnabled);
            c.doubleClick = o.optBoolean("doubleClick", c.doubleClick);
            c.powerLongPress = o.optBoolean("powerLongPress", c.powerLongPress);
            c.gestureLongPress = o.optBoolean("gestureLongPress", c.gestureLongPress);
            c.ubikitouch = o.optBoolean("ubikitouch", c.ubikitouch);
            c.screenRecognition = o.optBoolean("screenRecognition", c.screenRecognition);
            c.media = o.optBoolean("media", c.media);
            c.dismiss = o.optBoolean("dismiss", c.dismiss);
            c.content = o.optBoolean("content", c.content);
            c.speechQuery = o.optBoolean("speechQuery", c.speechQuery);
        } catch (Throwable ignored) {
            // 坏 JSON 就当没配过，用默认值。
        }
        return c;
    }

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("relayEnabled", relayEnabled);
            o.put("doubleClick", doubleClick);
            o.put("powerLongPress", powerLongPress);
            o.put("gestureLongPress", gestureLongPress);
            o.put("ubikitouch", ubikitouch);
            o.put("screenRecognition", screenRecognition);
            o.put("media", media);
            o.put("dismiss", dismiss);
            o.put("content", content);
            o.put("speechQuery", speechQuery);
        } catch (Throwable ignored) {
            // JSONObject.put 只会在 key 为 null 时抛，这里不可能。
        }
        return o.toString();
    }

    /* ── 模块 APK 侧（同一个进程，直接读 prefs） ─────────────────── */

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static RelayConfig load(Context ctx) {
        return fromJson(prefs(ctx).getString(KEY_JSON, null));
    }

    public static void save(Context ctx, RelayConfig cfg) {
        prefs(ctx).edit().putString(KEY_JSON, cfg.toJson()).commit();
    }
}
