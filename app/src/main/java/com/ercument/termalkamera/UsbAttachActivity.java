package com.ercument.termalkamera;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Kamera takılınca Android'in "bu uygulamayla aç" onayını karşılar (USB izni böylece otomatik gelir).
 * Kamera ekranı açıksa hiçbir şey yapmaz (o ekran kamerayı kendisi bulur);
 * değilse Termal Takip'i öne getirir.
 */
public class UsbAttachActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Kamera ekranı açıksa (izin penceresi yüzünden arka planda olsa bile) dokunma: MainActivity
        // singleTask olduğu için onu başlatmak üstündeki kamera ekranını kapatırdı. Değilse uygulamayı öne getir.
        if (!CameraActivity.exists) {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }
        finish();
    }
}
