package com.ercument.termalkamera;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Canlı termal görüntü + merkez sıcaklığı. "ÖLÇÜMÜ AL" → sonucu çağıran ekrana döndürür.
 */
public class CameraActivity extends Activity {

    static final String EXTRA_TAG = "tag";
    static final String EXTRA_TEMP = "temp";
    static final String ACTION_USB_PERMISSION = "com.ercument.termalkamera.CAM_USB_PERMISSION";
    static final int REQ_CAMERA = 31;
    static volatile boolean active;

    private UsbManager usb;
    private ImageView img;
    private TextView tempView, statView, statusView;
    private Button takeBtn;
    private volatile boolean running;
    private volatile ThermalSession.Result last;
    private Thread worker;
    private long lastPermAsk;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi(getIntent().getStringExtra(EXTRA_TAG));
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private void buildUi(String tag) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0a0f1a"));
        int p = dp(12);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText(tag == null || tag.isEmpty() ? "Termal ölçüm" : "Tag: " + tag);
        title.setTextColor(Color.parseColor("#9fb3c8"));
        title.setTextSize(15);
        root.addView(title);

        img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setAdjustViewBounds(true);
        img.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        ip.topMargin = dp(8);
        root.addView(img, ip);

        tempView = new TextView(this);
        tempView.setText("— °C");
        tempView.setTextColor(Color.parseColor("#00e5ff"));
        tempView.setTextSize(56);
        tempView.setTypeface(Typeface.DEFAULT_BOLD);
        tempView.setGravity(Gravity.CENTER);
        root.addView(tempView);

        statView = new TextView(this);
        statView.setTextColor(Color.parseColor("#9fb3c8"));
        statView.setTextSize(14);
        statView.setGravity(Gravity.CENTER);
        root.addView(statView);

        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#ffd54f"));
        statusView.setTextSize(13);
        statusView.setGravity(Gravity.CENTER);
        statusView.setText("Kamera bekleniyor...");
        root.addView(statusView);

        takeBtn = new Button(this);
        takeBtn.setText("ÖLÇÜMÜ AL");
        takeBtn.setTextSize(22);
        takeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        takeBtn.setEnabled(false);
        takeBtn.setOnClickListener(v -> take());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72));
        tp.topMargin = dp(8);
        root.addView(takeBtn, tp);

        LinearLayout row = new LinearLayout(this);
        Button cancel = new Button(this);
        cancel.setText("İptal");
        cancel.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        Button tani = new Button(this);
        tani.setText("Tanı");
        tani.setOnClickListener(v -> startActivity(new Intent(this, TaniActivity.class)));
        row.addView(cancel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f));
        row.addView(tani, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);

        setContentView(root);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void take() {
        ThermalSession.Result r = last;
        if (r == null || Double.isNaN(r.centerC)) return;
        double t = Math.round(r.centerC * 10.0) / 10.0;
        Intent i = new Intent();
        i.putExtra(EXTRA_TEMP, t);
        setResult(RESULT_OK, i);
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        running = true;
        worker = new Thread(this::loop, "termal-akis");
        worker.start();
    }

    @Override
    protected void onPause() {
        running = false;
        active = false;
        if (worker != null) {
            try { worker.join(3000); } catch (InterruptedException ignored) {}
            worker = null;
        }
        super.onPause();
    }

    private void status(String s) { runOnUiThread(() -> statusView.setText(s)); }

    /** Kamera takılı/izinli olana kadar bekler, akışı açar, kopunca yeniden dener. */
    private void loop() {
        while (running) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                status("Kamera izni gerekiyor");
                SystemClock.sleep(500);
                continue;
            }
            UsbDevice d = findTarget();
            if (d == null) {
                status("Termal kamera takılı değil. Tak (OTG açık olmalı).");
                SystemClock.sleep(500);
                continue;
            }
            if (!usb.hasPermission(d)) {
                status("USB izni bekleniyor — açılan pencerede izin ver");
                askPermission(d);
                SystemClock.sleep(500);
                continue;
            }
            status("Kamera açılıyor...");
            ThermalSession s = new ThermalSession(usb, d, 2, msg -> { });
            int fail = 0;
            try {
                if (!s.open()) {
                    status("Kamera açılamadı, tekrar deneniyor...");
                    SystemClock.sleep(1000);
                    continue;
                }
                status("");
                while (running) {
                    s.heartbeatIfDue();
                    byte[] f = s.grabFrame(2000);
                    if (f == null) {
                        if (++fail >= 3) break;
                        status("Görüntü bekleniyor...");
                        continue;
                    }
                    fail = 0;
                    ThermalSession.Result r = ThermalSession.analyze(f);
                    if (Double.isNaN(r.centerC)) continue;
                    last = r;
                    runOnUiThread(() -> show(r));
                }
            } catch (Throwable e) {
                status("Hata: " + e.getMessage());
                SystemClock.sleep(1000);
            } finally {
                s.close();
            }
        }
    }

    private void show(ThermalSession.Result r) {
        tempView.setText(String.format(Locale.US, "%.1f °C", r.centerC));
        statView.setText(String.format(Locale.US, "görüntüde min %.1f  ·  maks %.1f °C", r.minC, r.maxC));
        if (r.thermal != null) img.setImageBitmap(r.thermal);
        if (!takeBtn.isEnabled()) takeBtn.setEnabled(true);
        if (statusView.getText().length() > 0) statusView.setText("");
    }

    private void askPermission(UsbDevice d) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastPermAsk < 8000) return;
        lastPermAsk = now;
        Intent i = new Intent(ACTION_USB_PERMISSION);
        i.setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        usb.requestPermission(d, PendingIntent.getBroadcast(this, 1, i, flags));
    }

    private UsbDevice findTarget() {
        for (UsbDevice d : usb.getDeviceList().values()) if (d.getVendorId() == TaniActivity.FLUKE_VID) return d;
        for (UsbDevice d : usb.getDeviceList().values()) {
            for (int k = 0; k < d.getInterfaceCount(); k++) if (d.getInterface(k).getInterfaceClass() == 14) return d;
        }
        return null;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
    }
}
