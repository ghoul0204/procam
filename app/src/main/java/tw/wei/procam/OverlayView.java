package tw.wei.procam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import java.util.Locale;

/** 疊在預覽上的輔助資訊：構圖線、水平儀、直方圖、斑馬紋／峰值對焦、對焦框、倒數、快門閃光 */
public class OverlayView extends View {

    boolean showGrid = true, showLevel = true, showHist = true;
    int gridType = 0; // 0 三分法, 1 三分法+對角線

    private int[] hist;
    private float clipHi, clipLo;
    private Bitmap fx;
    private float roll;
    private boolean rollValid;
    private float focusX = -1, focusY = -1;
    private long focusTime;
    private int afState;
    private boolean afLocked;
    private int countdown;
    private long flashTime;
    private String banner;
    private long bannerTime;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint();
    private final float d;

    public OverlayView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setShadowLayer(3 * d, 0, 0, Color.BLACK);
        bmp.setFilterBitmap(false);
        bmp.setAlpha(200);
    }

    public void setHistogram(int[] h, float hi, float lo) {
        hist = h;
        clipHi = hi;
        clipLo = lo;
        invalidate();
    }

    public void setFx(int[] px, int w, int h) {
        if (px == null) {
            if (fx != null) {
                fx = null;
                invalidate();
            }
            return;
        }
        if (fx == null || fx.getWidth() != w || fx.getHeight() != h)
            fx = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        fx.setPixels(px, 0, w, 0, 0, w, h);
        invalidate();
    }

    public void setRoll(float r) {
        rollValid = !Float.isNaN(r);
        if (rollValid) roll = r;
        if (showLevel) invalidate();
    }

    public void setFocus(float x, float y, boolean locked) {
        focusX = x;
        focusY = y;
        afLocked = locked;
        focusTime = SystemClock.uptimeMillis();
        afState = 0;
        invalidate();
    }

    public void clearFocus() {
        focusX = -1;
        afLocked = false;
        invalidate();
    }

    public void setAfState(int s) {
        if (s != afState) {
            afState = s;
            invalidate();
        }
    }

    public void setCountdown(int n) {
        countdown = n;
        invalidate();
    }

    public void flash() {
        flashTime = SystemClock.uptimeMillis();
        invalidate();
    }

    public void banner(String s) {
        banner = s;
        bannerTime = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        long now = SystemClock.uptimeMillis();

        if (fx != null) c.drawBitmap(fx, null, new Rect(0, 0, w, h), bmp);

        if (showGrid) drawGrid(c, w, h);
        if (showLevel && rollValid) drawLevel(c, w, h);
        if (showHist && hist != null) drawHist(c);
        drawFocus(c, now);

        if (countdown > 0) {
            text.setTextSize(96 * d);
            text.setColor(Color.WHITE);
            c.drawText(String.valueOf(countdown), w / 2f, h / 2f + 32 * d, text);
        }

        if (banner != null) {
            long age = now - bannerTime;
            if (age < 1200) {
                text.setTextSize(22 * d);
                text.setColor(Color.WHITE);
                c.drawText(banner, w / 2f, h * 0.18f, text);
                postInvalidateDelayed(100);
            } else banner = null;
        }

        long fa = now - flashTime;
        if (fa < 180) {
            fill.setColor(Color.argb((int) (200 * (1 - fa / 180f)), 255, 255, 255));
            c.drawRect(0, 0, w, h, fill);
            postInvalidateOnAnimation();
        }
    }

    private void drawGrid(Canvas c, int w, int h) {
        line.setStrokeWidth(1 * d);
        line.setColor(0x80FFFFFF);
        for (int i = 1; i <= 2; i++) {
            c.drawLine(w * i / 3f, 0, w * i / 3f, h, line);
            c.drawLine(0, h * i / 3f, w, h * i / 3f, line);
        }
        if (gridType == 1) {
            line.setColor(0x50FFFFFF);
            c.drawLine(0, 0, w, h, line);
            c.drawLine(w, 0, 0, h, line);
        }
    }

    private void drawLevel(Canvas c, int w, int h) {
        float dev = roll - Math.round(roll / 90f) * 90f;
        boolean level = Math.abs(dev) < 1f;
        float cx = w / 2f, cy = h / 2f, len = w * 0.22f;
        int col = level ? 0xFF00E676 : 0xFFFFFFFF;

        // 固定參考刻度
        line.setStrokeWidth(2 * d);
        line.setColor(0xA0FFFFFF);
        c.drawLine(cx - len - 24 * d, cy, cx - len - 6 * d, cy, line);
        c.drawLine(cx + len + 6 * d, cy, cx + len + 24 * d, cy, line);

        // 實際水平線（跟著手機傾斜轉動）
        c.save();
        c.rotate(-dev, cx, cy);
        line.setStrokeWidth((level ? 3 : 2) * d);
        line.setColor(col);
        c.drawLine(cx - len, cy, cx - 10 * d, cy, line);
        c.drawLine(cx + 10 * d, cy, cx + len, cy, line);
        c.restore();

        if (!level) {
            text.setTextSize(12 * d);
            text.setColor(0xFFFFFFFF);
            c.drawText(String.format(Locale.US, "%.1f°", dev), cx, cy - 10 * d, text);
        }
    }

    private void drawHist(Canvas c) {
        float x0 = 10 * d, y0 = 10 * d, hw = 128 * d, hh = 56 * d;
        fill.setColor(0x88000000);
        c.drawRoundRect(new RectF(x0 - 4 * d, y0 - 4 * d, x0 + hw + 4 * d, y0 + hh + 4 * d), 6 * d, 6 * d, fill);
        int max = 1;
        for (int i = 1; i < hist.length - 1; i++) max = Math.max(max, hist[i]);
        float bw = hw / hist.length;
        fill.setColor(0xDDFFFFFF);
        for (int i = 0; i < hist.length; i++) {
            float v = (float) Math.sqrt(Math.min(1f, hist[i] / (float) max));
            c.drawRect(x0 + i * bw, y0 + hh * (1 - v), x0 + (i + 1) * bw, y0 + hh, fill);
        }
        // 中間灰（18% 灰）參考線
        line.setStrokeWidth(1 * d);
        line.setColor(0x60FFFFFF);
        c.drawLine(x0 + hw * 118 / 256f, y0, x0 + hw * 118 / 256f, y0 + hh, line);
        // 過曝／死黑警示
        if (clipHi > 1f) {
            fill.setColor(0xFFFF1744);
            c.drawRect(x0 + hw - 3 * d, y0, x0 + hw, y0 + hh, fill);
        }
        if (clipLo > 5f) {
            fill.setColor(0xFF2979FF);
            c.drawRect(x0, y0, x0 + 3 * d, y0 + hh, fill);
        }
    }

    private void drawFocus(Canvas c, long now) {
        if (focusX < 0) return;
        long age = now - focusTime;
        if (!afLocked && age > 1500) return;
        int col = 0xFFFFFFFF;
        if (afState == 4) col = 0xFF00E676;      // FOCUSED_LOCKED
        else if (afState == 5) col = 0xFFFF5252; // NOT_FOCUSED_LOCKED
        else if (afState == 3) col = 0xFFFFD54F; // ACTIVE_SCAN
        float r = 36 * d;
        line.setStrokeWidth(2 * d);
        line.setColor(col);
        c.drawCircle(focusX, focusY, r, line);
        c.drawCircle(focusX, focusY, 3 * d, line);
        if (afLocked) {
            text.setTextSize(12 * d);
            text.setColor(col);
            c.drawText("AF/AE 鎖定・長按解除", focusX, focusY + r + 16 * d, text);
        }
        if (!afLocked) postInvalidateDelayed(200);
    }
}
