package com.ercument.termalkamera;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * USB ham descriptor'larını (getRawDescriptors) okuyup UVC video formatlarını,
 * çözünürlükleri, uç noktaları ve üreticiye özel birimleri (Extension Unit) çıkarır.
 */
final class UvcParser {

    static final class Result {
        final StringBuilder text = new StringBuilder();
        final List<String> hints = new ArrayList<>();
        final List<Integer> vsInterfaces = new ArrayList<>();
    }

    // Bilinen termal sensör çözünürlükleri (genişlik, yükseklik)
    private static final int[][] THERMAL_RES = {
            {80, 60}, {120, 90}, {160, 120}, {256, 192}, {384, 288}, {640, 480}, {640, 512}, {320, 240}
    };

    private UvcParser() {}

    static Result parse(byte[] d) {
        Result r = new Result();
        StringBuilder sb = r.text;

        int curIf = -1, curAlt = -1, curClass = -1, curSub = -1;
        String curFmt = "?";
        int curBpp = 0;
        boolean anyVsIso = false, anyVsBulk = false, anyMjpegOnly = true, anyFormat = false;
        boolean any16bit = false, anyXu = false;
        List<String> doubledHints = new ArrayList<>();

        int i = 0;
        while (i + 1 < d.length) {
            int len = u8(d, i);
            int type = u8(d, i + 1);
            if (len < 2 || i + len > d.length) {
                sb.append("! Bozuk descriptor, konum ").append(i).append('\n');
                break;
            }
            switch (type) {
                case 0x01: // DEVICE
                    if (len >= 12) {
                        sb.append(String.format(Locale.US, "DEVICE  USB %x.%02x  sınıf=%d  VID=%04x PID=%04x%n",
                                u8(d, i + 3), u8(d, i + 2), u8(d, i + 4), u16(d, i + 8), u16(d, i + 10)));
                    }
                    break;
                case 0x02: // CONFIGURATION
                    if (len >= 9) {
                        sb.append(String.format(Locale.US, "CONFIG  toplam=%d byte  arayüz sayısı=%d  maxGüç=%dmA%n",
                                u16(d, i + 2), u8(d, i + 4), u8(d, i + 8) * 2));
                    }
                    break;
                case 0x0B: // INTERFACE ASSOCIATION
                    if (len >= 8) {
                        sb.append(String.format(Locale.US, "IAD     ilk arayüz=%d  adet=%d  sınıf=%d/%d%n",
                                u8(d, i + 2), u8(d, i + 3), u8(d, i + 4), u8(d, i + 5)));
                    }
                    break;
                case 0x04: // INTERFACE
                    if (len >= 9) {
                        curIf = u8(d, i + 2);
                        curAlt = u8(d, i + 3);
                        curClass = u8(d, i + 5);
                        curSub = u8(d, i + 6);
                        sb.append(String.format(Locale.US, "%nARAYÜZ  #%d alt=%d  uç nokta=%d  sınıf=%d/%d/%d  %s%n",
                                curIf, curAlt, u8(d, i + 4), curClass, curSub, u8(d, i + 7),
                                ifaceName(curClass, curSub)));
                        if (curClass == 14 && curSub == 2 && !r.vsInterfaces.contains(curIf)) {
                            r.vsInterfaces.add(curIf);
                        }
                    }
                    break;
                case 0x05: // ENDPOINT
                    if (len >= 7) {
                        int addr = u8(d, i + 2);
                        int attr = u8(d, i + 3) & 0x03;
                        int maxp = u16(d, i + 4);
                        int size = maxp & 0x7FF;
                        int mult = ((maxp >> 11) & 0x03) + 1;
                        sb.append(String.format(Locale.US, "  UÇ    0x%02x %s %s  paket=%d x%d%n",
                                addr, (addr & 0x80) != 0 ? "IN " : "OUT", epType(attr), size, mult));
                        if (curClass == 14 && curSub == 2 && (addr & 0x80) != 0) {
                            if (attr == 1) anyVsIso = true;
                            if (attr == 2) anyVsBulk = true;
                        }
                    }
                    break;
                case 0x24: // CS_INTERFACE
                    if (curClass == 14 && curSub == 1) {
                        if (parseVc(d, i, len, sb)) anyXu = true;
                    } else if (curClass == 14 && curSub == 2) {
                        int sub = len >= 3 ? u8(d, i + 2) : -1;
                        switch (sub) {
                            case 0x01:
                                if (len >= 4) sb.append("  VS başlık: format sayısı=").append(u8(d, i + 3)).append('\n');
                                break;
                            case 0x04: // FORMAT_UNCOMPRESSED
                            case 0x10: // FORMAT_FRAME_BASED
                                if (len >= 22) {
                                    anyFormat = true;
                                    anyMjpegOnly = false;
                                    curFmt = fourcc(d, i + 5);
                                    curBpp = u8(d, i + 21);
                                    // YUY2/UYVY de 16 bit ama renkli görüntü formatıdır; ham veri sayılmaz
                                    boolean yuv = curFmt.startsWith("YUY2") || curFmt.startsWith("UYVY") || curFmt.startsWith("YUYV");
                                    if (curFmt.startsWith("Y16") || curFmt.startsWith("Y14") || (curBpp == 16 && !yuv)) any16bit = true;
                                    sb.append(String.format(Locale.US, "  FORMAT #%d  %s  '%s'  %d bit/piksel  kare tipi=%d%n",
                                            u8(d, i + 3), sub == 0x04 ? "sıkıştırmasız" : "frame-based",
                                            curFmt, curBpp, u8(d, i + 4)));
                                    sb.append("          GUID ").append(guid(d, i + 5)).append('\n');
                                }
                                break;
                            case 0x06: // FORMAT_MJPEG
                                if (len >= 5) {
                                    anyFormat = true;
                                    curFmt = "MJPEG";
                                    curBpp = 0;
                                    sb.append(String.format(Locale.US, "  FORMAT #%d  MJPEG  kare tipi=%d%n",
                                            u8(d, i + 3), u8(d, i + 4)));
                                }
                                break;
                            case 0x05: // FRAME_UNCOMPRESSED
                            case 0x07: // FRAME_MJPEG
                                if (len >= 26) {
                                    int w = u16(d, i + 5), h = u16(d, i + 7);
                                    long maxBuf = u32(d, i + 17);
                                    long defInt = u32(d, i + 21);
                                    sb.append(String.format(Locale.US, "    KARE #%d  %dx%d  tampon=%d byte  varsayılan=%s  hızlar: %s%n",
                                            u8(d, i + 3), w, h, maxBuf, fps(defInt), intervals(d, i, len, 25, 26)));
                                    checkResolution(w, h, curFmt, doubledHints);
                                }
                                break;
                            case 0x11: // FRAME_FRAME_BASED
                                if (len >= 26) {
                                    int w = u16(d, i + 5), h = u16(d, i + 7);
                                    sb.append(String.format(Locale.US, "    KARE #%d  %dx%d  varsayılan=%s  satır başı byte=%d  hızlar: %s%n",
                                            u8(d, i + 3), w, h, fps(u32(d, i + 17)), u32(d, i + 22),
                                            intervals(d, i, len, 21, 26)));
                                    checkResolution(w, h, curFmt, doubledHints);
                                }
                                break;
                            case 0x03:
                                sb.append("  Durağan görüntü (still image) descriptor\n");
                                break;
                            case 0x0D:
                                break; // renk eşleme, önemsiz
                            default:
                                sb.append(String.format(Locale.US, "  VS alt tip 0x%02x (%d byte)%n", sub, len));
                        }
                    } else {
                        sb.append(String.format(Locale.US, "  CS_INTERFACE (sınıf %d) %d byte%n", curClass, len));
                    }
                    break;
                case 0x25: // CS_ENDPOINT
                    break;
                default:
                    sb.append(String.format(Locale.US, "  descriptor tipi 0x%02x (%d byte)%n", type, len));
            }
            i += len;
        }

        // ---- Yorumlar ----
        if (!anyFormat) {
            r.hints.add("Hiç UVC video formatı bulunamadı. Bu cihaz standart UVC olmayabilir.");
        }
        if (any16bit) {
            r.hints.add("16-bit ham format var → kamera ham sıcaklık verisi gönderiyor olabilir (iyi işaret).");
        }
        r.hints.addAll(doubledHints);
        if (anyFormat && anyMjpegOnly) {
            r.hints.add("Sadece MJPEG (sıkıştırılmış) görüntü var → sıcaklık doğrudan gelmiyor olabilir.");
        }
        if (anyXu) {
            r.hints.add("Üreticiye özel kontrol birimi (Extension Unit) var → sıcaklık modu/okuma bu birim üzerinden açılıyor olabilir.");
        }
        if (anyVsIso && !anyVsBulk) {
            r.hints.add("Görüntü akışı ISOCHRONOUS → kare okumak için native libuvc gerekecek (Java USB API bunu desteklemez).");
        } else if (anyVsBulk) {
            r.hints.add("Görüntü akışı BULK → kareler doğrudan Java ile okunabilir (kolay yol).");
        }
        return r;
    }

