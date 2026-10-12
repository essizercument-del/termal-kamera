/*
 * UVC ISO (isochronous) akışından tek bir tam kare okur.
 * Android Java USB API ISO transferi desteklemediği için usbfs ioctl'leri doğrudan kullanılır.
 * Dosya tanıtıcısı UsbDeviceConnection.getFileDescriptor() ile Java'dan gelir.
 */
#include <jni.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <errno.h>
#include <poll.h>
#include <time.h>

#define NURB 8
#define NPKT 32

/* stats dizisi indeksleri */
enum {
    ST_PKT = 0, ST_DATA, ST_ERRFLAG, ST_BADSIZE, ST_LASTBAD, ST_URBERR, ST_BADHDR, ST_FRAMES,
    ST_PKTSTATUS,   /* paket durum kodu != 0 olan paket sayısı */
    ST_ZERO,        /* hatasız ama 0 byte gelen paket */
    ST_HDRONLY,     /* sadece UVC başlığı, veri yok */
    ST_KBYTES,      /* gelen toplam yük (KB) */
    ST_STAT1,       /* ilk görülen paket durum kodu */
    ST_STAT2,       /* ikinci farklı durum kodu */
    ST_URBSTAT,     /* ilk URB durum kodu */
    ST_MAXLEN,      /* en uzun paket */
    ST_N
};

static long long now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (long long) t.tv_sec * 1000LL + t.tv_nsec / 1000000;
}

static void cleanup(int fd, struct usbdevfs_urb **urbs, int *pending) {
    for (int i = 0; i < NURB; i++) {
        if (urbs[i] && pending[i]) ioctl(fd, USBDEVFS_DISCARDURB, urbs[i]);
    }
    long long t0 = now_ms();
    int left = 0;
    for (int i = 0; i < NURB; i++) left += pending[i];
    while (left > 0 && now_ms() - t0 < 1000) {
        struct usbdevfs_urb *r = NULL;
        if (ioctl(fd, USBDEVFS_REAPURBNDELAY, &r) == 0 && r) {
            int idx = (int) (intptr_t) r->usercontext;
            if (idx >= 0 && idx < NURB && pending[idx]) { pending[idx] = 0; left--; }
        } else {
            struct pollfd p = {fd, POLLOUT, 0};
            poll(&p, 1, 20);
        }
    }
    for (int i = 0; i < NURB; i++) {
        if (!urbs[i]) continue;
        if (!pending[i]) {           /* çekirdekte bekleyen URB'nin belleği serbest bırakılmaz */
            free(urbs[i]->buffer);
            free(urbs[i]);
        }
        urbs[i] = NULL;
    }
}

/*
 * Dönüş: >0 tam kare boyutu (out'a kopyalandı), 0 zaman aşımı, <0 -errno
 */
