package com.openminis.hook;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * 把配置暴露给宿主进程（小爱）里的 hook。
 *
 * <p>{@code call("get")} 返回 {@code Bundle{"json": "<RelayConfig>"}}；
 * {@code call("set", arg=<json>)} 写回去，方便 adb 联调：</p>
 *
 * <pre>
 * adb shell content call --uri content://com.openminis.hook.config --method get
 * adb shell content call --uri content://com.openminis.hook.config --method set \
 *     --arg '{"ubikitouch":true}'
 * </pre>
 *
 * <p>{@code set} 是"叠加"语义：只给一个开关时，其余字段保持当前值。</p>
 *
 * <p>只在本机、只影响本模块自己的开关，没有敏感数据。</p>
 */
public final class RelayConfigProvider extends ContentProvider {

    private static final String TAG = "MinisHookConfig";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        if (RelayConfig.METHOD_SET.equals(method)) {
            String json = arg;
            if (json == null && extras != null) {
                json = extras.getString(RelayConfig.BUNDLE_JSON);
            }
            if (json != null) {
                // 叠加在当前配置上，别让只写一个字段的 adb 调用把其它开关打回默认
                RelayConfig.save(getContext(),
                        RelayConfig.fromJson(json, RelayConfig.load(getContext())));
                Log.i(TAG, "config set: " + json);
            }
        }
        out.putString(RelayConfig.BUNDLE_JSON, RelayConfig.load(getContext()).toJson());
        return out;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