    /** VideoControl alt descriptor'ları. Extension Unit bulunduysa true döner. */
    private static boolean parseVc(byte[] d, int i, int len, StringBuilder sb) {
        if (len < 3) return false;
        int sub = u8(d, i + 2);
        switch (sub) {
            case 0x01:
                if (len >= 5) sb.append(String.format(Locale.US, "  UVC sürümü %x.%02x%n", u8(d, i + 4), u8(d, i + 3)));
                return false;
            case 0x02:
                if (len >= 6) sb.append(String.format(Locale.US, "  Giriş terminali id=%d tip=0x%04x%s%n",
                        u8(d, i + 3), u16(d, i + 4), u16(d, i + 4) == 0x0201 ? " (kamera)" : ""));
                return false;
            case 0x03:
                if (len >= 4) sb.append(String.format(Locale.US, "  Çıkış terminali id=%d%n", u8(d, i + 3)));
                return false;
            case 0x05:
                if (len >= 4) sb.append(String.format(Locale.US, "  İşlem birimi id=%d%n", u8(d, i + 3)));
                return false;
            case 0x06:
                if (len >= 22) {
                    int nPins = u8(d, i + 21);
                    int ctrlSizeIdx = i + 22 + nPins;
                    String ctrls = "";
                    if (ctrlSizeIdx < i + len) {
                        int n = u8(d, ctrlSizeIdx);
                        StringBuilder bm = new StringBuilder();
                        for (int k = 0; k < n && ctrlSizeIdx + 1 + k < i + len; k++) {
                            bm.append(String.format(Locale.US, "%02x", u8(d, ctrlSizeIdx + 1 + k)));
                        }
                        ctrls = " kontrol bitmap=" + bm;
                    }
                    sb.append(String.format(Locale.US, "  ÜRETİCİYE ÖZEL BİRİM (XU) id=%d  kontrol sayısı=%d%s%n          GUID %s%n",
                            u8(d, i + 3), u8(d, i + 20), ctrls, guid(d, i + 4)));
                }
                return true;
            default:
                sb.append(String.format(Locale.US, "  VC alt tip 0x%02x (%d byte)%n", sub, len));
                return false;
        }
    }

