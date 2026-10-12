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
        if (!CameraActivity.active) {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        }
        finish();
    }
}
