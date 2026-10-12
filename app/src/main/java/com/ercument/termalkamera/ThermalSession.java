package com.ercument.termalkamera;

import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.util.Locale;

/**
 * Kamera akışını açar (UVC probe/commit + ISO alt ayar), kare yakalar ve
 * 256x384 karenin alt yarısından sıcaklık hesaplar.
 * Varsayım (InfiRay/Topdon tipi): alt yarı = 256x192 adet 16-bit LE değer, °C = değer/64 − 273.15
 */
final class ThermalSession {

    interface Log { void l(String s); }

    static { System.loadLibrary("uvciso"); }

    static native int grab(int fd, int ep, int pktSize, byte[] out, int timeoutMs, int[] stats);

    static final int W = 256, H = 192;
    static final int XU_UNIT = 4;

    private final UsbManager usb;
    private final UsbDevice dev;
    private final Log log;

    private UsbDeviceConnection conn;
    private UsbInterface vc, vs0, vsAlt;
    private int ep, pkt, frameSize;
    private final int frameIndex;

    ThermalSession(UsbManager usb, UsbDevice dev, int frameIndex, Log log) {
        this.usb = usb;
        this.dev = dev;
        this.frameIndex = frameIndex;
        this.log = log;
    }

    int frameSize() { return frameSize; }

    boolean open() { return open(true); }

    /** stream=false: sadece claim + PROBE/COMMIT (akış testi için) */
    boolean open(boolean stream) {
        conn = usb.openDevice(dev);
        if (conn == null) { log.l("!! openDevice() boş döndü"); return false; }

        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface f = dev.getInterface(i);
            if (f.getInterfaceClass() != 14) continue;
            if (f.getInterfaceSubclass() == 1 && vc == null) vc = f;
            if (f.getInterfaceSubclass() == 2 && f.getAlternateSetting() == 0) vs0 = f;
        }
        if (vc == null || vs0 == null) { log.l("!! VideoControl/VideoStreaming arayüzü bulunamadı"); return false; }
        log.l("claim VC: " + conn.claimInterface(vc, true) + "   claim VS: " + conn.claimInterface(vs0, true));

        xuDump();

        if (!probeCommit()) return false;
        if (!stream) return true;
        int ifn = vs0.getId();
        int need = lastPayload;

