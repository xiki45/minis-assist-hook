package com.openminis.hook;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 配置界面：哪条渠道唤起的小爱要转发给 Minis。
 *
 * <p>没有 XML 资源（模块 APK 一直是无 res 的），界面全部代码里搭。
 * 改动即时落到 SharedPreferences，{@link RelayConfigProvider} 会把它吐给
 * 小爱进程里的 hook，下一次手势就生效。</p>
 */
public final class RelaySettingsActivity extends Activity {

    private RelayConfig cfg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = RelayConfig.load(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addTitle(root, "转发渠道");
        addNote(root, "勾选的链路：小爱里发出的话（含图片/文件）会送到 Minis，随后小爱退出。");
        addSwitch(root, "双击小白条", "从桌面双击手势条唤起（double_click_fullscreen_gesture_line）",
                cfg.doubleClick, new Setter() {
                    @Override
                    public void set(boolean v) {
                        cfg.doubleClick = v;
                    }
                });
        addSwitch(root, "长按电源键", "电源键长按唤起（long_press_power_key / power_long_click_event）",
                cfg.powerLongPress, new Setter() {
                    @Override
                    public void set(boolean v) {
                        cfg.powerLongPress = v;
                    }
                });
        addSwitch(root, "长按小白条", "长按手势条唤起（long_press_fullscreen_gesture_line）",
                cfg.gestureLongPress, new Setter() {
                    @Override
                    public void set(boolean v) {
                        cfg.gestureLongPress = v;
                    }
                });
        addSwitch(root, "数字助手（ubikitouch）", "其它 App 用 ACTION_ASSIST 唤起，例如 eu.toneiv.ubktouch",
                cfg.ubikitouch, new Setter() {
                    @Override
                    public void set(boolean v) {
                        cfg.ubikitouch = v;
                    }
                });
        addSwitch(root, "小爱识屏", "系统识屏入口唤起（system_input_gesture / 两指长按 / 截屏）",
                cfg.screenRecognition, new Setter() {
                    @Override
                    public void set(boolean v) {
                        cfg.screenRecognition = v;
                    }
                });

        addTitle(root, "行为");
        addSwitch(root, "转发图片 / 文件", "把附件一起传给 Minis（相册、拍照、识屏截图、文档）",
                cfg.media, new Setter() {
                    @Override
                    public void set(boolean v) {
                        cfg.media = v;
                    }
                });
        addSwitch(root, "转发后收掉小爱", "投递完成后 stopService(VoiceService)，避免浮窗和 Minis 重叠",
                cfg.dismiss, new Setter() {
                    @Override
                    public void set(boolean v) {
                        cfg.dismiss = v;
                    }
                });
        addSwitch(root, "转发小爱的答复", "除了用户那句话，也把引擎返回的答复转过去（一般不用开，Minis 自己会答）",
                cfg.content, new Setter() {
                    @Override
                    public void set(boolean v) {
                        cfg.content = v;
                    }
                });
        addSwitch(root, "转发卡片 / H5 指令", "SpeechQueryService 通道，卡片按钮会变成一条 query，默认关",
                cfg.speechQuery, new Setter() {
                    @Override
                    public void set(boolean v) {
                        cfg.speechQuery = v;
                    }
                });

        addTitle(root, "总开关");
        addSwitch(root, "启用转发", "关掉后所有渠道都不再转发（小爱保持原样）",
                cfg.relayEnabled, new Setter() {
                    @Override
                    public void set(boolean v) {
                        cfg.relayEnabled = v;
                    }
                });

        Button reset = new Button(this);
        reset.setText("恢复默认");
        reset.setOnClickListener(v -> {
            cfg = RelayConfig.defaults();
            RelayConfig.save(this, cfg);
            recreate();
        });
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetParams.topMargin = dp(24);
        root.addView(reset, resetParams);

        addNote(root, "配置文件也可用 adb 改：\n"
                + "adb shell content call --uri content://" + RelayConfig.AUTHORITY
                + " --method get\n"
                + "adb shell content call --uri content://" + RelayConfig.AUTHORITY
                + " --method set --arg '{\"ubikitouch\":true}'");

        setContentView(scroll);
    }

    private void persist() {
        RelayConfig.save(this, cfg);
    }

    private interface Setter {
        void set(boolean value);
    }

    private void addTitle(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tv.setPadding(0, dp(20), 0, dp(4));
        root.addView(tv);
    }

    private void addNote(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setAlpha(0.7f);
        root.addView(tv);
    }

    private void addSwitch(LinearLayout root, String title, String subtitle, boolean checked,
                           final Setter setter) {
        Switch sw = new Switch(this);
        sw.setText(title);
        sw.setChecked(checked);
        sw.setPadding(0, dp(12), 0, 0);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setter.set(isChecked);
                persist();
            }
        });
        root.addView(sw);

        TextView sub = new TextView(this);
        sub.setText(subtitle);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setAlpha(0.6f);
        sub.setPadding(0, 0, 0, dp(8));
        root.addView(sub);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
