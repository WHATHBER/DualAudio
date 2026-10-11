package com.dualaudio;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Material 3 深色主题（Baseline token，单色强调）
 */
public class MainActivity extends Activity {

    private static final String VERSION = "2.2";

    private static final String DEX_DEST = "/data/local/tmp/MultiAudio.dex";
    private static final String AP = "ANDROID_ROOT=/system ANDROID_DATA=/data "
            + apProcess() + " -Djava.class.path=" + DEX_DEST
            + " /system/bin --nice-name=multiaudio MultiAudio ";

    /* ---- Material 3 深色主题 Color Tokens ---- */
    private static final int SURFACE      = 0xFF141218;  // 页面背景
    private static final int CONTAINER    = 0xFF211F26;  // 卡片
    private static final int CONTAINER_2  = 0xFF2B2930;  // 抬高层
    private static final int ON_SURFACE   = 0xFFE6E0E9;  // 主文字
    private static final int ON_VARIANT   = 0xFFCAC4D0;  // 次要文字
    private static final int OUTLINE_VAR  = 0xFF49454F;  // 描边 / 未选中轨道
    private static final int PRIMARY      = 0xFFFFFFFF;  // 强调（单色：白）
    private static final int ON_PRIMARY   = 0xFF1C1B1F;  // 强调上的文字（深）

    private static final int R_CARD = 16;
    private static final int R_PILL = 100;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private MiniSwitch swDual;
    private SeekBar sbUsb, sbBt;
    private TextView tvUsb, tvBt, tvLog, devState, btTitle;
    private LinearLayout cardUsb, cardBt, cardRedo;
    private boolean suppress = false;
    private volatile boolean busy = false;

    private static String apProcess() {
        if (new File("/system/bin/app_process64").exists()) return "/system/bin/app_process64";
        return "/system/bin/app_process";
    }

    private static String suBin() {
        if (new File("/system/bin/su").exists()) return "/system/bin/su";
        if (new File("/system/xbin/su").exists()) return "/system/xbin/su";
        return "su";
    }

