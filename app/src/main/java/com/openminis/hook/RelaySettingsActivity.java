package com.openminis.hook;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 配置界面：哪条渠道唤起的小爱要转发、小爱里的哪个入口要接管、投递时怎么表现。
 *
 * <p>模块 APK 没有 res/（一直是无资源构建），所以整页都在代码里画：卡片 + 分组 +
 * 开关行，按系统深色模式取色。改动立刻写进 SharedPreferences，
 * {@link RelayConfigProvider} 会把它吐给宿主进程里的 hook。</p>
 */
public final class RelaySettingsActivity extends Activity {

    private RelayConfig cfg;

    private int colorBg;
    private int colorCard;
    private int colorText;
    private int colorSub;
    private int colorDivider;
    private int colorWarn;
    private int colorAccent;
    private int colorAccentTrack;
    private int colorThumbOff;
    private int colorTrackOff;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = RelayConfig.load(this);
        applyPalette();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(colorBg);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(28), pad, dp(32));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addHeader(root);
        addMasterCard(root);

        addSection(root, "转发渠道", "谁唤起的小爱会被转发给 Minis");
        LinearLayout wake = addCard(root);
        addSwitchRow(wake, "双击小白条", "桌面双击手势条（double_click_fullscreen_gesture_line）", cfg.doubleClick,
                value -> cfg.doubleClick = value);
        addSwitchRow(wake, "长按电源键", "long_press_power_key / power_long_click_event", cfg.powerLongPress,
                value -> cfg.powerLongPress = value);
        addSwitchRow(wake, "长按小白条", "long_press_fullscreen_gesture_line", cfg.gestureLongPress,
                value -> cfg.gestureLongPress = value);
        addSwitchRow(wake, "数字助手（ubikitouch）", "其它 App 用 ACTION_ASSIST 唤起，如 eu.toneiv.ubktouch", cfg.ubikitouch,
                value -> cfg.ubikitouch = value);
        addSwitchRow(wake, "小爱识屏", "系统识屏入口唤起（system_input_gesture / 两指长按 / 截屏）", cfg.screenRecognition,
                value -> cfg.screenRecognition = value, true);

        addSection(root, "「+」菜单入口", "唤醒小爱后，点这些入口进来的内容要不要转发");
        LinearLayout entries = addCard(root);
        addSwitchRow(entries, "相机", "拍照识图（TakePhotoActivity）", cfg.entryCamera,
                value -> cfg.entryCamera = value);
        addSwitchRow(entries, "相册", "从相册选图（half_state_upload_photo）", cfg.entryGallery,
                value -> cfg.entryGallery = value);
        addSwitchRow(entries, "文档", "选 PDF / 文档上传", cfg.entryDocument,
                value -> cfg.entryDocument = value);
        addSwitchRow(entries, "识屏", "小爱识屏 / 圈选识别的截图", cfg.entryScreenRecognition,
                value -> cfg.entryScreenRecognition = value);
        addSwitchRow(entries, "屏幕翻译", "全屏翻译模式 —— 转发会打断翻译流程，默认不接管", cfg.entryTranslate,
                value -> cfg.entryTranslate = value, true);
        addSwitchRow(entries, "小爱陪伴", "视频陪伴模式 —— 默认不接管", cfg.entryCompanion,
                value -> cfg.entryCompanion = value, true);

        addSection(root, "投递行为", "送到 Minis 的方式");
        LinearLayout behaviour = addCard(root);
        addSwitchRow(behaviour, "转发图片 / 文件", "附件一起传过去（相册图、拍照、截图、文档）", cfg.media,
                value -> cfg.media = value);
        addSwitchRow(behaviour, "转发后收掉小爱", "投递完成 stopService(VoiceService)，避免浮窗和 Minis 重叠", cfg.dismiss,
                value -> cfg.dismiss = value);
        addSwitchRow(behaviour, "转发小爱的答复", "连引擎返回的答复一起转（一般不用开，Minis 自己会答）", cfg.content,
                value -> cfg.content = value, true);
        addSwitchRow(behaviour, "转发卡片 / H5 指令", "SpeechQueryService 通道，卡片按钮会变成一条 query，默认关", cfg.speechQuery,
                value -> cfg.speechQuery = value, true);

        addFooter(root);
        applyInsets(root, pad);
        setContentView(scroll);
    }

    /* ── 页面构件 ─────────────────────────────────────────────── */

    private void addHeader(LinearLayout root) {
        TextView title = new TextView(this);
        title.setText("Minis 转发设置");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(colorText);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("小爱 → Minis · 转发线 v" + versionName() + " · 改动即时生效，无需重启小爱");
        sub.setTextSize(13);
        sub.setTextColor(colorSub);
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub);
    }

    private void addMasterCard(LinearLayout root) {
        LinearLayout card = addCard(root);
        addSwitchRow(card, "启用转发", "关掉后所有渠道都不再转发，小爱保持原样", cfg.relayEnabled,
                value -> cfg.relayEnabled = value);
    }

    private void addSection(LinearLayout root, String title, String subtitle) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(colorSub);
        tv.setPadding(dp(6), dp(20), 0, dp(2));
        root.addView(tv);

        TextView sub = new TextView(this);
        sub.setText(subtitle);
        sub.setTextSize(12);
        sub.setTextColor(colorSub);
        sub.setAlpha(0.75f);
        sub.setPadding(dp(6), 0, 0, dp(8));
        root.addView(sub);
    }

    private LinearLayout addCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colorCard);
        bg.setCornerRadius(dp(20));
        card.setBackground(bg);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(4);
        root.addView(card, params);
        return card;
    }

    private void addSwitchRow(LinearLayout card, String title, String subtitle, boolean checked,
                              final Setter setter) {
        addSwitchRow(card, title, subtitle, checked, setter, false);
    }

    /**
     * @param risky true 时副标题用警示色（默认关闭的项，提醒"开了会接管小爱自己的功能"）
     */
    private void addSwitchRow(LinearLayout card, String title, String subtitle, boolean checked,
                              final Setter setter, boolean risky) {
        if (card.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(colorDivider);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
            dp.leftMargin = dp(16);
            dp.rightMargin = dp(16);
            card.addView(divider, dp);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(texts, textsParams);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(16);
        tvTitle.setTextColor(colorText);
        texts.addView(tvTitle);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tvSub = new TextView(this);
            tvSub.setText(subtitle);
            tvSub.setTextSize(12);
            tvSub.setTextColor(risky && !checked ? colorWarn : colorSub);
            tvSub.setAlpha(0.9f);
            tvSub.setPadding(0, dp(2), dp(12), 0);
            texts.addView(tvSub);
        }

        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setPadding(dp(8), 0, 0, 0);
        // 单色 tint 会让"开/关"长得一样，这里按 state_checked 分开上色
        sw.setThumbTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{colorAccent, colorThumbOff}));
        sw.setTrackTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{colorAccentTrack, colorTrackOff}));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setter.set(isChecked);
                persist();
            }
        });
        row.addView(sw);

        card.addView(row);
    }

    private void addFooter(LinearLayout root) {
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(24), 0, 0);

        Button reset = makeButton("恢复默认", false);
        reset.setOnClickListener(v -> {
            cfg = RelayConfig.defaults();
            RelayConfig.save(this, cfg);
            recreate();
            Toast.makeText(this, "已恢复默认", Toast.LENGTH_SHORT).show();
        });
        buttons.addView(reset, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button copy = makeButton("复制 ADB 命令", true);
        copy.setOnClickListener(v -> {
            String cmd = "adb shell content call --uri content://" + RelayConfig.AUTHORITY
                    + " --method get\n"
                    + "adb shell content call --uri content://" + RelayConfig.AUTHORITY
                    + " --method set --arg '" + cfg.toJson() + "'";
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("minis-relay", cmd));
                Toast.makeText(this, "命令已复制", Toast.LENGTH_SHORT).show();
            }
        });
        buttons.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(buttons);

        TextView foot = new TextView(this);
        foot.setText("配置文件：" + cfg.toJson());
        foot.setTextSize(10);
        foot.setTextColor(colorSub);
        foot.setAlpha(0.7f);
        foot.setPadding(dp(6), dp(12), dp(6), 0);
        root.addView(foot);
    }

    /** Android 15+ 强制 edge-to-edge，标题会钻到状态栏下面，这里补回安全区。 */
    private void applyInsets(final LinearLayout root, final int basePad) {
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top;
                int bottom;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    top = bars.top;
                    bottom = bars.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(basePad, dp(20) + top, basePad, dp(24) + bottom);
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    /* ── 杂项 ─────────────────────────────────────────────────── */

    /** 两个底部按钮：一个描边、一个实心，别用系统那套灰底。 */
    private Button makeButton(String text, boolean filled) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(filled ? Color.WHITE : colorText);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        if (filled) {
            bg.setColor(colorAccent);
        } else {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(Math.max(1, dp(1)), colorDivider);
        }
        button.setBackground(bg);
        button.setPadding(dp(8), dp(12), dp(8), dp(12));
        button.setStateListAnimator(null);
        button.setElevation(0f);
        return button;
    }

    /** 页头显示版本：main 是重定向线、这里是转发线，靠 versionName 后缀区分。 */
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private interface Setter {
        void set(boolean value);
    }

    private void persist() {
        RelayConfig.save(this, cfg);
    }

    private void applyPalette() {
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            colorBg = Color.parseColor("#0D0F12");
            colorCard = Color.parseColor("#1A1D22");
            colorText = Color.parseColor("#EDEFF3");
            colorSub = Color.parseColor("#8C939D");
            colorDivider = Color.parseColor("#262A31");
            colorWarn = Color.parseColor("#E0A44A");
            colorAccent = Color.parseColor("#7FA8FF");
            colorAccentTrack = Color.parseColor("#2F4468");
            colorThumbOff = Color.parseColor("#6B7280");
            colorTrackOff = Color.parseColor("#31353C");
        } else {
            colorBg = Color.parseColor("#F2F3F5");
            colorCard = Color.parseColor("#FFFFFF");
            colorText = Color.parseColor("#12141A");
            colorSub = Color.parseColor("#7C828C");
            colorDivider = Color.parseColor("#EDEEF1");
            colorWarn = Color.parseColor("#C77B18");
            colorAccent = Color.parseColor("#2F6BFF");
            colorAccentTrack = Color.parseColor("#CBD9FB");
            colorThumbOff = Color.parseColor("#FFFFFF");
            colorTrackOff = Color.parseColor("#D3D6DB");
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