    private static void checkResolution(int w, int h, String fmt, List<String> out) {
        for (int[] t : THERMAL_RES) {
            if (w != t[0]) continue;
            if (h == t[1]) return;
            if (h >= 2 * t[1] && h <= 2 * t[1] + 8) {
                add(out, String.format(Locale.US,
                        "%dx%d (%s): sensör %dx%d'nin iki katı yükseklik → görüntü + sıcaklık verisi tek karede olabilir (InfiRay tarzı).",
                        w, h, fmt, t[0], t[1]));
                return;
            }
            if (h > t[1] && h <= t[1] + 8) {
                add(out, String.format(Locale.US,
                        "%dx%d (%s): sensör %dx%d + %d ek satır → ek satırlarda ölçüm/kalibrasyon verisi olabilir.",
                        w, h, fmt, t[0], t[1], h - t[1]));
                return;
            }
        }
    }

    private static void add(List<String> l, String s) { if (!l.contains(s)) l.add(s); }

    private static String intervals(byte[] d, int i, int len, int typeOff, int firstOff) {
        if (typeOff >= len) return "?";
        int n = u8(d, i + typeOff);
        StringBuilder s = new StringBuilder();
        if (n == 0) {
            if (firstOff + 12 <= len) {
                s.append("sürekli ").append(fps(u32(d, i + firstOff + 4))).append("..").append(fps(u32(d, i + firstOff)));
            }
        } else {
            for (int k = 0; k < n && firstOff + 4 * k + 4 <= len; k++) {
                if (k > 0) s.append(", ");
                s.append(fps(u32(d, i + firstOff + 4 * k)));
            }
        }
        return s.toString();
    }

    private static String fps(long interval100ns) {
        if (interval100ns <= 0) return "?";
        return String.format(Locale.US, "%.1ffps", 10_000_000.0 / interval100ns);
    }

    private static String fourcc(byte[] d, int o) {
        StringBuilder s = new StringBuilder();
        for (int k = 0; k < 4; k++) {
            int c = u8(d, o + k);
            if (c < 32 || c > 126) return String.format(Locale.US, "0x%02x%02x%02x%02x",
                    u8(d, o), u8(d, o + 1), u8(d, o + 2), u8(d, o + 3));
            s.append((char) c);
        }
        return s.toString();
    }

    private static String guid(byte[] d, int o) {
        return String.format(Locale.US, "%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                u32(d, o), u16(d, o + 4), u16(d, o + 6),
                u8(d, o + 8), u8(d, o + 9), u8(d, o + 10), u8(d, o + 11),
                u8(d, o + 12), u8(d, o + 13), u8(d, o + 14), u8(d, o + 15));
    }

    static String ifaceName(int cls, int sub) {
        if (cls == 14 && sub == 1) return "(VideoControl)";
        if (cls == 14 && sub == 2) return "(VideoStreaming)";
        if (cls == 14) return "(Video)";
        if (cls == 1) return "(Audio)";
        if (cls == 3) return "(HID)";
        if (cls == 8) return "(Depolama)";
        if (cls == 0xFF) return "(Üreticiye özel)";
        return "";
    }

    static String epType(int t) {
        switch (t) {
            case 0: return "CONTROL";
            case 1: return "ISO    ";
            case 2: return "BULK   ";
            default: return "INTR   ";
        }
    }

    static int u8(byte[] d, int o) { return o < d.length ? d[o] & 0xFF : 0; }

    static int u16(byte[] d, int o) { return u8(d, o) | (u8(d, o + 1) << 8); }

    static long u32(byte[] d, int o) {
        return (u8(d, o) | (u8(d, o + 1) << 8) | (u8(d, o + 2) << 16) | ((long) u8(d, o + 3) << 24)) & 0xFFFFFFFFL;
    }
}