    /* ================= 生命周期 ================= */
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        } catch (Throwable ignore) {
        }
        try {
            getWindow().setStatusBarColor(SURFACE);
            getWindow().setNavigationBarColor(SURFACE);
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController ic = getWindow().getInsetsController();
                if (ic != null) {
                    ic.setSystemBarsAppearance(0,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
            }
        } catch (Throwable ignore) {
        }
        try {
            setContentView(buildUi());
        } catch (Throwable t) {
            setContentView(errView(t));
            return;
        }
        // 隐藏系统标题栏（ActionBar）
        try {
            if (getActionBar() != null) getActionBar().hide();
        } catch (Throwable ignore) {
        }
        pool.execute(new Runnable() {
            public void run() {
                deployDex();
                ui.post(new Runnable() {
                    public void run() {
                        refresh();
                    }
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tvLog != null) refresh();
    }

    /* ================= UI ================= */
    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(SURFACE);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), statusBarH() + dp(28), dp(20), dp(32));
        scroll.addView(root);

        /* 标题（Headline Medium） */
        TextView title = tv("双路音频", 28, ON_SURFACE);
        title.setTypeface(med());
        root.addView(title);

        TextView sub = tv("蓝牙耳机与 USB 声卡同时输出", 14, ON_VARIANT);
        LinearLayout.LayoutParams sp = lp();
        sp.topMargin = dp(6);
        root.addView(sub, sp);

        /* --- 卡片 1：总开关 --- */
        LinearLayout c1 = card();
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout t1 = new LinearLayout(this);
        t1.setOrientation(LinearLayout.VERTICAL);
        TextView h1 = tv("双路同时输出", 16, ON_SURFACE);
        h1.setTypeface(med());
        devState = tv("检测设备中…", 12, ON_VARIANT);
        LinearLayout.LayoutParams d1p = lp();
        d1p.topMargin = dp(4);
        t1.addView(h1);
        t1.addView(devState, d1p);
        row1.addView(t1, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        swDual = new MiniSwitch(this);
        swDual.setOnChange(new Runnable() {
            public void run() {
                onDualToggle(swDual.isChecked());
            }
        });
        row1.addView(swDual);
        c1.addView(row1);
        root.addView(c1, cardLp(24));

        /* --- 卡片 2：USB 音量（未插入时折叠） --- */
        LinearLayout c2 = card();
        cardUsb = c2;
        c2.addView(tv("有线 / USB 声卡 音量", 12, ON_VARIANT));
        tvUsb = tv("--", 32, ON_SURFACE);
        tvUsb.setTypeface(med());
        LinearLayout.LayoutParams uvp = lp();
        uvp.topMargin = dp(6);
        c2.addView(tvUsb, uvp);
        sbUsb = new SeekBar(this);
        sbUsb.setMax(100);
        sbUsb.setProgressDrawable(seekTrack());
        sbUsb.setThumb(seekThumb());
        sbUsb.setSplitTrack(false);
        LinearLayout.LayoutParams sbp = lp();
        sbp.topMargin = dp(12);
        sbUsb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean u) {
                tvUsb.setText(String.valueOf(v));
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
                final int v = s.getProgress();
                execCmd("tinymix -D 1 'PCM Playback Volume' " + v + " " + v,
                        "USB 硬件音量 -> " + v);
            }
        });
        c2.addView(sbUsb, sbp);
        root.addView(c2, cardLp(12));

        /* --- 卡片 3：蓝牙音量（未连接时折叠，标题带设备名） --- */
        LinearLayout c3 = card();
        cardBt = c3;
        btTitle = tv("蓝牙耳机 音量", 12, ON_VARIANT);
        c3.addView(btTitle);
        tvBt = tv("--", 32, ON_SURFACE);
        tvBt.setTypeface(med());
        LinearLayout.LayoutParams bvp = lp();
        bvp.topMargin = dp(6);
        c3.addView(tvBt, bvp);
        sbBt = new SeekBar(this);
        sbBt.setMax(100);
        sbBt.setProgressDrawable(seekTrack());
        sbBt.setThumb(seekThumb());
        sbBt.setSplitTrack(false);
        LinearLayout.LayoutParams bbp = lp();
        bbp.topMargin = dp(12);
        sbBt.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean u) {
                tvBt.setText(String.valueOf(v));
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
                final int v = s.getProgress();
                execCmd(AP + "vol bt " + v, "蓝牙音量 -> " + v);
            }
        });
        c3.addView(sbBt, bbp);
        root.addView(c3, cardLp(12));

        /* --- 重新应用（两个设备都在线时才出现） --- */
        LinearLayout redoBox = new LinearLayout(this);
        redoBox.setOrientation(LinearLayout.VERTICAL);
        cardRedo = redoBox;

        LinearLayout bRedo = iconButton("重新应用", true, false);
        bRedo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int u = sbUsb.getProgress();
                int bt = sbBt.getProgress();
                execCmd(AP + "auto; " + AP + "vol bt " + bt + "; "
                                + "tinymix -D 1 'PCM Playback Volume' " + u + " " + u,
                        "重新应用：重建双路 + 套用音量");
            }
        });
        redoBox.addView(bRedo);

        TextView nA = tv("蓝牙断开重连后点这里，重新绑定双设备并套用上面的音量",
                11, ON_VARIANT);
        nA.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nAp = lp();
        nAp.topMargin = dp(10);
        redoBox.addView(nA, nAp);

        root.addView(redoBox, cardLp(24));

        /* --- 日志（标题行带复制按钮） --- */
        LinearLayout logHead = new LinearLayout(this);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lhp = lp();
        lhp.topMargin = dp(32);
        logHead.setLayoutParams(lhp);

        TextView lh = tv("运行日志", 14, ON_VARIANT);
        lh.setTypeface(med());
        logHead.addView(lh, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnCopy = tv("复制", 13, PRIMARY);
        btnCopy.setTypeface(med());
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(CONTAINER_2);
        cbg.setCornerRadius(dp(R_PILL));
        btnCopy.setBackground(ripple(cbg, dp(R_PILL)));
        btnCopy.setPadding(dp(14), dp(7), dp(14), dp(7));
        btnCopy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                copyLog();
            }
        });
        logHead.addView(btnCopy);
        root.addView(logHead);

        LinearLayout c4 = card();
        LinearLayout.LayoutParams c4p = cardLp(10);
        ScrollView ls = new ScrollView(this);
        ls.setVerticalScrollBarEnabled(false);
        tvLog = tv("正在初始化…", 11, ON_VARIANT);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setLineSpacing(dp(4), 1f);
        ls.addView(tvLog);
        LinearLayout.LayoutParams lsp = lp();
        lsp.height = dp(150);
        c4.addView(ls, lsp);
        root.addView(c4, c4p);

        /* --- 关于 --- */
        LinearLayout c5 = card();
        TextView at = tv("关于", 14, ON_SURFACE);
        at.setTypeface(med());
        c5.addView(at);
        TextView a1 = tv("双路音频  v" + VERSION, 12, ON_VARIANT);
        LinearLayout.LayoutParams a1p = lp();
        a1p.topMargin = dp(8);
        c5.addView(a1, a1p);
        TextView a2 = tv("作者  GitHub@WHATHBER", 12, ON_VARIANT);
        LinearLayout.LayoutParams a2p = lp();
        a2p.topMargin = dp(4);
        c5.addView(a2, a2p);
        root.addView(c5, cardLp(16));

        return scroll;
    }

    /* ---------- 组件工厂 ---------- */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(20), dp(18), dp(20), dp(18));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CONTAINER);
        g.setCornerRadius(dp(R_CARD));
        c.setBackground(g);
        return c;
    }

    private LinearLayout.LayoutParams cardLp(int topDp) {
        LinearLayout.LayoutParams p = lp();
        p.topMargin = dp(topDp);
        return p;
    }

    /** 带图标的 M3 按钮 */
    private LinearLayout iconButton(String text, boolean filled, boolean ccw) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(R_PILL));
        int fg;
        if (filled) {
            g.setColor(PRIMARY);
            fg = ON_PRIMARY;
        } else {
            g.setColor(0x00000000);
            g.setStroke(dp(1), OUTLINE_VAR);
            fg = PRIMARY;
        }
        b.setBackground(ripple(g, dp(R_PILL)));

        b.addView(new ArrowIcon(this, ccw, fg), new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView t = tv(text, 14, fg);
        t.setTypeface(med());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = dp(7);
        b.addView(t, tp);
        return b;
    }

    private Drawable ripple(Drawable base, int radiusPx) {
        try {
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(0xFFFFFFFF);
            mask.setCornerRadius(radiusPx);
            return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, mask);
        } catch (Throwable t) {
            return base;
        }
    }

    private TextView tv(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private static Typeface med() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    private Drawable seekTrack() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(OUTLINE_VAR);
        bg.setCornerRadius(dp(4));
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(PRIMARY);
        fg.setCornerRadius(dp(4));
        LayerDrawable ld = new LayerDrawable(new Drawable[]{bg, fg});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        return ld;
    }

    private Drawable seekThumb() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(PRIMARY);
        g.setSize(dp(20), dp(20));
        return g;
    }

    private LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private int statusBarH() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return getResources().getDimensionPixelSize(id);
        } catch (Throwable ignore) {
        }
        return dp(28);
    }

    /* ===== M3 Switch：52×32 药丸轨道，圆点带缩放 + 颜色过渡 ===== */
    private class MiniSwitch extends View {
        private static final int OFF_TRACK = 0xFF36343B;
        private static final int OFF_LINE  = 0xFF49454F;
        private static final int OFF_KNOB  = 0xFF938F99;
        private static final int ON_TRACK  = 0xFFFFFFFF;
        private static final int ON_KNOB   = 0xFF1C1B1F;

        private boolean on = false;
        private float anim = 0f;           // 0 = 关, 1 = 开
        private Runnable change;
        private ValueAnimator va;
        private final GradientDrawable track = new GradientDrawable();
        private final GradientDrawable knob = new GradientDrawable();

        MiniSwitch(Context c) {
            super(c);
            track.setShape(GradientDrawable.RECTANGLE);
            knob.setShape(GradientDrawable.OVAL);
            setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    on = !on;
                    animTo(on ? 1f : 0f);
                    if (change != null) change.run();
                }
            });
        }

        void setOnChange(Runnable r) {
            change = r;
        }

        boolean isChecked() {
            return on;
        }

        void setChecked(boolean v) {
            if (on == v) return;
            on = v;
            animTo(v ? 1f : 0f);
        }

        private void animTo(float target) {
            if (va != null) va.cancel();
            va = ValueAnimator.ofFloat(anim, target);
            va.setDuration(170);
            va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(ValueAnimator a) {
                    anim = ((Float) a.getAnimatedValue()).floatValue();
                    invalidate();
                }
            });
            va.start();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(52), dp(32));
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            track.setColor(blend(OFF_TRACK, ON_TRACK, anim));
            track.setStroke(dp(1), blend(OFF_LINE, ON_TRACK, anim));
            track.setCornerRadius(h / 2f);
            track.setBounds(0, 0, w, h);
            track.draw(cv);

            float d = dp(16) + dp(8) * anim;        // 16dp -> 24dp
            float left = dp(8) + dp(16) * anim;     // 8dp  -> 24dp
            float cy = h / 2f;
            knob.setColor(blend(OFF_KNOB, ON_KNOB, anim));
            knob.setBounds((int) left, (int) (cy - d / 2),
                    (int) (left + d), (int) (cy + d / 2));
            knob.draw(cv);
        }
    }

    private static int blend(int c1, int c2, float t) {
        int a = (int) (((c1 >>> 24) & 0xFF) + (((c2 >>> 24) & 0xFF) - ((c1 >>> 24) & 0xFF)) * t);
        int r = (int) (((c1 >>> 16) & 0xFF) + (((c2 >>> 16) & 0xFF) - ((c1 >>> 16) & 0xFF)) * t);
        int g = (int) (((c1 >>> 8) & 0xFF) + (((c2 >>> 8) & 0xFF) - ((c1 >>> 8) & 0xFF)) * t);
        int b = (int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /* ===== 自绘圆形箭头图标（ccw=false 顺时针 / true 逆时针） ===== */
    private class ArrowIcon extends View {
        private final boolean ccw;
        private final int color;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        ArrowIcon(Context c, boolean ccw, int color) {
            super(c);
            this.ccw = ccw;
            this.color = color;
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(18), dp(18));
        }

        @Override
        protected void onDraw(Canvas cv) {
            float size = Math.min(getWidth(), getHeight());
            float swd = dp(2.0f);
            float r = size / 2f - swd - dp(1.0f);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);

            // 逆时针版 = 顺时针版水平镜像，保证两个图标一眼可辨
            if (ccw) {
                cv.save();
                cv.translate(getWidth(), 0);
                cv.scale(-1f, 1f);
            }

            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(swd);
            p.setStrokeCap(Paint.Cap.ROUND);

            float start = 34f;
            cv.drawArc(oval, start, 272f, false, p);   // 缺口约 88°

            // 箭头贴在弧的起点，指向顺时针切线方向
            double a = Math.toRadians(start);
            float px = cx + r * (float) Math.cos(a);
            float py = cy + r * (float) Math.sin(a);
            double td = a + Math.PI / 2;
            float dx = (float) Math.cos(td), dy = (float) Math.sin(td);
            float nx = (float) Math.cos(a), ny = (float) Math.sin(a);
            float s = dp(3.5f);

            Path path = new Path();
            path.moveTo(px + dx * s * 1.2f, py + dy * s * 1.2f);
            path.lineTo(px + nx * s * 1.15f - dx * s * 0.5f,
                    py + ny * s * 1.15f - dy * s * 0.5f);
            path.lineTo(px - nx * s * 1.15f - dx * s * 0.5f,
                    py - ny * s * 1.15f - dy * s * 0.5f);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setStrokeCap(Paint.Cap.BUTT);
            cv.drawPath(path, p);

            if (ccw) cv.restore();
        }
    }

    /* ================= 操作 ================= */
    private void copyLog() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            String txt = tvLog == null ? "" : tvLog.getText().toString();
            cm.setPrimaryClip(ClipData.newPlainText("DualAudio log", txt));
            Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    private View errView(Throwable t) {
        ScrollView sc = new ScrollView(this);
        TextView v = new TextView(this);
        v.setTextColor(0xFFFFB4AB);
        v.setTextSize(12);
        v.setPadding(dp(20), dp(20), dp(20), dp(20));
        StringBuilder sb = new StringBuilder("启动失败：\n");
        sb.append(t).append("\n\n");
        for (StackTraceElement e : t.getStackTrace()) sb.append("  at ").append(e).append('\n');
        Throwable c = t.getCause();
        while (c != null) {
            sb.append("\nCaused by: ").append(c).append('\n');
            for (StackTraceElement e : c.getStackTrace()) sb.append("  at ").append(e).append('\n');
            c = c.getCause();
        }
        v.setText(sb.toString());
        sc.addView(v);
        return sc;
    }

    /* ================= 逻辑 ================= */
    private String sh(String cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{suBin(), "-c", cmd});
            BufferedReader r1 = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l;
            while ((l = r1.readLine()) != null) sb.append(l).append('\n');
            r1.close();
            BufferedReader r2 = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            while ((l = r2.readLine()) != null) sb.append(l).append('\n');
            r2.close();
            p.waitFor();
        } catch (Throwable t) {
            sb.append("[ERR] ").append(t).append('\n');
        }
        return sb.toString();
    }

    private void execCmd(final String cmd, final String tag) {
        log("> " + tag);
        pool.execute(new Runnable() {
            public void run() {
                final String out = sh(cmd);
                ui.post(new Runnable() {
                    public void run() {
                        if (out.trim().length() > 0) log(out.trim());
                        refresh();
                    }
                });
            }
        });
    }

    private void deployDex() {
        try {
            String id = sh("id");
            if (id.contains("uid=0")) {
                log("[ok] root 权限正常");
            } else {
                log("[!] 没拿到 root：请在 KernelSU 里给「双路音频」授权后重开");
                return;
            }
            File f = new File(getFilesDir(), "MultiAudio.dex");
            if (!f.exists() || f.length() == 0) {
                InputStream in = getAssets().open("MultiAudio.dex");
                FileOutputStream out = new FileOutputStream(f);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
            }
            String r = sh("mkdir -p /data/local/tmp && cp '" + f.getAbsolutePath() + "' "
                    + DEX_DEST + " && chmod 644 " + DEX_DEST);
            if (r.trim().length() > 0) log("[deploy] " + r.trim());
        } catch (Throwable t) {
            log("[deploy ERR] " + t);
        }
    }

    private void refresh() {
        pool.execute(new Runnable() {
            public void run() {
                final String out = sh(AP + "status");
                ui.post(new Runnable() {
                    public void run() {
                        applyStatus(out);
                    }
                });
            }
        });
    }

    /** 解析 status 输出：[dual, usb, bt, btDev, usbDev] */
    private static int[] parseStatus(String out) {
        int[] r = new int[]{0, -1, -1, 0, 0};
        for (String raw : out.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("dual=")) r[0] = pi(line.substring(5), 0);
            else if (line.startsWith("usbdev=")) r[4] = pi(line.substring(7), 0);
            else if (line.startsWith("usb=")) r[1] = pi(line.substring(4), -1);
            else if (line.startsWith("btdev=")) r[3] = pi(line.substring(6), 0);
            else if (line.startsWith("bt=")) r[2] = pi(line.substring(3), -1);
        }
        return r;
    }

    /**
     * 切换双路输出：先校验设备 → 执行 → 复核结果。
     * 处理期间开关锁定（变淡、不可点），避免连点堆积命令；
     * 失败则回滚到原状态，并用 Toast 只说明问题。
     */
    private void onDualToggle(final boolean want) {
        if (busy) return;
        busy = true;
        swDual.setEnabled(false);
        swDual.setAlpha(0.45f);
        log("> " + (want ? "开启双路输出（校验中）" : "关闭双路输出"));

        pool.execute(new Runnable() {
            public void run() {
                String err = null;
                boolean finalState;
                if (want) {
                    int[] s = parseStatus(sh(AP + "status"));
                    if (s[3] != 1) {
                        err = "蓝牙未连接";
                    } else if (s[4] != 1) {
                        err = "USB 声卡未插入";
                    } else {
                        sh(AP + "auto");
                        err = (parseStatus(sh(AP + "status"))[0] == 1)
                                ? null : "双设备绑定失败";
                    }
                    finalState = (err == null);
                } else {
                    sh(AP + "autoclear");
                    err = (parseStatus(sh(AP + "status"))[0] == 0)
                            ? null : "关闭双路输出失败";
                    finalState = (err != null);   // 关闭失败则回滚为「开」
                }
                final String ferr = err;
                final boolean fst = finalState;
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        swDual.setChecked(fst);
                        swDual.setEnabled(true);
                        swDual.setAlpha(1f);
                        if (ferr != null) {
                            Toast.makeText(MainActivity.this, ferr, Toast.LENGTH_SHORT).show();
                            log("[失败] " + ferr);
                        }
                        refresh();
                    }
                });
            }
        });
    }

    private void applyStatus(String out) {
        int[] s = parseStatus(out);
        int dual = s[0], usb = s[1], bt = s[2], btDev = s[3], usbDev = s[4];
        String btName = field(out, "btname");

        suppress = true;
        if (!busy) swDual.setChecked(dual == 1);   // 处理期间不要覆盖用户的意图

        // 有线声卡：读不到显示「—」，设备不在线则整张卡片折叠
        boolean usbOk = (usb >= 0 && usb <= 100);
        sbUsb.setEnabled(usbOk);
        if (usbOk) sbUsb.setProgress(usb);
        tvUsb.setText(usbOk ? String.valueOf(sbUsb.getProgress()) : "—");
        fold(cardUsb, usbDev == 1);

        // 蓝牙耳机：同样折叠，标题写明是哪副耳机
        boolean btOk = (bt >= 0 && bt <= 100);
        sbBt.setEnabled(btOk);
        if (btOk) sbBt.setProgress(bt);
        tvBt.setText(btOk ? String.valueOf(sbBt.getProgress()) : "—");
        btTitle.setText(btName.length() > 0 ? "蓝牙耳机 · " + btName : "蓝牙耳机 音量");
        fold(cardBt, btDev == 1);

        // 两个设备都齐了才需要「重新应用」
        fold(cardRedo, btDev == 1 && usbDev == 1);

        suppress = false;

        if (devState != null) {
            devState.setText((btDev == 1 ? "蓝牙已连接" : "蓝牙未连接")
                    + " · " + (usbDev == 1 ? "声卡已就绪" : "声卡未插入"));
        }
    }

    /** 取 status 输出里的某个 key=value */
    private static String field(String out, String key) {
        String pfx = key + "=";
        for (String raw : out.split("\n")) {
            String line = raw.trim();
            if (line.startsWith(pfx)) return line.substring(pfx.length());
        }
        return "";
    }

    /** 折叠 / 展开（带淡入淡出） */
    private void fold(final View v, final boolean show) {
        if (v == null) return;
        int cur = v.getVisibility();
        if (show) {
            if (cur == View.VISIBLE) return;
            v.setVisibility(View.VISIBLE);
            v.setAlpha(0f);
            v.animate().alpha(1f).setDuration(180).start();
        } else {
            if (cur != View.VISIBLE) return;
            v.animate().alpha(0f).setDuration(150).withEndAction(new Runnable() {
                public void run() {
                    v.setVisibility(View.GONE);
                }
            }).start();
        }
    }

    private static int pi(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private void log(final String s) {
        if (tvLog == null) return;
        ui.post(new Runnable() {
            public void run() {
                String cur = tvLog.getText().toString();
                if (cur.startsWith("正在初始化")) cur = "";
                String next = stamp() + "  " + s + "\n" + cur;
                String[] lines = next.split("\n");
                if (lines.length > 60) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 60; i++) sb.append(lines[i]).append('\n');
                    next = sb.toString();
                }
                tvLog.setText(next);
            }
        });
    }

    private static String stamp() {
        return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
    }
}
