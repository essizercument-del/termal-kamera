package com.ercument.termalkamera;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

/**
 * Termal kamera tanı uygulaması.
 * Amaç: USB izninin neden reddedildiğini ve kameranın hangi görüntü formatlarını
 * gönderdiğini (ham sıcaklık verisi var mı?) ekrana yazmak. Sonuç "Paylaş" ile gönderilir.
 */
public class TaniActivity extends Activity {

    static final String ACTION_USB_PERMISSION = "com.ercument.termalkamera.USB_PERMISSION";
    static final int FLUKE_VID = 0x0f7e;
    static final int REQ_CAMERA = 11;

    private UsbManager usb;
    private TextView logView;
    private ScrollView scroll;
    private final StringBuilder log = new StringBuilder();
    private long usbRequestTime;
    private boolean receiverRegistered;
    private TextView tempView, statView;
    private ImageView imgThermal, imgImage;
    private Button liveBtn;
    private volatile boolean live, busy;
    private volatile int workingAlt = -1;
    private final SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            try {
                if (!ACTION_USB_PERMISSION.equals(i.getAction())) return;
                long dt = SystemClock.elapsedRealtime() - usbRequestTime;
                UsbDevice d = deviceExtra(i);
                boolean hasExtra = i.hasExtra(UsbManager.EXTRA_PERMISSION_GRANTED);
                boolean granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                line("── USB İZİN CEVABI (" + dt + " ms sonra) ──");
                line("  cevapta izin bilgisi var mı: " + hasExtra + "   değer: " + (granted ? "VERİLDİ" : "REDDEDİLDİ"));
                line("  cevaptaki cihaz: " + (d == null ? "YOK" : devName(d)));
                UsbDevice t = d != null ? d : findTarget();
                if (t == null) return;
                boolean real = usb.hasPermission(t);
                line("  gerçek durum usb.hasPermission(): " + (real ? "İZİN VAR" : "İZİN YOK"));
                if (real && !granted) {
                    line("  ! Cevap 'red' diyor ama izin aslında VAR → PendingIntent bayrak hatası (önceki kodda büyük ihtimalle bu vardı).");
                }
                if (!real && dt < 500) {
                    line("  ! Pencere gösterilmeden " + dt + " ms içinde reddedildi → sistem kendiliğinden reddetti. Genelde CAMERA izni eksikse olur.");
                }
                if (real) {
                    line("  ✓ USB izni alındı, kamera okunuyor...");
                    openAndRead();
                }
            } catch (Throwable e) {
                err("izin cevabı", e);
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        buildUi();
        registerUsbReceiver();
        header();
        scan();
        handleIntent(getIntent(), "açılış");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent, "yeni olay");
    }

    @Override
    protected void onDestroy() {
        live = false;
        if (receiverRegistered) {
            try { unregisterReceiver(usbReceiver); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    // ---------------- Arayüz ----------------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(8);
        root.setPadding(p, p, p, p);

        TextView hint = new TextView(this);
        hint.setText("Sıra: 1 → 2 → 3 → 4. Bitince \"Paylaş\" ile sonucu gönder.");
        hint.setTextSize(13);
        root.addView(hint);

        LinearLayout r1 = row(), r2 = row(), r3 = row();
        addBtn(r1, "1. Tara", v -> scan());
        addBtn(r1, "2. Kamera izni", v -> requestCameraPerm());
        addBtn(r1, "3. USB izni", v -> requestUsbPerm());
        addBtn(r2, "4. Aç ve oku", v -> openAndRead());
        addBtn(r2, "Paylaş", v -> share());
        addBtn(r2, "Kopyala", v -> copy());
        addBtn(r3, "5. Sıcaklık oku", v -> readTempOnce());
        liveBtn = addBtn(r3, "6. Canlı ▶", v -> toggleLive());
        addBtn(r3, "7. Akış testi", v -> streamTest());
        root.addView(r1);
        root.addView(r2);
        root.addView(r3);

        tempView = new TextView(this);
        tempView.setTextSize(30);
        tempView.setTypeface(Typeface.DEFAULT_BOLD);
        tempView.setText("Merkez: — °C");
        root.addView(tempView);
        statView = new TextView(this);
        statView.setTextSize(13);
        root.addView(statView);

        LinearLayout imgs = row();
        imgThermal = new ImageView(this);
        imgThermal.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imgImage = new ImageView(this);
        imgImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imgs.addView(imgThermal, new LinearLayout.LayoutParams(0, dp(140), 1f));
        imgs.addView(imgImage, new LinearLayout.LayoutParams(0, dp(140), 1f));
        root.addView(imgs);

        scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private Button addBtn(LinearLayout parent, String text, View.OnClickListener cl) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(13);
        btn.setOnClickListener(v -> {
            try { cl.onClick(v); } catch (Throwable e) { err(text, e); }
        });
        parent.addView(btn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return btn;
    }

    /** Arka plan iş parçacığından güvenli log */
    private void ui(String s) { runOnUiThread(() -> line(s)); }

    // ---------------- 5/6. Sıcaklık okuma ----------------

    private void readTempOnce() {
        if (busy) { line("Meşgul, bekle..."); return; }
        UsbDevice t = findTarget();
        if (t == null || !usb.hasPermission(t)) { line("Sıcaklık oku: cihaz yok ya da USB izni yok."); return; }
        busy = true;
        line("── SICAKLIK OKUMA (256x384 modu) ──");
        new Thread(() -> {
            ThermalSession s = new ThermalSession(usb, t, 2, this::ui);
            s.forceAlt = workingAlt;
            try {
                if (s.open()) {
                    byte[] f = null;
                    for (int k = 0; k < 3 && f == null; k++) f = s.grabFrame(3000);
                    if (f != null) {
                        ThermalSession.Result r = ThermalSession.analyze(f);
                        ui("Kare alındı: " + f.length + " byte");
                        for (String h : r.halves.split("\n")) ui("  " + h);
                        ui(String.format(Locale.US, "  MERKEZ: %.1f °C (ham=%d)   min=%.1f (%d,%d)  maks=%.1f (%d,%d)  ort=%.1f",
                                r.centerC, r.centerRaw, r.minC, r.minX, r.minY, r.maxC, r.maxX, r.maxY, r.avgC));
                        show(r);
                    }
                }
            } catch (Throwable e) {
                ui("!! HATA (sıcaklık): " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                s.close();
                busy = false;
                ui("Okuma bitti. Kameranın gösterdiği yer için Fluke/başka termometreyle karşılaştır.");
            }
        }).start();
    }

    /** Tüm hız modlarını (alt 7→1) iki kare boyutunda dener; hangisinde veri geldiğini raporlar. */
    private void streamTest() {
        if (busy) { line("Meşgul, bekle..."); return; }
        UsbDevice t = findTarget();
        if (t == null || !usb.hasPermission(t)) { line("Akış testi: cihaz yok ya da USB izni yok."); return; }
        busy = true;
        line("── AKIŞ TESTİ (yaklaşık 20 sn sürer) ──");
        new Thread(() -> {
            int[] frames = {2, 1};
            int found = -1, foundFrame = -1;
            for (int fi : frames) {
                ui("Kare #" + fi + (fi == 2 ? " (256x384)" : " (256x192)") + ":");
                ThermalSession s = new ThermalSession(usb, t, fi, this::ui);
                try {
                    if (s.open(false)) {
                        int a = s.sweep(1200);
                        if (a > 0 && found < 0) { found = a; foundFrame = fi; }
                    }
                } catch (Throwable e) {
                    ui("!! HATA (akış testi): " + e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    s.close();
                }
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }
            if (found > 0) {
                workingAlt = found;
                ui("✓ Veri gelen ilk mod: kare #" + foundFrame + " alt=" + found + ". 5/6 numaralı butonlar artık bu alt'ı kullanacak.");
            } else {
                ui("✗ Hiçbir modda veri gelmedi. Bu sonucu olduğu gibi gönder.");
            }
            busy = false;
            ui("AKIŞ TESTİ BİTTİ. \"Paylaş\" ile gönder.");
        }).start();
    }

    private void toggleLive() {
        if (live) { live = false; return; }
        if (busy) { line("Meşgul, bekle..."); return; }
        UsbDevice t = findTarget();
        if (t == null || !usb.hasPermission(t)) { line("Canlı: cihaz yok ya da USB izni yok."); return; }
        live = true;
        busy = true;
        liveBtn.setText("6. Canlı ■");
        line("── CANLI MOD başladı (durdurmak için tekrar bas) ──");
        new Thread(() -> {
            ThermalSession s = new ThermalSession(usb, t, 2, this::ui);
            s.forceAlt = workingAlt;
            int ok = 0, fail = 0;
            try {
                if (s.open()) {
                    while (live) {
                        s.heartbeatIfDue();
                        byte[] f = s.grabFrame(2000);
                        if (f == null) { if (++fail >= 5) { ui("!! 5 kare üst üste alınamadı, durduruldu."); break; } continue; }
                        ok++;
                        show(ThermalSession.analyze(f));
                    }
                }
            } catch (Throwable e) {
                ui("!! HATA (canlı): " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                s.close();
                live = false;
                busy = false;
                int fOk = ok, fFail = fail;
                runOnUiThread(() -> {
                    liveBtn.setText("6. Canlı ▶");
                    line("Canlı mod bitti. Başarılı kare=" + fOk + "  başarısız=" + fFail);
                });
            }
        }).start();
    }

    private void show(ThermalSession.Result r) {
        runOnUiThread(() -> {
            if (Double.isNaN(r.centerC)) {
                tempView.setText("Sıcaklık verisi yok");
            } else {
                tempView.setText(String.format(Locale.US, "Merkez: %.1f °C", r.centerC));
                statView.setText(String.format(Locale.US, "min %.1f   maks %.1f   ort %.1f   (ham %d)",
                        r.minC, r.maxC, r.avgC, r.centerRaw));
            }
            if (r.thermal != null) imgThermal.setImageBitmap(r.thermal);
            if (r.image != null) imgImage.setImageBitmap(r.image);
        });
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void line(String s) {
        log.append(tf.format(new Date())).append("  ").append(s).append('\n');
        logView.setText(log);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void err(String where, Throwable e) {
        line("!! HATA (" + where + "): " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    // ---------------- Bilgi ----------------

    private void header() {
        String ver = "?";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) {}
        line("Termal Kamera Tanı  sürüm " + ver);
        line("Telefon: " + Build.MANUFACTURER + " " + Build.MODEL + "  (" + Build.DEVICE + ")");
        line("Android " + Build.VERSION.RELEASE + "  (API " + Build.VERSION.SDK_INT + ")  hedef API " + getApplicationInfo().targetSdkVersion);
        String os = sysProp("ro.build.version.opporom");
        if (os.isEmpty()) os = sysProp("ro.build.version.oplusrom");
        if (!os.isEmpty()) line("ColorOS: " + os);
        line("USB host desteği: " + getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST)
                + "   harici kamera özelliği: " + getPackageManager().hasSystemFeature("android.hardware.camera.external"));
        line("Kamera (CAMERA) izni: " + (camGranted() ? "VAR" : "YOK"));
    }

    private String sysProp(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object v = c.getMethod("get", String.class).invoke(null, key);
            return v == null ? "" : v.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    private boolean camGranted() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    // ---------------- 1. Tarama ----------------

    private void scan() {
        HashMap<String, UsbDevice> m = usb.getDeviceList();
        line("── USB TARAMA ── bulunan cihaz: " + m.size());
        if (m.isEmpty()) {
            line("  Hiç USB cihaz yok. Kamera takılı mı? Oppo'da Ayarlar → Ek ayarlar → OTG bağlantısı AÇIK olmalı (10 dk sonra kendiliğinden kapanır).");
        }
        for (UsbDevice d : m.values()) describe(d);
        UsbDevice t = findTarget();
        if (t != null) line("Hedef cihaz: " + devName(t) + (t.getVendorId() == FLUKE_VID ? "  ← Fluke" : ""));
        camera2Check();
    }

    private void describe(UsbDevice d) {
        line("CİHAZ " + devName(d));
        String mf = "?", pr = "?";
        try { mf = String.valueOf(d.getManufacturerName()); } catch (Throwable ignored) {}
        try { pr = String.valueOf(d.getProductName()); } catch (Throwable ignored) {}
        line("  üretici: " + mf + "   ürün: " + pr);
        line("  cihaz sınıfı: " + d.getDeviceClass() + "/" + d.getDeviceSubclass() + "/" + d.getDeviceProtocol()
                + "   izin: " + (usb.hasPermission(d) ? "VAR" : "YOK"));
        for (int k = 0; k < d.getInterfaceCount(); k++) {
            UsbInterface f = d.getInterface(k);
            line(String.format(Locale.US, "  arayüz #%d alt=%d sınıf=%d/%d/%d %s  uç nokta=%d",
                    f.getId(), f.getAlternateSetting(), f.getInterfaceClass(), f.getInterfaceSubclass(),
                    f.getInterfaceProtocol(), UvcParser.ifaceName(f.getInterfaceClass(), f.getInterfaceSubclass()),
                    f.getEndpointCount()));
            for (int e = 0; e < f.getEndpointCount(); e++) {
                UsbEndpoint ep = f.getEndpoint(e);
                line(String.format(Locale.US, "     uç 0x%02x %s %s paket=%d",
                        ep.getAddress(), ep.getDirection() == UsbConstants.USB_DIR_IN ? "IN " : "OUT",
                        UvcParser.epType(ep.getType()), ep.getMaxPacketSize()));
            }
        }
    }

    private void camera2Check() {
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String[] ids = cm.getCameraIdList();
            StringBuilder s = new StringBuilder();
            boolean ext = false;
            for (String id : ids) {
                Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                String n = f == null ? "?" : f == CameraCharacteristics.LENS_FACING_FRONT ? "ön"
                        : f == CameraCharacteristics.LENS_FACING_BACK ? "arka" : "HARİCİ";
                if (f != null && f == CameraCharacteristics.LENS_FACING_EXTERNAL) ext = true;
                s.append(id).append('=').append(n).append("  ");
            }
            line("Camera2 kameraları: " + s);
            if (ext) line("  ✓ Harici kamera Camera2'de görünüyor → USB kamera normal kamera API'siyle de açılabilir.");
        } catch (Throwable e) {
            err("Camera2", e);
        }
    }

    // ---------------- 2. CAMERA izni ----------------

    private void requestCameraPerm() {
        if (camGranted()) {
            line("Kamera izni zaten VAR. Sıradaki adım: 3. USB izni");
            return;
        }
        line("Kamera izni isteniyor...");
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_CAMERA) return;
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        line("Kamera izni sonucu: " + (ok ? "VERİLDİ. Sıradaki adım: 3. USB izni" : "REDDEDİLDİ"));
    }

    // ---------------- 3. USB izni ----------------

    private void registerUsbReceiver() {
        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, f);
        }
        receiverRegistered = true;
    }

    private void requestUsbPerm() {
        UsbDevice t = findTarget();
        if (t == null) {
            line("USB izni: cihaz bulunamadı. Önce kamerayı tak ve 1. Tara'ya bas.");
            return;
        }
        if (usb.hasPermission(t)) {
            line("USB izni zaten VAR. Sıradaki adım: 4. Aç ve oku");
            return;
        }
        boolean cam = camGranted();
        line("Kamera izni durumu: " + (cam ? "VAR" : "YOK  ← UYARI: önce 2. adımı yap, yoksa sistem reddedebilir"));
        Intent i = new Intent(ACTION_USB_PERMISSION);
        i.setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, i, flags);
        line("USB izni isteniyor → " + devName(t) + "  (izin cevabı " + (Build.VERSION.SDK_INT >= 31 ? "MUTABLE" : "standart") + " PendingIntent ile)");
        line("  Ekranda izin penceresi çıkarsa \"Tamam/İzin ver\" de.");
        usbRequestTime = SystemClock.elapsedRealtime();
        usb.requestPermission(t, pi);
    }

    // ---------------- 4. Aç ve oku ----------------

    private void openAndRead() {
        UsbDevice t = findTarget();
        if (t == null) {
            line("Aç ve oku: cihaz yok.");
            return;
        }
        if (!usb.hasPermission(t)) {
            line("Aç ve oku: USB izni YOK. Önce 2 ve 3. adımları yap.");
            return;
        }
        UsbDeviceConnection c = usb.openDevice(t);
        if (c == null) {
            line("!! openDevice() boş döndü: izin olmasına rağmen cihaz açılamadı (başka uygulama tutuyor olabilir).");
            return;
        }
        try {
            line("── KAMERA AÇILDI ── " + devName(t));
            try { line("  seri no: " + c.getSerial()); } catch (Throwable ignored) {}
            byte[] raw = c.getRawDescriptors();
            if (raw == null) {
                line("!! Ham descriptor okunamadı.");
                return;
            }
            line("  ham descriptor: " + raw.length + " byte");
            UvcParser.Result r = UvcParser.parse(raw);
            log.append(r.text);
            logView.setText(log);

            for (int ifNum : r.vsInterfaces) probe(c, ifNum);

            line("── YORUM ──");
            if (r.hints.isEmpty()) line("  (özel bir işaret bulunamadı)");
            for (String h : r.hints) line("  • " + h);

            line("── HAM HEX (bana gönderdiğinde ben de inceleyeceğim) ──");
            log.append(hex(raw)).append('\n');
            logView.setText(log);
            line("BİTTİ. Şimdi \"Paylaş\" ile sonucu gönder.");
        } catch (Throwable e) {
            err("aç ve oku", e);
        } finally {
            c.close();
        }
    }

    /** UVC VS_PROBE_CONTROL: kameranın şu anki/en büyük kare ayarını sorar. */
    private void probe(UsbDeviceConnection c, int ifNum) {
        int[] reqs = {0x81, 0x83};   // GET_CUR, GET_MAX
        String[] names = {"GET_CUR", "GET_MAX"};
        for (int k = 0; k < reqs.length; k++) {
            byte[] b = new byte[34];
            int n = c.controlTransfer(0xA1, reqs[k], 0x0100, ifNum, b, b.length, 1000);
            if (n < 26) {
                line(String.format(Locale.US, "  PROBE %s arayüz #%d → yanıt yok (%d)", names[k], ifNum, n));
                continue;
            }
            line(String.format(Locale.US, "  PROBE %s arayüz #%d → format #%d kare #%d  %s  maks kare=%d byte  maks paket=%d byte",
                    names[k], ifNum, UvcParser.u8(b, 2), UvcParser.u8(b, 3),
                    UvcParser.u32(b, 4) > 0 ? String.format(Locale.US, "%.1ffps", 1e7 / UvcParser.u32(b, 4)) : "?",
                    UvcParser.u32(b, 18), UvcParser.u32(b, 22)));
        }
    }

    // ---------------- Takma ile açılış ----------------

    private void handleIntent(Intent i, String src) {
        if (i == null || !UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) return;
        UsbDevice d = deviceExtra(i);
        line("── Uygulama kamera takılınca açıldı (" + src + ") ── " + (d == null ? "" : devName(d)));
        if (d != null && usb.hasPermission(d)) {
            line("  ✓ Takma onayı ile USB izni OTOMATİK verildi. Kamera okunuyor...");
            openAndRead();
        } else {
            line("  Takma ile açıldı ama izin yok. Açılan pencerede bu uygulamayı seçip onaylaman gerekiyor.");
        }
    }

    // ---------------- Yardımcılar ----------------

    private UsbDevice findTarget() {
        HashMap<String, UsbDevice> m = usb.getDeviceList();
        for (UsbDevice d : m.values()) if (d.getVendorId() == FLUKE_VID) return d;
        for (UsbDevice d : m.values()) {
            if (d.getDeviceClass() == 14 || d.getDeviceClass() == 0xEF) {
                for (int k = 0; k < d.getInterfaceCount(); k++) {
                    if (d.getInterface(k).getInterfaceClass() == 14) return d;
                }
            }
            for (int k = 0; k < d.getInterfaceCount(); k++) {
                if (d.getInterface(k).getInterfaceClass() == 14) return d;
            }
        }
        for (UsbDevice d : m.values()) return d;
        return null;
    }

    @SuppressWarnings("deprecation")
    private static UsbDevice deviceExtra(Intent i) {
        if (Build.VERSION.SDK_INT >= 33) return i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        return i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private static String devName(UsbDevice d) {
        return String.format(Locale.US, "VID=%04x PID=%04x (%s)", d.getVendorId(), d.getProductId(), d.getDeviceName());
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i % 16 == 0) s.append(String.format(Locale.US, "%n%04x: ", i));
            s.append(String.format(Locale.US, "%02x ", b[i] & 0xFF));
        }
        return s.toString();
    }

    private void share() {
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("text/plain");
        s.putExtra(Intent.EXTRA_SUBJECT, "Termal kamera tanı sonucu");
        s.putExtra(Intent.EXTRA_TEXT, log.toString());
        startActivity(Intent.createChooser(s, "Sonucu paylaş"));
    }

    private void copy() {
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("tani", log.toString()));
        Toast.makeText(this, "Kopyalandı", Toast.LENGTH_SHORT).show();
    }
}
