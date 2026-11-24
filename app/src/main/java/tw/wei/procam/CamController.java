package tw.wei.procam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Camera2 控制核心：預覽、手動曝光（含軟體版快門優先／ISO 優先）、
 * 手動對焦、白平衡、變焦、即時分析（直方圖／斑馬紋／峰值對焦）、JPEG + RAW 拍攝與儲存。
 */
public class CamController {

    public interface Listener {
        void onReady(boolean rawActive);
        void onInfo(Info info);
        void onAnalysis(int[] hist, int mean, float clipHiPct, float clipLoPct,
                        int[] fx, int fxW, int fxH);
        void onSaved(Uri uri, boolean isRaw);
        void onCaptureDone();
        void onMessage(String msg);
    }

    public static class Info {
        public int iso;
        public long exp;          // ns
        public float aperture;
        public float focusDiopter;
        public int afState;
        public boolean manualExposure;
    }

    // ---------- 相機能力 ----------
    final CameraManager mgr;
    final ContentResolver resolver;
    final Listener L;
    final Handler ui = new Handler(Looper.getMainLooper());

    String camId;
    CameraCharacteristics ch;
    int sensorOrientation = 90;
    Rect active;
    boolean manualSensor, manualFocusOk, rawSupported, zoomRatioSupported;
    Range<Integer> isoRange = new Range<>(50, 6400);
    Range<Long> expRange = new Range<>(10_000L, 1_000_000_000L);
    float minFocus;          // 最近對焦距離（屈光度，1/公尺）
    boolean focusCalibrated;
    Range<Float> zoomRange = new Range<>(1f, 1f);
    Range<Integer> evRange = new Range<>(0, 0);
    float evStep = 1f / 3f;
    int[] awbModes = new int[0];
    float baseEquivMm = 24f;   // 1x 等效焦距（35mm 換算）
    int maxAfRegions, maxAeRegions;
    Size previewSize, yuvSize, jpegSize, rawSize;

    // ---------- 執行期 ----------
    HandlerThread camThread, anThread, saveThread;
    Handler cam, an, saver;
    CameraDevice dev;
    CameraCaptureSession sess;
    ImageReader jpegR, rawR, yuvR;
    Surface previewSurf;
    boolean rawActive;

    // ---------- 使用者設定（UI 執行緒寫入） ----------
    volatile Integer manIso;      // null = 自動
    volatile Long manExp;         // null = 自動（ns）
    volatile Float manFocus;      // null = 自動對焦（屈光度）
    volatile int awb = CameraMetadata.CONTROL_AWB_MODE_AUTO;
    volatile int evIdx = 0;
    volatile float zoom = 1f;
    volatile boolean zebra, peaking, wantRaw;

    // 實際送給感光元件的值（手動或半自動時）
    volatile int curIso = 100;
    volatile long curExp = 16_666_666L;
    volatile int lastAutoIso = 100;
    volatile long lastAutoExp = 16_666_666L;
    volatile int aeSkip = 0;

    volatile MeteringRectangle[] regions;
    volatile boolean afLocked;

    private Pending pending;
    private long lastInfoTime, lastAnTime;
    private byte[] yBuf;
    private int zebraPhase;