JNIEXPORT jint JNICALL
Java_com_ercument_termalkamera_ThermalSession_grab(JNIEnv *env, jclass cls, jint fd, jint ep,
                                                   jint pktSize, jbyteArray out, jint timeoutMs,
                                                   jintArray statsArr) {
    (void) cls;
    jint stats[ST_N];
    memset(stats, 0, sizeof(stats));

    const int expected = (*env)->GetArrayLength(env, out);
    uint8_t *frame = (uint8_t *) malloc((size_t) expected + 65536);
    if (!frame) return -ENOMEM;

    struct usbdevfs_urb *urbs[NURB];
    int pending[NURB];
    memset(urbs, 0, sizeof(urbs));
    memset(pending, 0, sizeof(pending));
    const size_t urbsz = sizeof(struct usbdevfs_urb) + NPKT * sizeof(struct usbdevfs_iso_packet_desc);

    int result = 0;
    for (int i = 0; i < NURB; i++) {
        struct usbdevfs_urb *u = (struct usbdevfs_urb *) calloc(1, urbsz);
        void *buf = malloc((size_t) pktSize * NPKT);
        if (!u || !buf) { free(u); free(buf); result = -ENOMEM; goto done; }
        u->type = USBDEVFS_URB_TYPE_ISO;
        u->endpoint = (unsigned char) ep;
        u->flags = USBDEVFS_URB_ISO_ASAP;
        u->buffer = buf;
        u->buffer_length = pktSize * NPKT;
        u->number_of_packets = NPKT;
        u->usercontext = (void *) (intptr_t) i;
        for (int k = 0; k < NPKT; k++) u->iso_frame_desc[k].length = (unsigned int) pktSize;
        urbs[i] = u;
        if (ioctl(fd, USBDEVFS_SUBMITURB, u) < 0) { result = -errno; goto done; }
        pending[i] = 1;
    }

    int fid = -1, started = 0, done = 0;
    size_t len = 0;
    long long totalBytes = 0;
    long long t0 = now_ms();

    while (!done) {
        if (now_ms() - t0 > timeoutMs) { result = 0; break; }
        struct usbdevfs_urb *r = NULL;
        int rc = ioctl(fd, USBDEVFS_REAPURBNDELAY, &r);
        if (rc < 0) {
            int e = errno;
            if (e == EAGAIN) {
                struct pollfd p = {fd, POLLOUT, 0};
                poll(&p, 1, 50);
                continue;
            }
            result = -e;
            break;
        }
        if (!r) continue;
        int idx = (int) (intptr_t) r->usercontext;
        if (idx >= 0 && idx < NURB) pending[idx] = 0;
        if (r->status != 0) {
            stats[ST_URBERR]++;
            if (stats[ST_URBSTAT] == 0) stats[ST_URBSTAT] = r->status;
        }

        uint8_t *base = (uint8_t *) r->buffer;
        for (int k = 0; k < r->number_of_packets && !done; k++) {
            stats[ST_PKT]++;
            int alen = (int) r->iso_frame_desc[k].actual_length;
            int st = (int) r->iso_frame_desc[k].status;
            if (st != 0) {
                stats[ST_PKTSTATUS]++;
                if (stats[ST_STAT1] == 0) stats[ST_STAT1] = st;
                else if (st != stats[ST_STAT1] && stats[ST_STAT2] == 0) stats[ST_STAT2] = st;
            }
            if (alen > stats[ST_MAXLEN]) stats[ST_MAXLEN] = alen;
            if (alen <= 0) { if (st == 0) stats[ST_ZERO]++; continue; }
            uint8_t *p = base + (size_t) k * pktSize;
            int hl = p[0];
            if (hl < 2 || hl > alen) { stats[ST_BADHDR]++; continue; }
            if (alen == hl) stats[ST_HDRONLY]++;
            int flags = p[1];
            int f = flags & 0x01;
            if (flags & 0x40) stats[ST_ERRFLAG]++;

            if (fid != -1 && f != fid) {          /* yeni kare başladı */
                if (started && len > 0) {
                    if ((int) len == expected) { done = 1; break; }
                    stats[ST_BADSIZE]++; stats[ST_LASTBAD] = (jint) len;
                }
                len = 0;
                started = 1;
            }
            fid = f;

            int plen = alen - hl;
            if (plen > 0) { stats[ST_DATA]++; totalBytes += plen; }
            if (started && plen > 0) {
                if (len + plen <= (size_t) expected + 65536) {
                    memcpy(frame + len, p + hl, (size_t) plen);
                    len += plen;
                }
            }
            if (flags & 0x02) {                    /* kare sonu (EOF) */
                stats[ST_FRAMES]++;
                if (started && (int) len == expected) { done = 1; break; }
                if (started && len > 0) { stats[ST_BADSIZE]++; stats[ST_LASTBAD] = (jint) len; }
                len = 0;
                started = 1;
            }
        }
        if (done) { result = expected; break; }

        /* URB'yi yeniden gönder */
        for (int k = 0; k < NPKT; k++) {
            r->iso_frame_desc[k].length = (unsigned int) pktSize;
            r->iso_frame_desc[k].actual_length = 0;
            r->iso_frame_desc[k].status = 0;
        }
        r->status = 0;
        if (ioctl(fd, USBDEVFS_SUBMITURB, r) < 0) { result = -errno; break; }
        if (idx >= 0 && idx < NURB) pending[idx] = 1;
    }

    stats[ST_KBYTES] = (jint) (totalBytes / 1024);
    if (result > 0) (*env)->SetByteArrayRegion(env, out, 0, expected, (const jbyte *) frame);

done:
    cleanup(fd, urbs, pending);
    free(frame);
    if (statsArr && (*env)->GetArrayLength(env, statsArr) >= ST_N) {
        (*env)->SetIntArrayRegion(env, statsArr, 0, ST_N, stats);
    }
    return result;
}
