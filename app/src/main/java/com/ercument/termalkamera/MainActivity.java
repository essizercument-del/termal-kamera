package com.ercument.termalkamera;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Ana ekran: Termal Takip (GitHub Pages) uygulama içi tarayıcıda.
 * JS köprüsü "TermalKamera": olc(hedefId, tag) → kamera ekranı → window.kameraSonuc(deger, hedefId)
 */
public class MainActivity extends Activity {

    static final String URL = "https://essizercument-del.github.io/Termal/sicaklikv9.html";
    static final int REQ_CAM = 21, REQ_FILE = 22, REQ_PERM_WEB = 23;

    static volatile boolean alive;
    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private PermissionRequest pendingWebPerm;
    private String camTarget = "kayitTemp";
    private boolean triedCache;
    private long lastBack;

    /** blob:/data: indirmelerini yakalayıp yerel dosyaya yazdırır (a.click, dispatchEvent ve tıklama) */
    private static final String DL_JS =
            "(function(){if(window.__tkDl)return;window.__tkDl=1;" +
            "function h(a){var u=a.href||'';var n=a.getAttribute('download');if(n===null)return false;" +
            "if(!(u.indexOf('blob:')===0||u.indexOf('data:')===0))return false;" +
            "fetch(u).then(function(r){return r.blob();}).then(function(b){var f=new FileReader();" +
            "f.onload=function(){var s=f.result;TermalKamera.saveFile(n||'dosya',b.type||'application/octet-stream',s.substring(s.indexOf(',')+1));};" +
            "f.readAsDataURL(b);}).catch(function(e){alert('Indirme hatasi: '+e);});return true;}" +
            "var oc=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){if(h(this))return;return oc.apply(this,arguments);};" +
            "var od=EventTarget.prototype.dispatchEvent;EventTarget.prototype.dispatchEvent=function(e){" +
            "if(this instanceof HTMLAnchorElement&&e&&e.type==='click'&&h(this))return false;return od.apply(this,arguments);};" +
            "document.addEventListener('click',function(e){var a=e.target&&e.target.closest&&e.target.closest('a[download]');" +
            "if(a&&h(a))e.preventDefault();},true);})();";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        alive = true;
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setUserAgentString(s.getUserAgentString() + " TermalKameraApp/1");
        s.setCacheMode(isOnline() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);

        web.addJavascriptInterface(new Bridge(), "TermalKamera");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(DL_JS, null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String host = u.getHost() == null ? "" : u.getHost();
                if (host.endsWith("github.io") || host.endsWith("firebaseapp.com") || host.endsWith("google.com")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Throwable ignored) {}
                return true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame() && !triedCache) {
                    // İnternet yoksa en son açılan sürümü önbellekten aç
                    triedCache = true;
                    v.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
                    v.loadUrl(URL);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest req) {
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        req.grant(req.getResources());
                    } else {
                        pendingWebPerm = req;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERM_WEB);
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(Intent.createChooser(i, "Dosya seç"), REQ_FILE);
                } catch (Throwable t) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl(URL);
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo n = cm.getActiveNetworkInfo();
            return n != null && n.isConnected();
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_PERM_WEB && pendingWebPerm != null) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) pendingWebPerm.grant(pendingWebPerm.getResources());
            else pendingWebPerm.deny();
            pendingWebPerm = null;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE) {
            if (fileCb != null) fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
        } else if (req == REQ_CAM && res == RESULT_OK && data != null) {
            double t = data.getDoubleExtra(CameraActivity.EXTRA_TEMP, Double.NaN);
            if (Double.isNaN(t)) return;
            String js = String.format(Locale.US,
                    "window.kameraSonuc?window.kameraSonuc(%.1f,%s):alert('Kamera degeri: %.1f °C (Termal Takip guncel degil)')",
                    t, JSONObject.quote(camTarget), t);
            web.evaluateJavascript(js, null);
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) { web.goBack(); return; }
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) { super.onBackPressed(); return; }
        lastBack = now;
        Toast.makeText(this, "Çıkmak için tekrar bas", Toast.LENGTH_SHORT).show();
    }

    // ---------------- JS köprüsü ----------------

    class Bridge {
        @JavascriptInterface
        public void olc(String hedefId, String tagAdi) {
            runOnUiThread(() -> {
                camTarget = (hedefId == null || hedefId.isEmpty()) ? "kayitTemp" : hedefId;
                Intent i = new Intent(MainActivity.this, CameraActivity.class);
                i.putExtra(CameraActivity.EXTRA_TAG, tagAdi == null ? "" : tagAdi);
                startActivityForResult(i, REQ_CAM);
            });
        }

        @JavascriptInterface
        public void tani() {
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, TaniActivity.class)));
        }

        @JavascriptInterface
        public String surum() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable t) { return "?"; }
        }

        @JavascriptInterface
        public void saveFile(String name, String mime, String b64) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                Uri uri = null;
                String where;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TermalTakip");
                    uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new IllegalStateException("Dosya oluşturulamadı");
                    try (OutputStream o = getContentResolver().openOutputStream(uri)) { o.write(data); }
                    where = "İndirilenler/TermalTakip/" + safe;
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    File f = new File(dir, safe);
                    try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
                    where = f.getAbsolutePath();
                }
                final Uri fu = uri;
                final String fw = where;
                runOnUiThread(() -> savedDialog(fw, fu, mime));
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Kaydetme hatası: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        }
    }

    private void savedDialog(String where, Uri uri, String mime) {
        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle("Dosya kaydedildi")
                .setMessage(where)
                .setNegativeButton("Tamam", null);
        if (uri != null) {
            d.setPositiveButton("Aç", (x, y) -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, mime);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(i);
                } catch (Throwable t) {
                    Toast.makeText(this, "Açacak uygulama bulunamadı", Toast.LENGTH_SHORT).show();
                }
            });
            d.setNeutralButton("Paylaş", (x, y) -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(mime);
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Paylaş"));
            });
        }
        d.show();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
