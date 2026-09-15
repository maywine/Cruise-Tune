package com.cruisetune.windowprobe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;

/** Isolated, permission-free measurements. No OEM height guesses or system setting writes. */
@SuppressWarnings("deprecation")
public final class ProbeActivity extends Activity {
    private LinearLayout root;
    private TextView headline, details;
    private WindowInsets latest;
    private String report = "等待窗口完成布局";
    private boolean systemFit;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        systemFit = state != null && state.getBoolean("systemFit");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        applyWindowMode();
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff101c22);
        root.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView title = label("车机窗口诊断", 24);
        root.addView(title);
        LinearLayout actions = new LinearLayout(this);
        Button copy = button("复制测量结果");
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("窗口诊断", report));
            Toast.makeText(this, "已复制窗口读数", Toast.LENGTH_SHORT).show();
        });
        Button mode = button(modeLabel());
        mode.setOnClickListener(v -> {
            systemFit = !systemFit;
            // Recreate with the requested policy before the content is attached.
            // Some OEM DecorViews consume insets instead of redispatching to children.
            recreate();
        });
        actions.addView(copy, new LinearLayout.LayoutParams(0, dp(56), 1));
        actions.addView(mode, new LinearLayout.LayoutParams(0, dp(56), 1));
        root.addView(actions);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        headline = label("等待系统边距", 24);
        headline.setTextColor(0xff8ed6d0);
        content.addView(headline);
        content.addView(label("先拍下此页，再点“请求系统避让”对比底部按钮。\n读数 0 表示该接口未报告占用，不表示底栏不存在。", 17));
        details = label("", 17);
        content.addView(details);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button footer = button("底部测试按钮 · 应完整可见");
        footer.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff176878));
        footer.setOnClickListener(v -> Toast.makeText(this, "底部按钮可点击", Toast.LENGTH_SHORT).show());
        root.addView(footer, new LinearLayout.LayoutParams(-1, dp(64)));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            latest = insets;
            Rect safe = currentInsets(insets);
            // With system fitting enabled, the window manager owns bar avoidance.
            // Otherwise this mirrors the player's visible system bar + cutout padding.
            if (systemFit) root.setPadding(dp(16), dp(8), dp(16), dp(8));
            else root.setPadding(dp(16) + safe.left, dp(8) + safe.top,
                dp(16) + safe.right, dp(8) + safe.bottom);
            root.post(this::refresh);
            return insets;
        });
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::refresh);
        setContentView(root);
        root.requestApplyInsets();
    }

    private String modeLabel() { return systemFit ? "返回播放器布局" : "请求系统避让"; }
    private void applyWindowMode() {
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(systemFit);
        else getWindow().getDecorView().setSystemUiVisibility(systemFit ? View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            : View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("systemFit", systemFit);
        super.onSaveInstanceState(state);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && root != null) root.requestApplyInsets();
    }
    private Rect currentInsets(WindowInsets i) {
        Rect r;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets s = i.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            r = new Rect(s.left, s.top, s.right, s.bottom);
        } else {
            r = new Rect(i.getSystemWindowInsetLeft(), i.getSystemWindowInsetTop(),
                i.getSystemWindowInsetRight(), i.getSystemWindowInsetBottom());
            if (Build.VERSION.SDK_INT >= 28 && i.getDisplayCutout() != null) {
                android.view.DisplayCutout c = i.getDisplayCutout();
                r.left = Math.max(r.left, c.getSafeInsetLeft()); r.top = Math.max(r.top, c.getSafeInsetTop());
                r.right = Math.max(r.right, c.getSafeInsetRight()); r.bottom = Math.max(r.bottom, c.getSafeInsetBottom());
            }
        }
        return r;
    }
    private void refresh() {
        // Read the window's original insets even if DecorView consumed them while fitting.
        WindowInsets original = root.getRootWindowInsets();
        if (original != null) latest = original;
        if (latest == null || root.getWidth() == 0 || root.getHeight() == 0) return;
        Rect current = currentInsets(latest);
        Rect stable;
        String visibility = "";
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets s = latest.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            stable = new Rect(s.left, s.top, s.right, s.bottom);
            visibility = "\n导航栏可见标记: " + latest.isVisible(WindowInsets.Type.navigationBars())
                + "\n导航栏当前底边: " + pixels(latest.getInsets(WindowInsets.Type.navigationBars()).bottom)
                + "\nIME 可见标记: " + latest.isVisible(WindowInsets.Type.ime());
        } else stable = new Rect(latest.getStableInsetLeft(), latest.getStableInsetTop(),
            latest.getStableInsetRight(), latest.getStableInsetBottom());
        Rect frame = new Rect();
        root.getWindowVisibleDisplayFrame(frame);
        int[] origin = new int[2]; root.getLocationOnScreen(origin);
        int visibleGap = Math.max(0, origin[1] + root.getHeight() - frame.bottom);
        Point physical = new Point(); getWindowManager().getDefaultDisplay().getRealSize(physical);
        String summary = "当前底边: " + pixels(current.bottom) + "\n稳定底边: " + pixels(stable.bottom)
            + "\n可见区域底部差值: " + pixels(visibleGap);
        String text = "Cruise Tune Window Probe 1.0\n模式: " + (systemFit ? "请求系统避让" : "播放器布局（自行应用当前边距）")
            + "\nAndroid " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT + " / target 35"
            + "\n密度: " + getResources().getDisplayMetrics().densityDpi + " dpi"
            + "\n字体: " + getResources().getConfiguration().fontScale
            + "\n" + summary
            + "\n边距顺序：左、上、右、下（px）"
            + "\n当前系统栏＋刘海: " + edges(current)
            + "\n稳定系统栏" + (Build.VERSION.SDK_INT >= 30 ? "＋刘海" : "") + ": " + edges(stable)
            + visibility
            + "\n根布局: " + root.getWidth() + " × " + root.getHeight() + " px"
            + "\n根布局屏幕原点: " + origin[0] + ", " + origin[1]
            + "\n根布局 padding: " + root.getPaddingLeft() + ", " + root.getPaddingTop() + ", " + root.getPaddingRight() + ", " + root.getPaddingBottom()
            + "\n系统可见矩形: " + frame.toShortString()
            + "\n显示总尺寸: " + physical.x + " × " + physical.y + " px"
            + "\n系统导航栏资源值（仅供比对）: " + resourceHeight("navigation_bar_height")
            + "\n横屏导航栏资源值（仅供比对）: " + resourceHeight("navigation_bar_height_landscape")
            + (Build.VERSION.SDK_INT >= 30 ? "\n当前窗口: " + getWindowManager().getCurrentWindowMetrics().getBounds().toShortString() : "")
            + "\n备注：窗口可见区域不是对遮挡物的图像检测；资源值也不保证等于车机底栏高度。Android 15 起，target 35 的系统避让请求可能受强制边到边布局影响。";
        if (!text.equals(report)) {
            report = text;
            headline.setText(summary);
            details.setText(text);
            // These diagnostics contain only window geometry and public OS version fields.
            Log.i("CruiseWindowProbe", text);
        }
    }
    private String resourceHeight(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id == 0 ? "未提供" : pixels(getResources().getDimensionPixelSize(id));
    }
    private String edges(Rect r) { return r.left + ", " + r.top + ", " + r.right + ", " + r.bottom; }
    private String pixels(int px) { return String.format(Locale.US, "%d px / %.1f dp", px, px / getResources().getDisplayMetrics().density); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(0xfff4f5f1);
        v.setPadding(0, dp(6), 0, dp(6)); return v;
    }
    private Button button(String text) {
        Button b = new Button(this); b.setText(text); b.setTextSize(18); b.setAllCaps(false);
        b.setTextColor(Color.WHITE); b.setGravity(Gravity.CENTER); return b;
    }
}
