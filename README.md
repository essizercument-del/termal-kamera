# Termal Kamera

Fluke USB-C termal kamerayı (VID 0f7e / PID 00bc, UVC) Termal Takip'e bağlamak için Android tarafı.

## Şu anki aşama: Tanı uygulaması

Amaç iki soruyu cevaplamak:
1. USB izni neden reddediliyor?
2. Kamera ham sıcaklık verisi gönderiyor mu, yoksa sadece renkli görüntü mü?

### Kurulum
Her `main` push'unda GitHub Actions APK'yı derler ve **Releases → son-surum** altına koyar.
Telefondan `termal-kamera-tani.apk` dosyasını indirip kur. İmza sabit olduğu için yeni sürüm eskinin üzerine kurulur.

### Kullanım (iş yerinde)
1. Oppo: Ayarlar → Ek ayarlar → **OTG bağlantısı AÇ** (10 dk sonra kendiliğinden kapanır).
2. Kamerayı tak. "Termal Kamera Tanı ile aç?" sorulursa onayla.
3. Uygulamada sırayla: **1. Tara → 2. Kamera izni → 3. USB izni → 4. Aç ve oku**.
4. **Paylaş** ile çıkan metnin tamamını gönder.