        // Paket boyu yetecek en küçük alt ayarı seç
        UsbInterface best = null, largest = null;
        int bestSz = Integer.MAX_VALUE, largestSz = 0;
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface f = dev.getInterface(i);
            if (f.getId() != ifn || f.getAlternateSetting() == 0 || f.getEndpointCount() == 0) continue;
            UsbEndpoint e = f.getEndpoint(0);
            int mp = e.getMaxPacketSize();
            int sz = (mp & 0x7FF) * (((mp >> 11) & 3) + 1);
            if (sz > largestSz) { largestSz = sz; largest = f; }
            if (sz >= need && sz < bestSz) { bestSz = sz; best = f; }
            if (f.getAlternateSetting() == forceAlt) { best = f; bestSz = -1; break; }
        }
        vsAlt = best != null ? best : largest;
        if (vsAlt == null) { log.l("!! ISO alt ayar bulunamadı"); return false; }
        UsbEndpoint e = vsAlt.getEndpoint(0);
        int mp = e.getMaxPacketSize();
        pkt = (mp & 0x7FF) * (((mp >> 11) & 3) + 1);
        ep = e.getAddress();
        boolean ok = conn.setInterface(vsAlt);
        log.l(String.format(Locale.US, "Akış: alt=%d uç=0x%02x paket=%d  setInterface=%b", vsAlt.getAlternateSetting(), ep, pkt, ok));
        return ok;
    }

    int forceAlt = -1;
    private int lastPayload;

    /** UVC PROBE + COMMIT: format #1, istenen kare, 25 fps */
    boolean probeCommit() {
        int ifn = vs0.getId();
        byte[] p = new byte[26];
        put16(p, 0, 1);              // bmHint: kare aralığı sabit
        p[2] = 1;                    // format #1 (YUY2)
        p[3] = (byte) frameIndex;    // kare #2 = 256x384
        put32(p, 4, 400000);         // 25 fps
        int r1 = conn.controlTransfer(0x21, 0x01, 0x0100, ifn, p, p.length, 1000);
        byte[] g = new byte[26];
        int r2 = conn.controlTransfer(0xA1, 0x81, 0x0100, ifn, g, g.length, 1000);
        log.l(String.format(Locale.US, "PROBE set=%d get=%d → format #%d kare #%d  kare boyu=%d  paket boyu=%d",
                r1, r2, g[2] & 0xFF, g[3] & 0xFF, u32(g, 18), u32(g, 22)));
        if (r2 < 26) { log.l("!! PROBE okunamadı"); return false; }
        if ((g[3] & 0xFF) != frameIndex) log.l("! Kamera istenen kareyi kabul etmedi (istenen #" + frameIndex + ")");
        int r3 = conn.controlTransfer(0x21, 0x01, 0x0200, ifn, g, g.length, 1000);
        log.l("COMMIT=" + r3);
        frameSize = (int) u32(g, 18);
        if (frameSize <= 0) frameSize = W * (frameIndex == 2 ? 2 * H : H) * 2;
        lastPayload = (int) u32(g, 22);
        return true;
    }

    /** Tüm ISO alt ayarlarını sırayla dener, her birinde gelen veriyi raporlar. En iyi alt'ı döner (-1 yoksa). */
    int sweep(int ms) {
        int ifn = vs0.getId();
        int bestAlt = -1, bestBytes = 0;
        for (int a = 7; a >= 1; a--) {
            UsbInterface f = null;
            for (int i = 0; i < dev.getInterfaceCount(); i++) {
                UsbInterface x = dev.getInterface(i);
                if (x.getId() == ifn && x.getAlternateSetting() == a && x.getEndpointCount() > 0) f = x;
            }
            if (f == null) continue;
            try { conn.setInterface(vs0); Thread.sleep(100); } catch (Throwable ignored) {}
            probeCommitQuiet();
            UsbEndpoint e = f.getEndpoint(0);
            int mp = e.getMaxPacketSize();
            int sz = (mp & 0x7FF) * (((mp >> 11) & 3) + 1);
            boolean ok = conn.setInterface(f);
            byte[] buf = new byte[frameSize];
            for (int k = 0; k < stats.length; k++) stats[k] = 0;
            int n = grab(conn.getFileDescriptor(), e.getAddress(), sz, buf, ms, stats);
            log.l(String.format(Locale.US, "  alt=%d paket=%d set=%b → %s  %s",
                    a, sz, ok, n > 0 ? "TAM KARE ✓" : "kare yok", statsString()));
            if (stats[11] > bestBytes) { bestBytes = stats[11]; bestAlt = a; }
        }
        try { conn.setInterface(vs0); } catch (Throwable ignored) {}
        return bestAlt;
    }

    private void probeCommitQuiet() {
        int ifn = vs0.getId();
        byte[] p = new byte[26];
        put16(p, 0, 1); p[2] = 1; p[3] = (byte) frameIndex; put32(p, 4, 400000);
        conn.controlTransfer(0x21, 0x01, 0x0100, ifn, p, p.length, 1000);
        byte[] g = new byte[26];
        if (conn.controlTransfer(0xA1, 0x81, 0x0100, ifn, g, g.length, 1000) == 26) {
            conn.controlTransfer(0x21, 0x01, 0x0200, ifn, g, g.length, 1000);
        }
    }

    String statsString() {
        return String.format(Locale.US,
                "paket=%d veri=%d boş=%d sadeceBaşlık=%d hatalıPaket=%d (kod %d/%d) urbHata=%d(kod %d) kötüBaşlık=%d EOF=%d yanlışBoy=%d(son %d) gelen=%dKB enUzun=%d",
                stats[0], stats[1], stats[9], stats[10], stats[8], stats[12], stats[13], stats[5], stats[14],
                stats[6], stats[7], stats[3], stats[4], stats[11], stats[15]);
    }

    /** Üreticiye özel birimin (XU) kontrollerini sadece OKUR (yazma yok). */
    private void xuDump() {
        int idx = (XU_UNIT << 8) | vc.getId();
        for (int sel = 1; sel <= 3; sel++) {
            byte[] info = new byte[1];
            int ri = conn.controlTransfer(0xA1, 0x86, sel << 8, idx, info, 1, 500);
            byte[] ln = new byte[2];
            int rl = conn.controlTransfer(0xA1, 0x85, sel << 8, idx, ln, 2, 500);
            int len = rl == 2 ? ((ln[0] & 0xFF) | (ln[1] & 0xFF) << 8) : 0;
            String cur = "-";
            if (len > 0 && len <= 512) {
                byte[] c = new byte[len];
                int rc = conn.controlTransfer(0xA1, 0x81, sel << 8, idx, c, len, 500);
                cur = rc > 0 ? hex(c, Math.min(rc, 48)) : ("hata " + rc);
            }
            log.l(String.format(Locale.US, "XU sel=%d info=%s uzunluk=%d değer=%s",
                    sel, ri == 1 ? String.format(Locale.US, "0x%02x", info[0]) : "yok", len, cur));
        }
    }

    final int[] stats = new int[16];

    byte[] grabFrame(int timeoutMs) {
        byte[] f = new byte[frameSize];
        int n = grab(conn.getFileDescriptor(), ep, pkt, f, timeoutMs, stats);
        if (n > 0) return f;
        log.l("!! Kare alınamadı (sonuç=" + n + ") " + statsString());
        return null;
    }

    void close() {
        if (conn == null) return;
        try { if (vs0 != null) conn.setInterface(vs0); } catch (Throwable ignored) {}
        try { if (vs0 != null) conn.releaseInterface(vs0); } catch (Throwable ignored) {}
        try { if (vc != null) conn.releaseInterface(vc); } catch (Throwable ignored) {}
        conn.close();
        conn = null;
    }

    // ---------------- Kare çözümleme ----------------

    static final class Result {
        double centerC, minC, maxC, avgC;
        int centerRaw;
        int minX, minY, maxX, maxY;
        String halves;
        Bitmap image, thermal;
    }

    static Result analyze(byte[] f) {
        Result r = new Result();
        int half = W * H * 2;
        r.image = yBitmap(f, 0);
        StringBuilder hs = new StringBuilder();
        hs.append(halfStats("üst yarı", f, 0)).append('\n');
        if (f.length >= 2 * half) {
            hs.append(halfStats("alt yarı", f, half));
            double[] t = new double[W * H];
            double mn = 1e9, mx = -1e9, sum = 0;
            for (int i = 0; i < W * H; i++) {
                int o = half + 2 * i;
                int v = (f[o] & 0xFF) | (f[o + 1] & 0xFF) << 8;
                double c = v / 64.0 - 273.15;
                t[i] = c;
                sum += c;
                if (c < mn) { mn = c; r.minX = i % W; r.minY = i / W; }
                if (c > mx) { mx = c; r.maxX = i % W; r.maxY = i / W; }
            }
            r.minC = mn; r.maxC = mx; r.avgC = sum / (W * H);
            int cx = W / 2, cy = H / 2;
            double cs = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) cs += t[(cy + dy) * W + cx + dx];
            r.centerC = cs / 9;
            int o = half + 2 * (cy * W + cx);
            r.centerRaw = (f[o] & 0xFF) | (f[o + 1] & 0xFF) << 8;
            r.thermal = heatBitmap(t, mn, mx);
        } else {
            r.centerC = Double.NaN;
        }
        r.halves = hs.toString();
        return r;
    }

    /** Bir yarının 16-bit istatistikleri: görüntü mü sıcaklık mı ayırt etmek için. */
    private static String halfStats(String name, byte[] f, int off) {
        int n = W * H;
        long sumLo = 0, sumHi = 0;
        int vmin = 65535, vmax = 0;
        for (int i = 0; i < n; i++) {
            int lo = f[off + 2 * i] & 0xFF, hi = f[off + 2 * i + 1] & 0xFF;
            sumLo += lo; sumHi += hi;
            int v = lo | hi << 8;
            if (v < vmin) vmin = v;
            if (v > vmax) vmax = v;
        }
        return String.format(Locale.US, "%s: ort.düşük byte=%.1f ort.yüksek byte=%.1f  16bit min=%d max=%d  (÷64−273: %.1f … %.1f °C)",
                name, sumLo / (double) n, sumHi / (double) n, vmin, vmax, vmin / 64.0 - 273.15, vmax / 64.0 - 273.15);
    }

    private static Bitmap yBitmap(byte[] f, int off) {
        int[] px = new int[W * H];
        for (int i = 0; i < W * H; i++) {
            int y = f[off + 2 * i] & 0xFF;
            px[i] = 0xFF000000 | y << 16 | y << 8 | y;
        }
        return Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888);
    }

    private static Bitmap heatBitmap(double[] t, double mn, double mx) {
        int[] px = new int[W * H];
        double span = Math.max(0.1, mx - mn);
        for (int i = 0; i < W * H; i++) px[i] = iron((t[i] - mn) / span);
        // merkez artı işareti
        int cx = W / 2, cy = H / 2;
        for (int d = -6; d <= 6; d++) {
            if (Math.abs(d) < 2) continue;
            px[cy * W + cx + d] = 0xFF00FF00;
            px[(cy + d) * W + cx] = 0xFF00FF00;
        }
        return Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888);
    }

    /** Siyah → mor → kırmızı → turuncu → sarı → beyaz */
    private static int iron(double x) {
        if (x < 0) x = 0;
        if (x > 1) x = 1;
        double[][] s = {{0, 0, 0}, {80, 0, 140}, {200, 30, 60}, {250, 120, 0}, {255, 220, 40}, {255, 255, 255}};
        double p = x * (s.length - 1);
        int k = Math.min((int) p, s.length - 2);
        double u = p - k;
        int r = (int) (s[k][0] + (s[k + 1][0] - s[k][0]) * u);
        int g = (int) (s[k][1] + (s[k + 1][1] - s[k][1]) * u);
        int b = (int) (s[k][2] + (s[k + 1][2] - s[k][2]) * u);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    // ---------------- Yardımcılar ----------------

    static void put16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }

    static void put32(byte[] b, int o, int v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24);
    }

    static long u32(byte[] b, int o) {
        return ((b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | ((long) (b[o + 3] & 0xFF)) << 24);
    }

    static String hex(byte[] b, int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append(String.format(Locale.US, "%02x ", b[i] & 0xFF));
        return s.toString().trim();
    }
}