    public CamController(Context ctx, Listener l) throws CameraAccessException {
        L = l;
        resolver = ctx.getContentResolver();
        mgr = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        for (String id : mgr.getCameraIdList()) {
            CameraCharacteristics c = mgr.getCameraCharacteristics(id);
            Integer f = c.get(CameraCharacteristics.LENS_FACING);
            if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) {
                camId = id;
                ch = c;
                break;
            }
        }
        if (camId == null) throw new IllegalStateException("找不到後鏡頭");
        readCapabilities();
    }

    private static boolean has(int[] arr, int v) {
        if (arr == null) return false;
        for (int a : arr) if (a == v) return true;
        return false;
    }

    private void readCapabilities() {
        Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
        if (so != null) sensorOrientation = so;
        active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (active == null) active = new Rect(0, 0, 4000, 3000);

        int[] caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        manualSensor = has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
        rawSupported = has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW);

        Range<Integer> ir = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (ir != null) isoRange = ir;
        Range<Long> er = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (er != null) expRange = er;

        Float mf = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        minFocus = mf == null ? 0f : mf;
        manualFocusOk = manualSensor && minFocus > 0f;
        Integer cal = ch.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION);
        focusCalibrated = cal != null
                && cal != CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED;

        Range<Float> zr = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        if (zr != null) {
            zoomRange = zr;
            zoomRatioSupported = true;
        } else {
            Float md = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            zoomRange = new Range<>(1f, md == null ? 1f : md);
        }

        Range<Integer> evr = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        if (evr != null) evRange = evr;
        Rational st = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        if (st != null && st.floatValue() > 0) evStep = st.floatValue();

        int[] aw = ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        if (aw != null) awbModes = aw;

        Integer ra = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        maxAfRegions = ra == null ? 0 : ra;
        Integer re = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        maxAeRegions = re == null ? 0 : re;

        float[] fl = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (fl != null && fl.length > 0 && phys != null) {
            double diag = Math.hypot(phys.getWidth(), phys.getHeight());
            if (diag > 0) {
                float eq = (float) (fl[0] * 43.27 / diag);
                if (eq > 10 && eq < 80) baseEquivMm = eq;
            }
        }

        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        previewSize = pick(map.getOutputSizes(SurfaceTexture.class), 1920, 1080, true);
        yuvSize = pickSmall(map.getOutputSizes(ImageFormat.YUV_420_888));
        jpegSize = pick(map.getOutputSizes(ImageFormat.JPEG), 100000, 100000, true);
        if (rawSupported) {
            Size[] rs = map.getOutputSizes(ImageFormat.RAW_SENSOR);
            if (rs != null && rs.length > 0) rawSize = pick(rs, 100000, 100000, false);
            else rawSupported = false;
        }
    }

    private static boolean is43(Size s) {
        return s.getWidth() * 3 == s.getHeight() * 4;
    }

    private static Size pick(Size[] sizes, int maxW, int maxH, boolean want43) {
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() > maxW || s.getHeight() > maxH) continue;
            if (want43 && !is43(s)) continue;
            if (best == null || (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight())
                best = s;
        }
        if (best == null) {
            for (Size s : sizes)
                if (best == null || (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight())
                    best = s;
        }
        return best;
    }

    private static Size pickSmall(Size[] sizes) {
        for (Size s : sizes) if (s.getWidth() == 640 && s.getHeight() == 480) return s;
        Size best = null;
        for (Size s : sizes) {
            if (!is43(s) || s.getWidth() < 320 || s.getWidth() > 1024) continue;
            if (best == null || s.getWidth() < best.getWidth()) best = s;
        }
        return best != null ? best : sizes[sizes.length - 1];
    }

    // ================= 開關相機 =================

    public void open(SurfaceTexture st) {
        if (cam != null) return;
        camThread = new HandlerThread("cam");
        camThread.start();
        cam = new Handler(camThread.getLooper());
        anThread = new HandlerThread("analysis");
        anThread.start();
        an = new Handler(anThread.getLooper());
        saveThread = new HandlerThread("save");
        saveThread.start();
        saver = new Handler(saveThread.getLooper());

        st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        previewSurf = new Surface(st);
        try {
            mgr.openCamera(camId, stateCb, cam);
        } catch (CameraAccessException | SecurityException e) {
            msg("無法開啟相機：" + e.getMessage());
        }
    }

    public void close() {
        if (cam == null) return;
        final CountDownLatch latch = new CountDownLatch(1);
        cam.post(() -> {
            closeSession();
            if (dev != null) {
                dev.close();
                dev = null;
            }
            latch.countDown();
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        camThread.quitSafely();
        anThread.quitSafely();
        saveThread.quitSafely();
        cam = null;
        an = null;
        saver = null;
        pending = null;
        if (previewSurf != null) previewSurf.release();
        previewSurf = null;
    }

    private final CameraDevice.StateCallback stateCb = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice d) {
            dev = d;
            createSession();
        }

        @Override
        public void onDisconnected(CameraDevice d) {
            d.close();
            dev = null;
        }

        @Override
        public void onError(CameraDevice d, int error) {
            d.close();
            dev = null;
            msg("相機錯誤（代碼 " + error + "），請重新開啟 App");
        }
    };

    private void closeSession() {
        if (sess != null) {
            try {
                sess.close();
            } catch (Exception ignored) {
            }
            sess = null;
        }
        if (yuvR != null) yuvR.close();
        if (jpegR != null) jpegR.close();
        if (rawR != null) rawR.close();
        yuvR = jpegR = rawR = null;
    }

    /** 開/關 RAW 需要重建 session */
    public void setRaw(boolean on) {
        wantRaw = on;
        if (cam != null) cam.post(this::createSession);
    }

    private void createSession() {
        if (dev == null) return;
        closeSession();
        yuvR = ImageReader.newInstance(yuvSize.getWidth(), yuvSize.getHeight(), ImageFormat.YUV_420_888, 3);
        yuvR.setOnImageAvailableListener(this::onYuv, an);
        jpegR = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 3);
        jpegR.setOnImageAvailableListener(this::onJpeg, cam);
        rawActive = wantRaw && rawSupported && rawSize != null;
        if (rawActive) {
            rawR = ImageReader.newInstance(rawSize.getWidth(), rawSize.getHeight(), ImageFormat.RAW_SENSOR, 3);
            rawR.setOnImageAvailableListener(this::onRaw, cam);
        }
        List<Surface> outs = new ArrayList<>();
        outs.add(previewSurf);
        outs.add(yuvR.getSurface());
        outs.add(jpegR.getSurface());
        if (rawActive) outs.add(rawR.getSurface());
        final boolean triedRaw = rawActive;
        try {
            dev.createCaptureSession(outs, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    sess = s;
                    doUpdatePreview();
                    final boolean r = rawActive;
                    ui.post(() -> L.onReady(r));
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    if (triedRaw) {
                        wantRaw = false;
                        msg("這個組合不支援 RAW，已自動關閉 RAW");
                        createSession();
                    } else {
                        msg("相機設定失敗");
                    }
                }
            }, cam);
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
            msg("相機設定失敗：" + e.getMessage());
        }
    }

    // ================= 設定 =================

    public boolean isManualExposure() {
        return manualSensor && (manIso != null || manExp != null);
    }

    public void setIso(Integer iso) {
        boolean was = isManualExposure();
        double p = was ? (double) curIso * curExp : (double) lastAutoIso * lastAutoExp;
        manIso = iso;
        exposureChanged(was, p);
    }

    public void setExposure(Long ns) {
        boolean was = isManualExposure();
        double p = was ? (double) curIso * curExp : (double) lastAutoIso * lastAutoExp;
        manExp = ns;
        exposureChanged(was, p);
    }

    /** 切換到手動時，用「目前的曝光量」換算另一個自動參數，畫面亮度才不會突然跳 */
    private void exposureChanged(boolean wasManual, double product) {
        if (!wasManual) {
            curIso = lastAutoIso;
            curExp = lastAutoExp;
        }
        Integer mi = manIso;
        Long me = manExp;
        if (mi != null) curIso = mi;
        if (me != null) curExp = me;
        if (mi != null && me == null) curExp = clampExp((long) (product / curIso), true);
        else if (me != null && mi == null) curIso = clampIso((int) Math.round(product / curExp));
        aeSkip = 3;
        updatePreview();
    }

    int clampIso(int v) {
        return Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), v));
    }

    long clampExp(long v, boolean auto) {
        long hi = expRange.getUpper();
        if (auto) hi = Math.min(hi, 250_000_000L); // 自動快門最慢 1/4 秒
        return Math.max(expRange.getLower(), Math.min(hi, v));
    }

    public void setFocus(Float diopter) {
        manFocus = diopter;
        if (diopter != null) afLocked = false;
        updatePreview();
    }

    public void setAwb(int mode) {
        awb = mode;
        updatePreview();
    }

    public void setEv(int idx) {
        evIdx = Math.max(evRange.getLower(), Math.min(evRange.getUpper(), idx));
        aeSkip = 2;
        updatePreview();
    }

    public void setZoom(float z) {
        zoom = Math.max(zoomRange.getLower(), Math.min(zoomRange.getUpper(), z));
        updatePreview();
    }

    public void updatePreview() {
        Handler h = cam;
        if (h != null) h.post(this::doUpdatePreview);
    }

    private void doUpdatePreview() {
        if (sess == null || dev == null) return;
        try {
            CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(previewSurf);
            b.addTarget(yuvR.getSurface());
            apply(b, false);
            sess.setRepeatingRequest(b.build(), previewCb, cam);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException ignored) {
        }
    }

    private void apply(CaptureRequest.Builder b, boolean still) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

        // 變焦（Pixel 會自動在超廣角／主鏡頭／長焦之間切換）
        if (zoomRatioSupported) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
        } else if (zoom > 1.01f) {
            int w = (int) (active.width() / zoom), h = (int) (active.height() / zoom);
            int l = (active.width() - w) / 2, t = (active.height() - h) / 2;
            b.set(CaptureRequest.SCALER_CROP_REGION, new Rect(l, t, l + w, t + h));
        }

        MeteringRectangle[] rg = regions;

        // 對焦
        Float mf = manFocus;
        if (mf != null && manualFocusOk) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, mf);
        } else if (afLocked) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
            if (rg != null && maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, rg);
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }

        // 曝光
        if (isManualExposure()) {
            long e = curExp;
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, curIso);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(e, 33_333_333L));
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIdx);
            if (rg != null && maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, rg);
        }

        b.set(CaptureRequest.CONTROL_AWB_MODE, awb);

        if (still) {
            b.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY);
            b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY);
        }
    }

    // ================= 點擊對焦 / 測光 =================

    /** nx, ny：在直式預覽畫面上的位置（0~1） */
    public void focusAt(float nx, float ny) {
        float sx, sy;
        switch (sensorOrientation) {
            case 90:  sx = ny;      sy = 1 - nx; break;
            case 270: sx = 1 - ny;  sy = nx;     break;
            case 180: sx = 1 - nx;  sy = 1 - ny; break;
            default:  sx = nx;      sy = ny;
        }
        int W = active.width(), H = active.height();
        int hw = W / 12, hh = H / 12;
        int cx = (int) (sx * W), cy = (int) (sy * H);
        int l = Math.max(0, cx - hw), t = Math.max(0, cy - hh);
        int r = Math.min(W - 1, cx + hw), btm = Math.min(H - 1, cy + hh);
        if (r <= l || btm <= t) return;
        regions = new MeteringRectangle[]{
                new MeteringRectangle(new Rect(l, t, r, btm), MeteringRectangle.METERING_WEIGHT_MAX - 1)};
        final boolean doAf = manFocus == null && maxAfRegions > 0;
        afLocked = doAf;
        Handler h = cam;
        if (h == null) return;
        h.post(() -> {
            doUpdatePreview();
            if (!doAf || sess == null) return;
            try {
                CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                b.addTarget(previewSurf);
                b.addTarget(yuvR.getSurface());
                apply(b, false);
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                sess.capture(b.build(), null, cam);
            } catch (CameraAccessException | IllegalStateException | IllegalArgumentException ignored) {
            }
        });
    }

    public void resetFocus() {
        regions = null;
        final boolean wasLocked = afLocked;
        afLocked = false;
        Handler h = cam;
        if (h == null) return;
        h.post(() -> {
            if (wasLocked && sess != null) {
                try {
                    CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                    b.addTarget(previewSurf);
                    b.addTarget(yuvR.getSurface());
                    b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
                    b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                    sess.capture(b.build(), null, cam);
                } catch (CameraAccessException | IllegalStateException | IllegalArgumentException ignored) {
                }
            }
            doUpdatePreview();
        });
    }

    // ================= 預覽結果（顯示參數用） =================

    private final CameraCaptureSession.CaptureCallback previewCb = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult r) {
            Integer iso = r.get(CaptureResult.SENSOR_SENSITIVITY);
            Long exp = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            boolean manual = isManualExposure();
            if (!manual && iso != null && exp != null && exp > 0) {
                lastAutoIso = iso;
                lastAutoExp = exp;
            }
            long now = SystemClock.uptimeMillis();
            if (now - lastInfoTime < 150) return;
            lastInfoTime = now;
            final Info i = new Info();
            i.iso = iso == null ? 0 : iso;
            i.exp = exp == null ? 0 : exp;
            Float ap = r.get(CaptureResult.LENS_APERTURE);
            i.aperture = ap == null ? 0 : ap;
            Float fd = r.get(CaptureResult.LENS_FOCUS_DISTANCE);
            i.focusDiopter = fd == null ? -1 : fd;
            Integer af = r.get(CaptureResult.CONTROL_AF_STATE);
            i.afState = af == null ? 0 : af;
            i.manualExposure = manual;
            ui.post(() -> L.onInfo(i));
        }
    };

    // ================= 即時分析 =================

    private void onYuv(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastAnTime < 70) return;
            lastAnTime = now;
            int w = img.getWidth(), h = img.getHeight();
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int rs = p.getRowStride(), ps = p.getPixelStride();
            if (yBuf == null || yBuf.length != w * h) yBuf = new byte[w * h];
            if (ps == 1) {
                for (int row = 0; row < h; row++) {
                    buf.position(row * rs);
                    buf.get(yBuf, row * w, w);
                }
            } else {
                for (int row = 0; row < h; row++)
                    for (int x = 0; x < w; x++) yBuf[row * w + x] = buf.get(row * rs + x * ps);
            }
            img.close();
            img = null;
            analyze(w, h);
        } catch (IllegalStateException | IndexOutOfBoundsException ignored) {
        } finally {
            if (img != null) {
                try {
                    img.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void analyze(int w, int h) {
        final int S = 4;
        final int gw = w / S, gh = h / S;
        final byte[] y = yBuf;
        int[] hist = new int[64];
        long sum = 0;
        int n = 0, hi = 0, lo = 0;
        boolean z = zebra, pk = peaking;
        int[] edge = pk ? new int[gw * gh] : null;
        int[] edgeHist = pk ? new int[512] : null;
        boolean[] clip = z ? new boolean[gw * gh] : null;

        for (int gy = 0; gy < gh; gy++) {
            for (int gx = 0; gx < gw; gx++) {
                int bx = gx * S, by = gy * S;
                int v = y[by * w + bx] & 0xff;
                hist[v >> 2]++;
                sum += v;
                n++;
                if (v >= 250) hi++;
                if (v <= 5) lo++;
                if (z) clip[gy * gw + gx] = v >= 248;
                if (pk) {
                    int mx = 0;
                    for (int yy = by; yy < by + S && yy < h - 1; yy++) {
                        int row = yy * w;
                        for (int xx = bx; xx < bx + S && xx < w - 1; xx++) {
                            int a = y[row + xx] & 0xff;
                            int d = Math.abs(a - (y[row + xx + 1] & 0xff)) + Math.abs(a - (y[row + w + xx] & 0xff));
                            if (d > mx) mx = d;
                        }
                    }
                    edge[gy * gw + gx] = mx;
                    edgeHist[Math.min(511, mx)]++;
                }
            }
        }
        int mean = n == 0 ? 0 : (int) (sum / n);

        // 斑馬紋／峰值對焦 疊圖（轉成直式畫面方向）
        int[] fx = null;
        int ow = 0, oh = 0;
        if (z || pk) {
            int thr = 999;
            if (pk) {
                int target = (int) (gw * gh * 0.06), acc = 0;
                for (int i = 511; i >= 0; i--) {
                    acc += edgeHist[i];
                    if (acc >= target) {
                        thr = i;
                        break;
                    }
                }
                thr = Math.max(thr, 36);
            }
            boolean rot = sensorOrientation == 90 || sensorOrientation == 270;
            ow = rot ? gh : gw;
            oh = rot ? gw : gh;
            fx = new int[ow * oh];
            zebraPhase = (zebraPhase + 1) % 8;
            for (int gy = 0; gy < gh; gy++) {
                for (int gx = 0; gx < gw; gx++) {
                    int i = gy * gw + gx;
                    int c = 0;
                    if (pk && edge[i] >= thr) c = 0xFF39FF14;
                    if (z && clip[i] && (((gx + gy + zebraPhase) >> 2) & 1) == 0) c = 0xFFFF1744;
                    if (c == 0) continue;
                    int ox, oy;
                    switch (sensorOrientation) {
                        case 90:  ox = gh - 1 - gy; oy = gx; break;
                        case 270: ox = gy; oy = gw - 1 - gx; break;
                        case 180: ox = gw - 1 - gx; oy = gh - 1 - gy; break;
                        default:  ox = gx; oy = gy;
                    }
                    fx[oy * ow + ox] = c;
                }
            }
        }

        softwareAe(mean);

        final float hp = n == 0 ? 0 : hi * 100f / n, lp = n == 0 ? 0 : lo * 100f / n;
        final int[] fFx = fx;
        final int fw = ow, fh = oh, m = mean;
        ui.post(() -> L.onAnalysis(hist, m, hp, lp, fFx, fw, fh));
    }

    /** 快門優先／ISO 優先：只有一個參數手動時，由軟體調整另一個，讓畫面維持正常亮度 */
    private void softwareAe(int mean) {
        Integer mi = manIso;
        Long me = manExp;
        if (!manualSensor || (mi == null) == (me == null)) return;
        if (aeSkip > 0) {
            aeSkip--;
            return;
        }
        double target = 118 * Math.pow(2, evIdx * evStep / 2.2);
        target = Math.max(25, Math.min(225, target));
        double ratio = target / Math.max(mean, 1);
        double factor = Math.pow(ratio, 1.6);
        factor = Math.max(0.5, Math.min(2.0, factor));
        if (Math.abs(Math.log(factor) / Math.log(2)) < 0.12) return;
        if (mi == null) {
            int nv = clampIso((int) Math.round(curIso * factor));
            if (nv == curIso) return;
            curIso = nv;
        } else {
            long nv = clampExp((long) (curExp * factor), true);
            if (nv == curExp) return;
            curExp = nv;
        }
        aeSkip = 3;
        updatePreview();
    }

    // ================= 拍照 =================

    private static class Pending {
        int orientation;
        boolean wantRaw;
        boolean jpegDone, rawDone;
        TotalCaptureResult result;
        Image rawImage;
        String name;
    }

    public void capture(final int deviceOrientation) {
        Handler h = cam;
        if (h == null) {
            ui.post(L::onCaptureDone);
            return;
        }
        h.post(() -> doCapture(deviceOrientation));
    }

    private void doCapture(int deviceOrientation) {
        if (sess == null || dev == null || pending != null) {
            ui.post(L::onCaptureDone);
            return;
        }
        int jo = (sensorOrientation + deviceOrientation) % 360;
        final Pending p = new Pending();
        p.orientation = jo;
        p.wantRaw = rawActive;
        p.name = "PRO_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        try {
            CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(previewSurf);
            b.addTarget(jpegR.getSurface());
            if (rawActive) {
                b.addTarget(rawR.getSurface());
                b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                        CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON);
            }
            apply(b, true);
            b.set(CaptureRequest.JPEG_ORIENTATION, jo);
            pending = p;
            sess.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest rq, TotalCaptureResult r) {
                    p.result = r;
                    tryRaw(p);
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest rq, CaptureFailure f) {
                    msg("拍攝失敗，請再試一次");
                    finish(p);
                }
            }, cam);
            long timeoutMs = (isManualExposure() ? curExp / 1_000_000L : 0) + 8000;
            cam.postDelayed(() -> {
                if (pending == p) finish(p);
            }, timeoutMs);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            pending = null;
            msg("拍攝失敗：" + e.getMessage());
            ui.post(L::onCaptureDone);
        }
    }

    private void onJpeg(ImageReader r) {
        Image img = null;
        byte[] bytes;
        try {
            img = r.acquireNextImage();
            if (img == null) return;
            ByteBuffer b = img.getPlanes()[0].getBuffer();
            bytes = new byte[b.remaining()];
            b.get(bytes);
        } catch (IllegalStateException e) {
            return;
        } finally {
            if (img != null) img.close();
        }
        final Pending p = pending;
        final String name = (p != null ? p.name : "PRO_" + System.currentTimeMillis()) + ".jpg";
        final byte[] data = bytes;
        Handler sv = saver;
        if (sv == null) return;
        sv.post(() -> {
            Uri u = save(name, "image/jpeg", data, null);
            if (u != null) ui.post(() -> L.onSaved(u, false));
            Handler h = cam;
            if (p != null && h != null) h.post(() -> {
                p.jpegDone = true;
                checkDone(p);
            });
        });
    }

    private void onRaw(ImageReader r) {
        Image img;
        try {
            img = r.acquireNextImage();
        } catch (IllegalStateException e) {
            return;
        }
        if (img == null) return;
        Pending p = pending;
        if (p == null || p.rawImage != null) {
            img.close();
            return;
        }
        p.rawImage = img;
        tryRaw(p);
    }

    private void tryRaw(final Pending p) {
        if (!p.wantRaw || p.rawImage == null || p.result == null || p.rawDone) return;
        p.rawDone = true;
        final Image img = p.rawImage;
        final TotalCaptureResult res = p.result;
        Handler sv = saver;
        if (sv == null) {
            img.close();
            return;
        }
        sv.post(() -> {
            try {
                DngCreator dc = new DngCreator(ch, res);
                dc.setOrientation(exifOrientation(p.orientation));
                Uri u = save(p.name + ".dng", "image/x-adobe-dng", null, dc, img);
                dc.close();
                if (u != null) ui.post(() -> L.onSaved(u, true));
            } catch (Exception e) {
                msg("RAW 儲存失敗：" + e.getMessage());
            } finally {
                try {
                    img.close();
                } catch (Exception ignored) {
                }
            }
            Handler h = cam;
            if (h != null) h.post(() -> checkDone(p));
        });
    }

    private void checkDone(Pending p) {
        if (pending != p) return;
        if (p.jpegDone && (!p.wantRaw || p.rawDone)) finish(p);
    }

    private void finish(Pending p) {
        if (pending == p) pending = null;
        if (p.rawImage != null && !p.rawDone) {
            try {
                p.rawImage.close();
            } catch (Exception ignored) {
            }
        }
        ui.post(L::onCaptureDone);
    }

    private static int exifOrientation(int deg) {
        switch (deg) {
            case 90:  return ExifInterface.ORIENTATION_ROTATE_90;
            case 180: return ExifInterface.ORIENTATION_ROTATE_180;
            case 270: return ExifInterface.ORIENTATION_ROTATE_270;
            default:  return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private Uri save(String name, String mime, byte[] data, DngCreator dc) {
        return save(name, mime, data, dc, null);
    }

    /** 存到「相簿 / DCIM/ProCam」 */
    private Uri save(String name, String mime, byte[] data, DngCreator dc, Image rawImg) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, mime);
        v.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/ProCam");
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri u = null;
        try {
            u = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (u == null) throw new IllegalStateException("無法建立檔案");
            try (OutputStream os = resolver.openOutputStream(u)) {
                if (os == null) throw new IllegalStateException("無法寫入檔案");
                if (dc != null) dc.writeImage(os, rawImg);
                else os.write(data);
            }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(u, v, null, null);
            return u;
        } catch (Exception e) {
            if (u != null) {
                try {
                    resolver.delete(u, null, null);
                } catch (Exception ignored) {
                }
            }
            msg("儲存失敗：" + e.getMessage());
            return null;
        }
    }

    private void msg(final String s) {
        ui.post(() -> L.onMessage(s));
    }
}
