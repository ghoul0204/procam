package tw.wei.procam;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraMetadata;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.Size;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements CamController.Listener, SensorEventListener {

    static final int REQ_CAM = 1;
    static final int AMBER = 0xFFFFC107;
    static final String[] TAB_TITLES = {"ISO", "快門", "對焦", "白平衡", "曝光補償"};

    CamController cc;
    final Handler h = new Handler(Looper.getMainLooper());
    SharedPreferences sp;
    float dp;

    TextureView tv;
    OverlayView ov;
    FrameLayout pf;
    TextView info1, info2, tip, coach, valueTv, autoChip;
    TextView chipGrid, chipLevel, chipHist, chipZebra, chipPeak, chipRaw, chipTimer;
    SeekBar seek;
    LinearLayout zoomRow;
    ImageView thumb;
    View shutter;
    final TextView[] tabs = new TextView[5];
    final List<TextView> zoomBtns = new ArrayList<>();
    final List<Float> zoomVals = new ArrayList<>();

    int tab = 1;
    int[] isoList;
    long[] expList;
    int[] awbList;
    String[] awbNames;

    int gridMode, timer;
    boolean levelOn, histOn, zebraOn, peakOn, rawOn;
    boolean resumed, busy, updatingSeek;
    Uri lastUri;
    volatile int devOrientation = 0;
    OrientationEventListener oel;
    SensorManager sm;
    Sensor grav;
    MediaActionSound sound;
    CamController.Info info;
    int mean = 118;
    float clipHi, clipLo;
    ScaleGestureDetector sgd;
    GestureDetector gd;

    // ================= 生命週期 =================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sp = getSharedPreferences("procam", MODE_PRIVATE);
        gridMode = sp.getInt("grid", 1);
        levelOn = sp.getBoolean("level", true);
        histOn = sp.getBoolean("hist", true);
        zebraOn = sp.getBoolean("zebra", false);
        peakOn = sp.getBoolean("peak", false);
        rawOn = sp.getBoolean("raw", false);
        timer = sp.getInt("timer", 0);

        try {
            cc = new CamController(this, this);
        } catch (Exception e) {
            TextView t = new TextView(this);
            t.setText("無法使用相機：" + e.getMessage());
            t.setPadding(40, 120, 40, 40);
            setContentView(t);
            return;
        }
        buildLists();
        buildUi();
        applyToggles();
        selectTab(tab);

        sound = new MediaActionSound();
        sound.load(MediaActionSound.SHUTTER_CLICK);
        oel = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                devOrientation = ((o + 45) / 90 * 90) % 360;
            }
        };
        sm = getSystemService(SensorManager.class);
        grav = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (grav == null) grav = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cc == null) return;
        resumed = true;
        hideBars();
        oel.enable();
        if (grav != null) sm.registerListener(this, grav, SensorManager.SENSOR_DELAY_UI);
        tryOpen();
    }

    @Override
    protected void onPause() {
        if (cc != null) {
            resumed = false;
            h.removeCallbacksAndMessages(null);
            ov.setCountdown(0);
            busy = false;
            shutter.setAlpha(1f);
            oel.disable();
            sm.unregisterListener(this);
            cc.close();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (sound != null) sound.release();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    private void hideBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private boolean hasCamPerm() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void tryOpen() {
        if (cc == null || !resumed || !hasCamPerm() || !tv.isAvailable()) return;
        cc.zebra = zebraOn;
        cc.peaking = peakOn;
        cc.wantRaw = rawOn && cc.rawSupported;
        cc.open(tv.getSurfaceTexture());
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req != REQ_CAM) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) tryOpen();
        else toast("需要相機權限才能使用。請到 設定 → 應用程式 → 專業相機 → 權限 開啟相機。");
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_CAMERA) {
            if (e.getRepeatCount() == 0) shoot();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    // ================= 參數清單 =================

    private void buildLists() {
        int[] isoC = {50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600,
                2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600};
        List<Integer> il = new ArrayList<>();
        for (int v : isoC)
            if (v >= cc.isoRange.getLower() && v <= cc.isoRange.getUpper()) il.add(v);
        if (il.isEmpty()) il.add(cc.isoRange.getLower());
        isoList = new int[il.size()];
        for (int i = 0; i < isoList.length; i++) isoList[i] = il.get(i);

        double[] expC = {1 / 8000.0, 1 / 6400.0, 1 / 5000.0, 1 / 4000.0, 1 / 3200.0, 1 / 2500.0, 1 / 2000.0,
                1 / 1600.0, 1 / 1250.0, 1 / 1000.0, 1 / 800.0, 1 / 640.0, 1 / 500.0, 1 / 400.0, 1 / 320.0,
                1 / 250.0, 1 / 200.0, 1 / 160.0, 1 / 125.0, 1 / 100.0, 1 / 80.0, 1 / 60.0, 1 / 50.0, 1 / 40.0,
                1 / 30.0, 1 / 25.0, 1 / 20.0, 1 / 15.0, 1 / 13.0, 1 / 10.0, 1 / 8.0, 1 / 6.0, 1 / 5.0, 1 / 4.0,
                0.3, 0.4, 0.5, 0.6, 0.8, 1, 1.3, 1.6, 2, 2.5, 3.2, 4, 5, 6, 8, 10, 13, 15, 20, 25, 30};
        List<Long> el = new ArrayList<>();
        for (double s : expC) {
            long ns = Math.round(s * 1e9);
            if (ns >= cc.expRange.getLower() && ns <= cc.expRange.getUpper()) el.add(ns);
        }
        if (el.isEmpty()) el.add(cc.expRange.getLower());
        expList = new long[el.size()];
        for (int i = 0; i < expList.length; i++) expList[i] = el.get(i);

        int[] order = {CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT, CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT,
                CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
                CameraMetadata.CONTROL_AWB_MODE_TWILIGHT, CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
                CameraMetadata.CONTROL_AWB_MODE_SHADE};
        String[] names = {"白熾燈 2800K", "暖色螢光 3000K", "螢光燈 4000K", "日光 5500K", "黃昏", "陰天 6500K", "陰影 7500K"};
        List<Integer> al = new ArrayList<>();
        List<String> an = new ArrayList<>();
        for (int i = 0; i < order.length; i++) {
            for (int m : cc.awbModes)
                if (m == order[i]) {
                    al.add(order[i]);
                    an.add(names[i]);
                }
        }
        awbList = new int[al.size()];
        awbNames = new String[al.size()];
        for (int i = 0; i < awbList.length; i++) {
            awbList[i] = al.get(i);
            awbNames[i] = an.get(i);
        }
    }

    // ================= 畫面 =================

    private int dp(float v) {
        return Math.round(v * dp);
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView chip(String label, View.OnClickListener l) {
        TextView t = text(13, Color.WHITE);
        t.setText(label);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        t.setBackground(round(0xFF2C2C2C, 16));
        t.setOnClickListener(l);
        return t;
    }

    private void setChip(TextView t, boolean on) {
        if (Boolean.valueOf(on).equals(t.getTag())) return;
        t.setTag(on);
        t.setBackground(round(on ? AMBER : 0xFF2C2C2C, 16));
        t.setTextColor(on ? Color.BLACK : Color.WHITE);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        // --- 參數資訊列 ---
        LinearLayout infoBar = new LinearLayout(this);
        infoBar.setOrientation(LinearLayout.VERTICAL);
        infoBar.setPadding(dp(12), dp(6), dp(12), dp(4));
        info1 = text(16, Color.WHITE);
        info1.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        setText(info1, "ISO --   --   f/--");
        info2 = text(12, 0xFFB0B0B0);
        infoBar.addView(info1);
        infoBar.addView(info2);
        root.addView(infoBar);

        // --- 功能開關 ---
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(dp(8), dp(2), dp(8), dp(6));
        chipGrid = chip("構圖線", v -> {
            gridMode = (gridMode + 1) % 3;
            saveAndApply();
        });
        chipLevel = chip("水平儀", v -> {
            levelOn = !levelOn;
            saveAndApply();
        });
        chipHist = chip("直方圖", v -> {
            histOn = !histOn;
            saveAndApply();
        });
        chipZebra = chip("斑馬紋", v -> {
            zebraOn = !zebraOn;
            saveAndApply();
            if (zebraOn) ov.banner("紅色斜紋＝過曝");
        });
        chipPeak = chip("峰值對焦", v -> {
            peakOn = !peakOn;
            saveAndApply();
            if (peakOn) ov.banner("綠點＝對焦清楚的地方");
        });
        chipRaw = chip("RAW", v -> {
            if (!cc.rawSupported) {
                toast("這個鏡頭不支援 RAW");
                return;
            }
            rawOn = !rawOn;
            saveAndApply();
            cc.setRaw(rawOn);
        });
        chipTimer = chip("計時 關", v -> {
            timer = timer == 0 ? 3 : timer == 3 ? 10 : 0;
            saveAndApply();
        });
        TextView chipBook = chip("📖 攝影課本", v -> showBook());
        for (TextView t : new TextView[]{chipGrid, chipLevel, chipHist, chipZebra, chipPeak, chipRaw, chipTimer, chipBook}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            chips.addView(t, lp);
        }
        hs.addView(chips);
        root.addView(hs);

        // --- 預覽（4:3） ---
        int sw = getResources().getDisplayMetrics().widthPixels;
        pf = new FrameLayout(this);
        root.addView(pf, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, sw * 4 / 3));
        tv = new TextureView(this);
        pf.addView(tv, new FrameLayout.LayoutParams(-1, -1));
        ov = new OverlayView(this);
        pf.addView(ov, new FrameLayout.LayoutParams(-1, -1));

        coach = text(13, AMBER);
        coach.setPadding(dp(10), dp(4), dp(10), dp(4));
        coach.setBackground(round(0x99000000, 8));
        coach.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cl.bottomMargin = dp(64);
        cl.leftMargin = cl.rightMargin = dp(16);
        pf.addView(coach, cl);

        zoomRow = new LinearLayout(this);
        zoomRow.setGravity(Gravity.CENTER);
        zoomRow.setPadding(dp(4), dp(4), dp(4), dp(4));
        zoomRow.setBackground(round(0x66000000, 24));
        FrameLayout.LayoutParams zl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        zl.bottomMargin = dp(12);
        pf.addView(zoomRow, zl);
        buildZoomButtons();

        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int hh) {
                tryOpen();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int hh) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture s) {
            }
        });

        sgd = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector det) {
                cc.setZoom(cc.zoom * det.getScaleFactor());
                updateZoomUi();
                return true;
            }
        });
        gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                tapFocus(e.getX(), e.getY());
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                cc.resetFocus();
                ov.clearFocus();
                ov.banner("回到連續自動對焦");
                refreshTabs();
            }
        });
        pf.setOnTouchListener((v, e) -> {
            sgd.onTouchEvent(e);
            if (sgd.isInProgress() || e.getPointerCount() > 1) {
                MotionEvent c = MotionEvent.obtain(e);
                c.setAction(MotionEvent.ACTION_CANCEL);
                gd.onTouchEvent(c);
                c.recycle();
            } else {
                gd.onTouchEvent(e);
            }
            return true;
        });

        // --- 參數分頁 ---
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setPadding(dp(4), dp(6), dp(4), 0);
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            TextView t = text(12, Color.WHITE);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(4), 0, dp(4));
            t.setOnClickListener(v -> selectTab(idx));
            tabs[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.leftMargin = lp.rightMargin = dp(2);
            tabRow.addView(t, lp);
        }
        root.addView(tabRow);

        // --- 調整列 ---
        LinearLayout adj = new LinearLayout(this);
        adj.setGravity(Gravity.CENTER_VERTICAL);
        adj.setPadding(dp(10), dp(4), dp(10), 0);
        autoChip = chip("AUTO", v -> toggleAuto());
        adj.addView(autoChip);
        seek = new SeekBar(this);
        seek.setProgressTintList(ColorStateList.valueOf(AMBER));
        seek.setThumbTintList(ColorStateList.valueOf(AMBER));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!updatingSeek && fromUser) onSeek(p);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        adj.addView(seek, new LinearLayout.LayoutParams(0, dp(40), 1f));
        valueTv = text(15, Color.WHITE);
        valueTv.setTypeface(Typeface.DEFAULT_BOLD);
        valueTv.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        adj.addView(valueTv, new LinearLayout.LayoutParams(dp(96), -2));
        root.addView(adj);

        tip = text(12, 0xFFAAAAAA);
        tip.setPadding(dp(14), dp(2), dp(14), dp(2));
        tip.setLineSpacing(0, 1.1f);
        root.addView(tip);

        // --- 快門列 ---
        FrameLayout bottom = new FrameLayout(this);
        bottom.setMinimumHeight(dp(92));
        root.addView(bottom, new LinearLayout.LayoutParams(-1, 0, 1f));

        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(round(0xFF222222, 10));
        thumb.setClipToOutline(true);
        thumb.setOnClickListener(v -> openGallery());
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START | Gravity.CENTER_VERTICAL);
        tl.leftMargin = dp(28);
        bottom.addView(thumb, tl);

        shutter = new View(this);
        GradientDrawable sh = new GradientDrawable();
        sh.setShape(GradientDrawable.OVAL);
        sh.setColor(Color.WHITE);
        sh.setStroke(dp(5), 0xFF777777);
        shutter.setBackground(sh);
        shutter.setOnClickListener(v -> shoot());
        bottom.addView(shutter, new FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER));

        TextView reset = chip("全自動", v -> resetAll());
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        rl.rightMargin = dp(24);
        bottom.addView(reset, rl);

        setContentView(root);
    }

    private void buildZoomButtons() {
        float[] cand = {0.5f, 1f, 2f, 5f, 10f};
        float lo = cc.zoomRange.getLower(), hi = cc.zoomRange.getUpper();
        for (final float z : cand) {
            if (z < lo - 0.01f || z > hi + 0.01f) continue;
            TextView t = text(12, Color.WHITE);
            t.setGravity(Gravity.CENTER);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0xAA222222);
            t.setBackground(g);
            t.setOnClickListener(v -> {
                cc.setZoom(z);
                updateZoomUi();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
            lp.leftMargin = lp.rightMargin = dp(3);
            zoomRow.addView(t, lp);
            zoomBtns.add(t);
            zoomVals.add(z);
        }
        if (zoomBtns.isEmpty()) zoomRow.setVisibility(View.GONE);
        updateZoomUi();
    }

    private static String zoomLabel(float z) {
        if (Math.abs(z - Math.round(z)) < 0.05f) return String.valueOf(Math.round(z));
        return String.format(Locale.US, "%.1f", z);
    }

    private void updateZoomUi() {
        float z = cc.zoom;
        int best = -1;
        double bd = 1e9;
        for (int i = 0; i < zoomVals.size(); i++) {
            double dd = Math.abs(Math.log(z / zoomVals.get(i)));
            if (dd < bd) {
                bd = dd;
                best = i;
            }
        }
        for (int i = 0; i < zoomBtns.size(); i++) {
            TextView t = zoomBtns.get(i);
            if (i == best) {
                t.setText(zoomLabel(z) + "x");
                t.setTextColor(AMBER);
            } else {
                float v = zoomVals.get(i);
                t.setText(v < 1 ? ".5" : zoomLabel(v));
                t.setTextColor(Color.WHITE);
            }
        }
    }

    private void saveAndApply() {
        sp.edit().putInt("grid", gridMode).putBoolean("level", levelOn).putBoolean("hist", histOn)
                .putBoolean("zebra", zebraOn).putBoolean("peak", peakOn).putBoolean("raw", rawOn)
                .putInt("timer", timer).apply();
        applyToggles();
    }

    private void applyToggles() {
        setChip(chipGrid, gridMode > 0);
        chipGrid.setText(gridMode == 2 ? "構圖線＋對角" : "構圖線");
        setChip(chipLevel, levelOn);
        setChip(chipHist, histOn);
        setChip(chipZebra, zebraOn);
        setChip(chipPeak, peakOn);
        setChip(chipRaw, rawOn);
        chipRaw.setText(rawOn ? "RAW＋JPEG" : "RAW");
        setChip(chipTimer, timer > 0);
        chipTimer.setText(timer == 0 ? "計時 關" : "計時 " + timer + " 秒");
        ov.showGrid = gridMode > 0;
        ov.gridType = gridMode == 2 ? 1 : 0;
        ov.showLevel = levelOn;
        ov.showHist = histOn;
        cc.zebra = zebraOn;
        cc.peaking = peakOn;
        if (!zebraOn && !peakOn) ov.setFx(null, 0, 0);
        ov.invalidate();
    }

    // ================= 參數分頁 =================

    private void selectTab(int i) {
        tab = i;
        refreshTabs();
        refreshControls();
    }

    private void refreshTabs() {
        for (int i = 0; i < 5; i++) {
            String v;
            boolean manual;
            switch (i) {
                case 0:
                    manual = cc.manIso != null;
                    v = manual ? String.valueOf(cc.manIso) : "AUTO";
                    break;
                case 1:
                    manual = cc.manExp != null;
                    v = manual ? fmtShutter(cc.manExp / 1e9) : "AUTO";
                    break;
                case 2:
                    manual = cc.manFocus != null;
                    v = manual ? fmtFocus(cc.manFocus) : (cc.afLocked ? "AF 鎖定" : "AF");
                    break;
                case 3:
                    manual = cc.awb != CameraMetadata.CONTROL_AWB_MODE_AUTO;
                    v = manual ? awbShort(cc.awb) : "AWB";
                    break;
                default:
                    manual = cc.evIdx != 0;
                    v = fmtEv(cc.evIdx);
            }
            SpannableStringBuilder sb = new SpannableStringBuilder(TAB_TITLES[i]);
            sb.append('\n');
            int st = sb.length();
            sb.append(v);
            sb.setSpan(new ForegroundColorSpan(manual ? AMBER : 0xFF9E9E9E), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new RelativeSizeSpan(1.1f), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tabs[i].setText(sb);
            tabs[i].setBackground(i == tab ? round(0xFF333333, 10) : null);
            tabs[i].setTextColor(i == tab ? Color.WHITE : 0xFFBBBBBB);
        }
        updateInfo2();
    }

    private void refreshControls() {
        updatingSeek = true;
        boolean enabled = true;
        boolean auto;
        String extra = "";
        switch (tab) {
            case 0: {
                enabled = cc.manualSensor;
                auto = cc.manIso == null;
                int cur = !auto ? cc.manIso : (info != null && info.iso > 0 ? info.iso : 100);
                seek.setMax(isoList.length - 1);
                seek.setProgress(nearest(isoList, cur));
                setText(valueTv, auto ? "A  " + cur : String.valueOf(cur));
                break;
            }
            case 1: {
                enabled = cc.manualSensor;
                auto = cc.manExp == null;
                long cur = !auto ? cc.manExp : (info != null && info.exp > 0 ? info.exp : 16_666_666L);
                seek.setMax(expList.length - 1);
                seek.setProgress(nearest(expList, cur));
                setText(valueTv, (auto ? "A  " : "") + fmtShutter(cur / 1e9));
                break;
            }
            case 2: {
                enabled = cc.manualFocusOk;
                auto = cc.manFocus == null;
                float cur = !auto ? cc.manFocus : (info != null && info.focusDiopter >= 0 ? info.focusDiopter : 0f);
                seek.setMax(100);
                seek.setProgress(cc.minFocus > 0 ? Math.round(100f * (float) Math.sqrt(Math.min(1f, cur / cc.minFocus))) : 0);
                setText(valueTv, (auto ? "AF " : "") + fmtFocus(cur));
                if (!enabled) extra = "\n（這個鏡頭不支援手動對焦）";
                break;
            }
            case 3: {
                auto = cc.awb == CameraMetadata.CONTROL_AWB_MODE_AUTO;
                enabled = awbList.length > 0;
                seek.setMax(Math.max(0, awbList.length - 1));
                int idx = indexOf(awbList, cc.awb);
                if (idx < 0) idx = Math.max(0, indexOf(awbList, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT));
                seek.setProgress(idx);
                setText(valueTv, auto ? "自動" : awbShort(cc.awb));
                break;
            }
            default: {
                auto = cc.evIdx == 0;
                int lo = cc.evRange.getLower();
                seek.setMax(cc.evRange.getUpper() - lo);
                seek.setProgress(cc.evIdx - lo);
                setText(valueTv, fmtEv(cc.evIdx) + " EV");
                if (cc.manIso != null && cc.manExp != null) extra = "\n（目前是全手動 M，曝光補償不作用，請看上方「測光」）";
            }
        }
        if (!cc.manualSensor && tab <= 1) extra = "\n（這支手機不支援手動曝光）";
        seek.setEnabled(enabled);
        autoChip.setEnabled(enabled);
        setChip(autoChip, auto);
        autoChip.setText(tab == 4 ? "歸零" : "AUTO");
        setText(tip, Lessons.TAB_TIPS[tab] + extra);
        updatingSeek = false;
    }

    private void onSeek(int p) {
        switch (tab) {
            case 0:
                cc.setIso(isoList[p]);
                break;
            case 1:
                cc.setExposure(expList[p]);
                break;
            case 2:
                float f = p / 100f;
                cc.setFocus(cc.minFocus * f * f);
                ov.clearFocus();
                break;
            case 3:
                if (awbList.length > 0) cc.setAwb(awbList[p]);
                break;
            default:
                cc.setEv(cc.evRange.getLower() + p);
        }
        refreshTabs();
        refreshControls();
    }

    private void toggleAuto() {
        int p = seek.getProgress();
        switch (tab) {
            case 0:
                cc.setIso(cc.manIso == null ? Integer.valueOf(isoList[p]) : null);
                break;
            case 1:
                cc.setExposure(cc.manExp == null ? Long.valueOf(expList[p]) : null);
                break;
            case 2:
                if (cc.manFocus == null) {
                    float f = p / 100f;
                    cc.setFocus(cc.minFocus * f * f);
                    ov.clearFocus();
                } else cc.setFocus(null);
                break;
            case 3:
                if (cc.awb == CameraMetadata.CONTROL_AWB_MODE_AUTO && awbList.length > 0) cc.setAwb(awbList[p]);
                else cc.setAwb(CameraMetadata.CONTROL_AWB_MODE_AUTO);
                break;
            default:
                cc.setEv(0);
        }
        refreshTabs();
        refreshControls();
    }

    private void resetAll() {
        cc.setIso(null);
        cc.setExposure(null);
        cc.setFocus(null);
        cc.resetFocus();
        cc.setAwb(CameraMetadata.CONTROL_AWB_MODE_AUTO);
        cc.setEv(0);
        ov.clearFocus();
        ov.banner("已回到全自動");
        refreshTabs();
        refreshControls();
    }

    private void tapFocus(float x, float y) {
        cc.focusAt(x / pf.getWidth(), y / pf.getHeight());
        ov.setFocus(x, y, cc.manFocus == null);
        if (cc.manFocus != null) ov.banner("手動對焦中：只測光");
        else if (cc.isManualExposure()) ov.banner("已對焦（手動曝光不受測光影響）");
        refreshTabs();
    }

    // ================= 拍照 =================

    private void shoot() {
        if (cc == null || busy) return;
        busy = true;
        shutter.setAlpha(0.4f);
        if (timer > 0) countdown(timer);
        else fire();
    }

    private void countdown(final int n) {
        if (n <= 0) {
            ov.setCountdown(0);
            fire();
            return;
        }
        ov.setCountdown(n);
        Vibrator vb = getSystemService(Vibrator.class);
        if (vb != null) vb.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        h.postDelayed(() -> countdown(n - 1), 1000);
    }

    private void fire() {
        sound.play(MediaActionSound.SHUTTER_CLICK);
        ov.flash();
        if (info != null && info.exp > 500_000_000L) ov.banner("長曝光中，請保持不動…");
        cc.capture(devOrientation);
    }

    @Override
    public void onCaptureDone() {
        busy = false;
        shutter.setAlpha(1f);
    }

    @Override
    public void onSaved(final Uri uri, boolean isRaw) {
        if (isRaw) {
            ov.banner("已儲存 RAW (DNG)");
            return;
        }
        lastUri = uri;
        new Thread(() -> {
            try {
                final Bitmap bm = getContentResolver().loadThumbnail(uri, new Size(240, 240), null);
                runOnUiThread(() -> thumb.setImageBitmap(bm));
            } catch (IOException ignored) {
            }
        }).start();
    }

    private void openGallery() {
        if (lastUri == null) {
            toast("照片存在 相簿 → DCIM/ProCam");
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(lastUri, "image/jpeg");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("找不到可以開啟照片的 App");
        }
    }

    // ================= 相機回報 =================

    @Override
    public void onReady(boolean rawActive) {
        if (rawOn != rawActive) {
            rawOn = rawActive;
            saveAndApply();
        }
        refreshTabs();
        refreshControls();
    }

    @Override
    public void onInfo(CamController.Info i) {
        info = i;
        float n = i.aperture > 0 ? i.aperture : 1.68f;
        setText(info1, String.format(Locale.US, "ISO %d   %s   f/%.1f   %dmm",
                i.iso, fmtShutter(i.exp / 1e9), n, Math.round(cc.baseEquivMm * cc.zoom)));
        ov.setAfState(i.afState);
        updateInfo2();
        if ((tab == 0 && cc.manIso == null) || (tab == 1 && cc.manExp == null) || (tab == 2 && cc.manFocus == null))
            refreshControls();
        updateCoach();
    }

    @Override
    public void onAnalysis(int[] hist, int m, float hi, float lo, int[] fx, int fw, int fh) {
        mean = m;
        clipHi = hi;
        clipLo = lo;
        if (histOn) ov.setHistogram(hist, hi, lo);
        if (zebraOn || peakOn) ov.setFx(fx, fw, fh);
        else ov.setFx(null, 0, 0);
    }

    @Override
    public void onMessage(String msg) {
        toast(msg);
    }

    private double meterStops() {
        return 2.2 * Math.log(Math.max(mean, 1) / 118.0) / Math.log(2);
    }

    private void updateInfo2() {
        boolean mi = cc.manIso != null, me = cc.manExp != null;
        String mode = mi && me ? "M 全手動" : me ? "S 快門優先" : mi ? "ISO 優先" : "P 全自動";
        StringBuilder sb = new StringBuilder(mode);
        if (info != null && info.iso > 0 && info.exp > 0) {
            float n = info.aperture > 0 ? info.aperture : 1.68f;
            double ev = Math.log(n * n / (info.exp / 1e9)) / Math.log(2) - Math.log(info.iso / 100.0) / Math.log(2);
            sb.append(String.format(Locale.US, " · 場景 EV %.1f", ev));
            if (info.focusDiopter >= 0) sb.append(" · 焦點 ").append(fmtFocus(info.focusDiopter));
        }
        sb.append(" · ").append(zoomLabel(cc.zoom)).append('x');
        if (mi && me) sb.append(String.format(Locale.US, " · 測光 %+.1f", meterStops()));
        setText(info2, sb.toString());
    }

    private void updateCoach() {
        String c;
        double t = info != null ? info.exp / 1e9 : 0;
        double safe = 2.0 / (cc.baseEquivMm * Math.max(cc.zoom, 0.5f));
        boolean full = cc.manIso != null && cc.manExp != null;
        double ms = meterStops();
        if (clipHi > 3)
            c = "⚠ 高光過曝（直方圖撞右牆）：降低曝光補償、加快快門或降低 ISO";
        else if (cc.previewSimulated())
            c = "長曝光 " + fmtShutter(t) + "：預覽亮度為模擬，按快門後要等曝光結束，請用腳架或靠穩＋計時";
        else if (t > safe && t > 0)
            c = "⚠ 快門 " + fmtShutter(t) + " 比安全快門 " + fmtShutter(safe) + " 慢：手持可能糊，靠穩或用計時";
        else if (info != null && info.iso >= 3200)
            c = "ISO " + info.iso + " 偏高：雜訊會明顯，光線允許就降低 ISO";
        else if (full && Math.abs(ms) > 1)
            c = String.format(Locale.US, "比標準曝光%s %.1f 檔：%s", ms > 0 ? "亮" : "暗", Math.abs(ms),
                    ms > 0 ? "加快快門或降低 ISO" : "放慢快門或提高 ISO");
        else if (clipLo > 20)
            c = "暗部大量死黑（直方圖撞左牆）：可提高曝光，或刻意拍成剪影";
        else
            c = "✓ 曝光正常　點畫面＝對焦＋測光，長按解除，雙指縮放";
        setText(coach, c);
        coach.setTextColor(c.startsWith("✓") ? 0xFFB9F6CA : AMBER);
    }

    // ================= 水平儀 =================

    @Override
    public void onSensorChanged(SensorEvent e) {
        float ax = e.values[0], ay = e.values[1];
        if (Math.hypot(ax, ay) < 3) {
            ov.setRoll(Float.NaN); // 手機平放時不顯示
            return;
        }
        ov.setRoll((float) Math.toDegrees(Math.atan2(-ax, ay)));
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) {
    }

    // ================= 小工具 =================

    private void showBook() {
        TextView t = text(15, 0xFFE0E0E0);
        t.setPadding(dp(20), dp(8), dp(20), dp(20));
        t.setLineSpacing(0, 1.25f);
        t.setText(Html.fromHtml(Lessons.BOOK, Html.FROM_HTML_MODE_COMPACT));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("📖 攝影小課本")
                .setView(sv)
                .setPositiveButton("開始練習", null)
                .show();
    }

    /** 只有內容變了才更新，避免每秒重排版好幾次 */
    private static void setText(TextView t, CharSequence s) {
        if (!s.toString().contentEquals(t.getText())) t.setText(s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    static String fmtShutter(double s) {
        if (s <= 0) return "--";
        if (s < 0.29) return "1/" + Math.round(1 / s);
        if (s < 10) {
            String v = String.format(Locale.US, "%.1f", s);
            if (v.endsWith(".0")) v = v.substring(0, v.length() - 2);
            return v + "\"";
        }
        return Math.round(s) + "\"";
    }

    String fmtFocus(float diopter) {
        String pre = cc.focusCalibrated ? "" : "約";
        if (diopter <= 0.02f) return "∞";
        float m = 1f / diopter;
        if (m < 1f) return pre + Math.round(m * 100) + "cm";
        if (m > 20f) return "∞";
        return pre + String.format(Locale.US, "%.1fm", m);
    }

    String fmtEv(int idx) {
        float v = idx * cc.evStep;
        if (Math.abs(v) < 0.01f) return "±0";
        return String.format(Locale.US, "%+.1f", v);
    }

    String awbShort(int mode) {
        int i = indexOf(awbList, mode);
        if (i < 0) return "自動";
        String n = awbNames[i];
        int sp = n.indexOf(' ');
        return sp > 0 ? n.substring(0, sp) : n;
    }

    static int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return -1;
    }

    static int nearest(int[] a, int v) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < a.length; i++) {
            double d = Math.abs(Math.log((double) a[i] / Math.max(v, 1)));
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    static int nearest(long[] a, long v) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < a.length; i++) {
            double d = Math.abs(Math.log((double) a[i] / Math.max(v, 1)));
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }
}
