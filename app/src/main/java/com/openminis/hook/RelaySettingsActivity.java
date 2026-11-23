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
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置界面：哪条渠道唤起的小爱要转发、小爱里的哪个入口要接管、投递时怎么表现。
 *
 * <p>模块 APK 没有 res/（一直是无资源构建），所以整页都在代码里画：一张带状态的总览卡 +
 * 分组卡片 + 开关行。配色走系统的动态色板（Monet / Material You，{@code android.R.color
 * .system_accent1_*} 等，API 31 起公开），关掉或系统不支持时退回手写的深浅两套。</p>
 *
 * <p>改动立刻写进 SharedPreferences，{@link RelayConfigProvider} 会把它吐给宿主进程里的 hook。</p>
 */
public final class RelaySettingsActivity extends Activity {

    /** Monet 的系统色板（system_accent1_* / system_neutral1_*）从 Android 12 起才公开。 */
    private static final int API_MONET = Build.VERSION_CODES.S;

    private RelayConfig cfg;
    /** 上一次和 prefs 对齐时的 JSON，用来发现"页面开着时被 adb / provider 改过配置"。 */
    private String syncedJson;
    private boolean monetActive;

    /* ── 调色板 ─────────────────────────────────────────────────── */

    private int colorBg;
    private int colorSurface;
    private int colorSurfaceVariant;
    private int colorOutline;
    private int colorDivider;
    private int colorText;
    private int colorSub;
    private int colorAccent;
    private int colorOnAccent;
    private int colorAccentContainer;
    private int colorOnAccentContainer;
    private int colorWarn;
    private int colorWarnContainer;
    private int colorTrackOff;
    private int colorThumbOff;
    private int colorRipple;

    /* ── 需要跟着开关刷新的视图 ─────────────────────────────────── */

    private GradientDrawable statusPillBg;
    private TextView statusLabel;
    private GradientDrawable statusDotBg;
    private TextView summaryLabel;
    private final List<View> relayDependents = new ArrayList<>();
    private TextView jsonLabel;
    private final List<Object[]> counters = new ArrayList<>();

    private interface Setter {
        void set(boolean value);
    }

    private interface Counter {
        String count();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = RelayConfig.load(this);
        syncedJson = cfg.toJson();
        applyPalette();
        applyWindowColors();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(colorBg);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(18), pad, dp(22));
        // 横屏 / 平板上一行拉到 1000dp 会很难读，先铺满、拿到实际宽度后再限宽（见 capContentWidth）。
        FrameLayout.LayoutParams rootParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL);
        scroll.addView(root, rootParams);

        addHero(root);

        addSection(root, "转发渠道", "谁唤起的小爱会被转发给 Minis", () -> channelCount() + "/5");
        LinearLayout wake = addCard(root);
        relayDependents.add(wake);
        addSwitchRow(wake, "双击小白条", "桌面双击手势条", "double_click · gesture_line",
                cfg.doubleClick, value -> cfg.doubleClick = value);
        addSwitchRow(wake, "长按电源键", "电源键长按唤起小爱", "long_press_power_key",
                cfg.powerLongPress, value -> cfg.powerLongPress = value);
        addSwitchRow(wake, "长按小白条", "手势条长按唤醒", "long_press_fullscreen_gesture_line",
                cfg.gestureLongPress, value -> cfg.gestureLongPress = value);
        addSwitchRow(wake, "数字助手（ubikitouch）", "其它 App 用 ACTION_ASSIST 唤起", "eu.toneiv.ubktouch",
                cfg.ubikitouch, value -> cfg.ubikitouch = value);
        addSwitchRow(wake, "小爱识屏", "系统识屏入口唤起", "system_input_gesture",
                cfg.screenRecognition, "默认关", true, true, value -> cfg.screenRecognition = value);

        addSection(root, "「+」菜单入口", "唤醒小爱后，点这些入口进来的内容要不要转发", () -> entryCount() + "/6");
        LinearLayout entries = addCard(root);
        relayDependents.add(entries);
        addSwitchRow(entries, "相机", "拍照识图", "TakePhotoActivity",
                cfg.entryCamera, value -> cfg.entryCamera = value);
        addSwitchRow(entries, "相册", "从相册选图", "half_state_upload_photo",
                cfg.entryGallery, value -> cfg.entryGallery = value);
        addSwitchRow(entries, "文档", "选 PDF / 文档上传", null,
                cfg.entryDocument, value -> cfg.entryDocument = value);
        addSwitchRow(entries, "识屏", "小爱识屏 / 圈选识别的截图", null,
                cfg.entryScreenRecognition, value -> cfg.entryScreenRecognition = value);
        addSwitchRow(entries, "屏幕翻译", "全屏翻译模式，转发会打断翻译流程", "checkTranslationIsOpen()",
                cfg.entryTranslate, "默认关", true, true, value -> cfg.entryTranslate = value);
        addSwitchRow(entries, "小爱陪伴", "视频陪伴模式，转发会打断陪伴流程", "isStandByMode()",
                cfg.entryCompanion, "默认关", true, true, value -> cfg.entryCompanion = value);

        addSection(root, "投递行为", "送到 Minis 的方式", () -> behaviourCount() + "/4");
        LinearLayout behaviour = addCard(root);
        relayDependents.add(behaviour);
        addSwitchRow(behaviour, "转发图片 / 文件", "相册图、拍照、截图、文档一起传", "EXTRA_STREAM · FileProvider",
                cfg.media, value -> cfg.media = value);
        addSwitchRow(behaviour, "转发后收掉小爱", "投递完成后关掉小爱浮窗，避免和 Minis 重叠",
                "stopService(VoiceService)",
                cfg.dismiss, value -> cfg.dismiss = value);
        addSwitchRow(behaviour, "转发小爱的答复", "连引擎返回的答复一起转（一般不用开，Minis 自己会答）", null,
                cfg.content, null, false, true, value -> cfg.content = value);
        addSwitchRow(behaviour, "转发卡片 / H5 指令", "卡片按钮会变成一条 query", "SpeechQueryService",
                cfg.speechQuery, "默认关", true, true, value -> cfg.speechQuery = value);

        addAppearanceSection(root);
        addAboutSection(root);
        addFooter(root);

        applyInsets(scroll);
        setContentView(scroll);
        scroll.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                capContentWidth(scroll, root);
            }
        });
        capContentWidth(scroll, root);
        // 必须等 decor view 建好再碰 insets controller：Android 16 上
        // PhoneWindow.getInsetsController() 会在 decor 为 null 时直接 NPE。
        applyBarsAppearance();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 页面停在后头时配置可能被 adb content call 改过，回来时对齐一次，别让界面和 prefs 打架。
        RelayConfig latest = RelayConfig.load(this);
        if (syncedJson != null && !syncedJson.equals(latest.toJson())) {
            cfg = latest;
            syncedJson = cfg.toJson();
            recreate();
        }
    }

    /* ── 页头：状态总览 ────────────────────────────────────────── */

    private void addHero(LinearLayout root) {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{colorAccentContainer, blend(colorAccentContainer, colorSurface, 0.55f)});
        bg.setCornerRadius(dp(28));
        hero.setBackground(bg);
        hero.setPadding(dp(20), dp(20), dp(20), dp(18));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(6);
        root.addView(hero, params);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        hero.addView(top);

        TextView brand = new TextView(this);
        brand.setText("MINIS · 小爱转发");
        brand.setTextSize(11);
        brand.setLetterSpacing(0.14f);
        brand.setTextColor(withAlpha(colorOnAccentContainer, 0.72f));
        top.addView(brand, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView version = new TextView(this);
        version.setText("v" + versionName());
        version.setTextSize(10.5f);
        version.setTextColor(colorOnAccentContainer);
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(dp(8));
        chip.setColor(withAlpha(colorOnAccentContainer, 0.10f));
        version.setBackground(chip);
        version.setPadding(dp(8), dp(3), dp(8), dp(3));
        top.addView(version);

        TextView title = new TextView(this);
        title.setText("转发设置");
        title.setTextSize(27);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(colorOnAccentContainer);
        title.setPadding(0, dp(12), 0, 0);
        hero.addView(title);

        TextView caption = new TextView(this);
        caption.setText("小爱 → Minis · 改动即时生效，无需重启小爱");
        caption.setTextSize(12.5f);
        caption.setTextColor(withAlpha(colorOnAccentContainer, 0.78f));
        caption.setPadding(0, dp(5), 0, 0);
        hero.addView(caption);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(16);
        hero.addView(statusRow, statusParams);

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(12), dp(7), dp(14), dp(7));
        statusPillBg = new GradientDrawable();
        statusPillBg.setCornerRadius(dp(16));
        pill.setBackground(statusPillBg);
        statusRow.addView(pill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View dot = new View(this);
        statusDotBg = new GradientDrawable();
        statusDotBg.setShape(GradientDrawable.OVAL);
        dot.setBackground(statusDotBg);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotParams.rightMargin = dp(7);
        pill.addView(dot, dotParams);

        statusLabel = new TextView(this);
        statusLabel.setTextSize(13);
        statusLabel.setTypeface(Typeface.DEFAULT_BOLD);
        pill.addView(statusLabel);

        View spacer = new View(this);
        statusRow.addView(spacer, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        PillSwitch master = newSwitch();
        master.setChecked(cfg.relayEnabled);
        master.setOnCheckedChangeListener(new PillSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChange(PillSwitch view, boolean isChecked) {
                cfg.relayEnabled = isChecked;
                persist();
                refresh();
            }
        });
        statusRow.addView(master);

        summaryLabel = new TextView(this);
        summaryLabel.setTextSize(11.5f);
        summaryLabel.setTextColor(withAlpha(colorOnAccentContainer, 0.7f));
        summaryLabel.setPadding(0, dp(10), 0, 0);
        hero.addView(summaryLabel);
    }

    /* ── 分组 / 卡片 / 开关行 ──────────────────────────────────── */

    private void addSection(LinearLayout root, String title, String subtitle, Counter counter) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), dp(22), dp(10), 0);

        View bar = new View(this);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setCornerRadius(dp(2));
        barBg.setColor(colorAccent);
        bar.setBackground(barBg);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dp(3), dp(12));
        barParams.rightMargin = dp(8);
        head.addView(bar, barParams);

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLetterSpacing(0.03f);
        tv.setTextColor(blend(colorText, colorBg, 0.12f));
        head.addView(tv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (counter != null) {
            TextView count = new TextView(this);
            count.setTextSize(11);
            count.setTextColor(colorSub);
            head.addView(count);
            counters.add(new Object[]{count, counter});
        }
        root.addView(head);

        TextView sub = new TextView(this);
        sub.setText(subtitle);
        sub.setTextSize(12);
        sub.setTextColor(colorSub);
        sub.setAlpha(0.8f);
        sub.setPadding(dp(11), dp(1), dp(10), dp(8));
        root.addView(sub);
    }

    private LinearLayout addCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colorSurface);
        bg.setCornerRadius(dp(22));
        if (isNight()) {
            bg.setStroke(Math.max(1, dp(0.6f)), colorOutline);
        }
        card.setBackground(bg);
        card.setClipToOutline(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(2);
        root.addView(card, params);
        return card;
    }

    private void addSwitchRow(LinearLayout card, String title, String desc, String tech,
                              boolean checked, Setter setter) {
        addSwitchRow(card, title, desc, tech, checked, null, false, true, setter);
    }

    /**
     * @param chip  标题后面的小标签（如「默认关」），null 不显示
     * @param risky true 时打开后副标题转警示色（接管小爱自己的功能，默认关）
     */
    private void addSwitchRow(LinearLayout card, String title, String desc, String tech,
                              boolean checked, String chip, boolean risky, boolean enabled,
                              final Setter setter) {
        divider(card);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(12), dp(16), dp(12));
        row.setForeground(ripple(0));
        row.setClickable(enabled);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        texts.addView(titleRow);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(16);
        tvTitle.setTextColor(colorText);
        titleRow.addView(tvTitle);

        TextView tag = null;
        GradientDrawable tagBg = null;
        if (chip != null) {
            tag = new TextView(this);
            tag.setText(chip);
            tag.setTextSize(10);
            tagBg = new GradientDrawable();
            tagBg.setCornerRadius(dp(7));
            tag.setBackground(tagBg);
            tag.setPadding(dp(6), dp(2), dp(6), dp(2));
            LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tagParams.leftMargin = dp(8);
            titleRow.addView(tag, tagParams);
        }

        TextView tvSub = null;
        if (!TextUtils.isEmpty(desc)) {
            tvSub = new TextView(this);
            tvSub.setText(desc);
            tvSub.setTextSize(12.5f);
            tvSub.setAlpha(0.92f);
            tvSub.setPadding(0, dp(3), dp(10), 0);
            texts.addView(tvSub);
        }

        if (!TextUtils.isEmpty(tech)) {
            TextView tvTech = new TextView(this);
            tvTech.setText(tech);
            tvTech.setTextSize(9.5f);
            tvTech.setTypeface(Typeface.MONOSPACE);
            tvTech.setTextColor(colorSub);
            tvTech.setAlpha(0.68f);
            tvTech.setPadding(0, dp(3), dp(10), 0);
            texts.addView(tvTech);
        }

        PillSwitch sw = newSwitch();
        sw.setContentDescription(title);
        sw.setChecked(checked);
        sw.setEnabled(enabled);

        final TextView tagView = tag;
        final GradientDrawable tagBackground = tagBg;
        final TextView subView = tvSub;
        final boolean warnWhenOn = risky;
        final Runnable restyle = new Runnable() {
            @Override
            public void run() {
                boolean on = sw.isChecked();
                if (tagView != null && tagBackground != null) {
                    // 默认关的项被打开后，徽标从"提醒"退成普通灰
                    tagView.setTextColor(on ? colorSub : colorWarn);
                    tagBackground.setColor(withAlpha(on ? colorOutline : colorWarnContainer, 0.85f));
                }
                if (subView != null) {
                    subView.setTextColor(warnWhenOn && on ? colorWarn : colorSub);
                }
            }
        };
        restyle.run();

        sw.setOnCheckedChangeListener(new PillSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChange(PillSwitch view, boolean isChecked) {
                setter.set(isChecked);
                persist();
                restyle.run();
                refresh();
            }
        });
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        switchParams.leftMargin = dp(10);
        row.addView(sw, switchParams);

        final PillSwitch target = sw;
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (target.isEnabled()) {
                    target.toggle();
                }
            }
        });

        card.addView(row);
    }

    private void divider(LinearLayout card) {
        if (card.getChildCount() == 0) {
            return;
        }
        View divider = new View(this);
        divider.setBackgroundColor(colorDivider);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
        params.leftMargin = dp(18);
        params.rightMargin = dp(18);
        card.addView(divider, params);
    }

    /* ── 外观：Monet 取色 ─────────────────────────────────────── */

    private void addAppearanceSection(LinearLayout root) {
        boolean supported = Build.VERSION.SDK_INT >= API_MONET;
        addSection(root, "外观", "配色来自系统壁纸的动态色板（Material You）", null);
        LinearLayout look = addCard(root);

        String tech = supported
                ? (monetActive ? "system_accent1_* · system_neutral1_*" : "当前使用内置深浅两套配色")
                : "需要 Android 12 及以上";
        addSwitchRow(look, "跟随系统取色（Monet）",
                supported ? "关掉后用内置的蓝灰配色" : "系统版本太低，只能用内置配色",
                tech, cfg.monet && supported, supported ? null : "不可用", false, supported,
                value -> {
                    // 监听器已经写过盘，这里只需要按新配色重建页面
                    cfg.monet = value;
                    recreate();
                });
    }

    /* ── 关于 / 配置内容 ──────────────────────────────────────── */

    private void addAboutSection(LinearLayout root) {
        addSection(root, "关于", "模块信息与当前配置快照", null);
        LinearLayout about = addCard(root);

        infoRow(about, "版本", "v" + versionName() + "  ·  code " + versionCode(), null);
        infoRow(about, "作用域", "com.miui.voiceassist", "LSPosed 里需要勾选小爱");
        // authority 太长，等宽字体一行放不下，主动在 "//" 后换行，别让系统在中间截断。
        infoRow(about, "配置出口", "content://\n" + RelayConfig.AUTHORITY, "hook 每次转发前读一次，1 s 缓存");

        LinearLayout jsonCard = new LinearLayout(this);
        jsonCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable jsonBg = new GradientDrawable();
        jsonBg.setColor(colorSurfaceVariant);
        jsonBg.setCornerRadius(dp(18));
        jsonCard.setBackground(jsonBg);
        jsonCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        jsonCard.setForeground(ripple(dp(18)));
        LinearLayout.LayoutParams jsonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        jsonParams.topMargin = dp(10);
        root.addView(jsonCard, jsonParams);

        TextView jsonHint = new TextView(this);
        jsonHint.setText("当前配置 · 点击复制");
        jsonHint.setTextSize(11);
        jsonHint.setLetterSpacing(0.04f);
        jsonHint.setTextColor(blend(colorText, colorSurfaceVariant, 0.35f));
        jsonCard.addView(jsonHint);

        TextView json = new TextView(this);
        jsonLabel = json;
        json.setText(cfg.toJson());
        json.setTextSize(10f);
        json.setTypeface(Typeface.MONOSPACE);
        json.setTextColor(blend(colorText, colorSurfaceVariant, 0.15f));
        json.setLineSpacing(dp(2), 1f);
        json.setPadding(0, dp(6), 0, 0);
        jsonCard.addView(json);
        jsonCard.setOnClickListener(v -> copy(cfg.toJson(), "配置 JSON 已复制"));
    }

    private void infoRow(LinearLayout card, String title, String value, String hint) {
        divider(card);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(18), dp(13), dp(18), dp(13));
        card.addView(row);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(13.5f);
        tvTitle.setTextColor(colorSub);
        row.addView(tvTitle);

        TextView tvValue = new TextView(this);
        tvValue.setText(value);
        tvValue.setTextSize(12f);
        tvValue.setTypeface(Typeface.MONOSPACE);
        tvValue.setTextColor(colorText);
        tvValue.setPadding(0, dp(3), 0, 0);
        row.addView(tvValue);

        if (!TextUtils.isEmpty(hint)) {
            TextView tvHint = new TextView(this);
            tvHint.setText(hint);
            tvHint.setTextSize(11);
            tvHint.setTextColor(colorSub);
            tvHint.setAlpha(0.75f);
            tvHint.setPadding(0, dp(3), 0, 0);
            row.addView(tvHint);
        }
    }

    /* ── 页脚 ─────────────────────────────────────────────────── */

    private void addFooter(LinearLayout root) {
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(20), 0, 0);

        Button reset = makeButton("恢复默认", false);
        reset.setOnClickListener(v -> {
            cfg = RelayConfig.defaults();
            RelayConfig.save(this, cfg);
            recreate();
            Toast.makeText(this, "已恢复默认", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        left.rightMargin = dp(5);
        buttons.addView(reset, left);

        Button copy = makeButton("复制 ADB 命令", true);
        copy.setOnClickListener(v -> {
            String cmd = "adb shell content call --uri content://" + RelayConfig.AUTHORITY
                    + " --method get\n"
                    + "adb shell content call --uri content://" + RelayConfig.AUTHORITY
                    + " --method set --arg '" + cfg.toJson() + "'";
            copy(cmd, "命令已复制");
        });
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        right.leftMargin = dp(5);
        buttons.addView(copy, right);
        root.addView(buttons);

        TextView foot = new TextView(this);
        foot.setText("自动保存 · 点上面的配置卡可整段复制，交给 adb 即可复现");
        foot.setTextSize(10.5f);
        foot.setTextColor(colorSub);
        foot.setAlpha(0.65f);
        foot.setPadding(dp(8), dp(12), dp(8), 0);
        root.addView(foot);
    }

    /** 两个底部按钮：一个描边、一个实心，别用系统那套灰底。 */
    private Button makeButton(String text, boolean filled) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(filled ? colorOnAccent : colorText);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        if (filled) {
            bg.setColor(colorAccent);
        } else {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(Math.max(1, dp(1)), colorOutline);
        }
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(dp(16));
        mask.setColor(Color.WHITE);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(colorRipple), bg, mask));
        button.setPadding(dp(8), dp(13), dp(8), dp(13));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        button.setElevation(0f);
        return button;
    }

    /* ── 状态刷新 ─────────────────────────────────────────────── */

    private void refresh() {
        boolean on = cfg.relayEnabled;

        if (statusPillBg != null) {
            statusPillBg.setColor(on ? colorAccent : withAlpha(colorOnAccentContainer, 0.10f));
        }
        if (statusDotBg != null) {
            statusDotBg.setColor(on ? colorOnAccent : colorWarn);
        }
        if (statusLabel != null) {
            statusLabel.setText(on ? "转发中" : "已暂停");
            statusLabel.setTextColor(on ? colorOnAccent : colorOnAccentContainer);
        }
        if (summaryLabel != null) {
            summaryLabel.setText("渠道 " + channelCount() + "/5 · 入口 " + entryCount() + "/6 · 附件 "
                    + (cfg.media ? "转发" : "不转"));
        }
        if (jsonLabel != null) {
            jsonLabel.setText(cfg.toJson());
        }
        for (Object[] pair : counters) {
            ((TextView) pair[0]).setText(((Counter) pair[1]).count());
        }
        for (View card : relayDependents) {
            card.setAlpha(on ? 1f : 0.42f);
            setEnabledDeep(card, on);
        }
    }

    private int channelCount() {
        return count(cfg.doubleClick, cfg.powerLongPress, cfg.gestureLongPress, cfg.ubikitouch,
                cfg.screenRecognition);
    }

    private int entryCount() {
        return count(cfg.entryCamera, cfg.entryGallery, cfg.entryDocument, cfg.entryScreenRecognition,
                cfg.entryTranslate, cfg.entryCompanion);
    }

    private int behaviourCount() {
        return count(cfg.media, cfg.dismiss, cfg.content, cfg.speechQuery);
    }

    private static int count(boolean... values) {
        int n = 0;
        for (boolean b : values) {
            if (b) {
                n++;
            }
        }
        return n;
    }

    private void setEnabledDeep(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setEnabledDeep(group.getChildAt(i), enabled);
            }
        }
    }

    /* ── 安全区 / 系统栏 ──────────────────────────────────────── */

    /**
     * Android 15+ 强制 edge-to-edge：安全区补在 ScrollView 上而不是内容里。
     *
     * <p>补在内容里的话，滚动时卡片会从状态栏后面穿过去，和状态栏图标叠在一起；
     * 补在 ScrollView 上再配合默认的 clipToPadding，内容到边缘就被裁掉。</p>
     */
    private void applyInsets(final ScrollView scroll) {
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
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
                v.setPadding(0, top, 0, bottom);
                return insets;
            }
        });
        scroll.requestApplyInsets();
    }

    /** 限宽看 ScrollView 的实际可用宽度：分屏 / 自由窗口下 display 宽并不等于窗口宽。 */
    private void capContentWidth(ScrollView scroll, View root) {
        int available = scroll.getWidth() - scroll.getPaddingLeft() - scroll.getPaddingRight();
        if (available <= 0) {
            return;
        }
        int target = Math.min(available, dp(600));
        ViewGroup.LayoutParams params = root.getLayoutParams();
        if (params != null && params.width != target) {
            params.width = target;
            root.setLayoutParams(params);
        }
    }

    /** 窗口底色 + 透明系统栏，避免启动瞬间露出系统默认的白/黑底。 */
    private void applyWindowColors() {
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(colorBg));
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
    }

    /** 按深浅模式切系统栏图标颜色，浅色背景上不能出现白色状态栏文字。 */
    private void applyBarsAppearance() {
        boolean lightIcons = !isNight();
        try {
            Window window = getWindow();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    int flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    controller.setSystemBarsAppearance(lightIcons ? flags : 0, flags);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                int flags = lightIcons
                        ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                        : 0;
                window.getDecorView().setSystemUiVisibility(flags);
            }
        } catch (Throwable ignored) {
            // 系统栏配色只是观感，个别 ROM 上拿不到 controller 就算了。
        }
    }

    /* ── 杂项 ─────────────────────────────────────────────────── */

    private void copy(String text, String toast) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("minis-relay", text));
            Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
        }
    }

    /** 统一按当前调色板造开关：开启 = 实心强调色 track + onAccent thumb。 */
    private PillSwitch newSwitch() {
        PillSwitch sw = new PillSwitch(this);
        sw.setColors(colorAccent, colorTrackOff, colorOnAccent, colorThumbOff);
        return sw;
    }

    private RippleDrawable ripple(float radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(dp(radiusDp));
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(colorRipple), null, mask);
    }

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 页头显示版本：main 是重定向线、这里是转发线，靠 versionName 后缀区分。 */
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private String versionCode() {
        try {
            return String.valueOf(getPackageManager().getPackageInfo(getPackageName(), 0).versionCode);
        } catch (Throwable t) {
            return "?";
        }
    }

    private void persist() {
        RelayConfig.save(this, cfg);
        syncedJson = cfg.toJson();
    }

    /* ── 调色板 ───────────────────────────────────────────────── */

    private void applyPalette() {
        boolean night = isNight();
        monetActive = cfg.monet && Build.VERSION.SDK_INT >= API_MONET;
        if (monetActive) {
            try {
                applyDynamicPalette(night);
            } catch (Throwable t) {
                // 个别 ROM 精简 / 换过 framework 的 system_* 色板，取不到就退回内置配色。
                // 设置页本身是"关掉转发"的唯一入口，不能在这里把 onCreate 弄崩。
                monetActive = false;
                applyBuiltinPalette(night);
            }
        } else {
            applyBuiltinPalette(night);
        }
        colorRipple = withAlpha(colorAccent, 0.22f);
    }

    /** 系统动态色板：surface / neutral 系列当底色，accent1 系列当强调色，跟随壁纸。 */
    private void applyDynamicPalette(boolean night) {
        int surface;
        if (night) {
            surface = tone(android.R.color.system_neutral1_900);
            colorBg = blend(surface, Color.BLACK, 0.45f);
            colorText = tone(android.R.color.system_neutral1_100);
            colorSub = tone(android.R.color.system_neutral1_300);
            colorAccent = tone(android.R.color.system_accent1_200);
            colorOnAccent = tone(android.R.color.system_accent1_900);
            colorAccentContainer = tone(android.R.color.system_accent1_700);
            colorOnAccentContainer = tone(android.R.color.system_accent1_100);
            colorSurfaceVariant = blend(surface, Color.WHITE, 0.07f);
            colorOutline = blend(surface, Color.WHITE, 0.13f);
            colorDivider = blend(surface, Color.WHITE, 0.08f);
            colorWarn = 0xFFFFCF8F;
            colorWarnContainer = blend(colorWarn, Color.BLACK, 0.80f);
            colorTrackOff = blend(colorText, surface, 0.80f);
            colorThumbOff = blend(colorText, surface, 0.45f);
        } else {
            surface = tone(android.R.color.system_neutral1_10);
            colorBg = tone(android.R.color.system_neutral1_50);
            colorText = tone(android.R.color.system_neutral1_900);
            colorSub = tone(android.R.color.system_neutral1_700);
            colorAccent = tone(android.R.color.system_accent1_600);
            colorOnAccent = Color.WHITE;
            colorAccentContainer = tone(android.R.color.system_accent1_100);
            colorOnAccentContainer = tone(android.R.color.system_accent1_900);
            colorSurfaceVariant = tone(android.R.color.system_neutral2_100);
            colorOutline = tone(android.R.color.system_neutral1_200);
            colorDivider = blend(colorOutline, surface, 0.45f);
            colorWarn = 0xFF8C5A00;
            colorWarnContainer = 0xFFFBE3BA;
            colorTrackOff = tone(android.R.color.system_neutral1_100);
            colorThumbOff = tone(android.R.color.system_neutral1_500);
        }
        colorSurface = surface;
    }

    /** 内置配色：Monet 不可用或被关掉时用，蓝灰 + 琥珀，和 v2.6 的观感一致。 */
    private void applyBuiltinPalette(boolean night) {
        if (night) {
            colorBg = 0xFF0D0F12;
            colorSurface = 0xFF1A1D22;
            colorSurfaceVariant = 0xFF22262C;
            colorOutline = 0xFF2C3138;
            colorDivider = 0xFF23272D;
            colorText = 0xFFEDEFF3;
            colorSub = 0xFF8C939D;
            colorAccent = 0xFF7FA8FF;
            colorOnAccent = 0xFF04122B;
            colorAccentContainer = 0xFF2F4468;
            colorOnAccentContainer = 0xFFDCE7FF;
            colorWarn = 0xFFE0A44A;
            colorWarnContainer = 0xFF3A2E1A;
            colorTrackOff = 0xFF31353C;
            colorThumbOff = 0xFF6B7280;
        } else {
            colorBg = 0xFFF2F3F5;
            colorSurface = 0xFFFFFFFF;
            colorSurfaceVariant = 0xFFEDEEF1;
            colorOutline = 0xFFDDE0E5;
            colorDivider = 0xFFEDEEF1;
            colorText = 0xFF12141A;
            colorSub = 0xFF7C828C;
            colorAccent = 0xFF2F6BFF;
            colorOnAccent = Color.WHITE;
            colorAccentContainer = 0xFFE3ECFF;
            colorOnAccentContainer = 0xFF10285A;
            colorWarn = 0xFFC77B18;
            colorWarnContainer = 0xFFFCEFD9;
            colorTrackOff = 0xFFD3D6DB;
            colorThumbOff = 0xFF8A9099;
        }
    }

    private int tone(int colorRes) {
        return getResources().getColor(colorRes, getTheme());
    }

    /** 按比例把 over 混到 base 上：t = 0 全是 base，t = 1 全是 over。 */
    private static int blend(int base, int over, float t) {
        float f = Math.max(0f, Math.min(1f, t));
        int a = Math.round(Color.alpha(base) + (Color.alpha(over) - Color.alpha(base)) * f);
        int r = Math.round(Color.red(base) + (Color.red(over) - Color.red(base)) * f);
        int g = Math.round(Color.green(base) + (Color.green(over) - Color.green(base)) * f);
        int b = Math.round(Color.blue(base) + (Color.blue(over) - Color.blue(base)) * f);
        return Color.argb(a, r, g, b);
    }

    private static int withAlpha(int color, float alpha) {
        return (color & 0x00FFFFFF) | (Math.round(255 * Math.max(0f, Math.min(1f, alpha))) << 24);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
