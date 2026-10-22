package com.videotheque.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Habillage plein écran de videotheque.html. Le partage réseau (SMB) est lu directement par
 * SmbBridgeService, qui l'expose en HTTP sur 127.0.0.1 ; la page s'en sert comme base, et
 * le lecteur externe (Nova, VLC…) s'y connecte aussi pour lire le flux. Ni PC, ni serveur
 * externe requis : seuls le serveur, le partage et les identifiants sont à renseigner (⚙).
 */
public class MainActivity extends Activity {

    private WebView web;
    private SmbBridgeService service;
    private boolean bound;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            service = ((SmbBridgeService.LocalBinder) b).getService();
            bound = true;
            if (!service.isConfigured()) askSettings(true);
        }
        @Override public void onServiceDisconnected(ComponentName n) { bound = false; service = null; }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        web = new WebView(this);
        web.setBackgroundColor(0xFF191A4A);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(true);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient());
        web.requestFocus(View.FOCUS_DOWN);
        web.loadUrl("file:///android_asset/mediatheque.html");

        Intent i = new Intent(this, SmbBridgeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i); else startService(i);
        bindService(i, conn, Context.BIND_AUTO_CREATE);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (bound) unbindService(conn);
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** Boîte de dialogue Réglages : serveur, partage, sous-dossier, identifiants, test de connexion. */
    private void askSettings(boolean forced) {
        SharedPreferences p = getSharedPreferences(SmbBridgeService.PREFS, MODE_PRIVATE);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText host = field(box, "Serveur (IP ou nom)", "ex. blind4g-rstefxp", p.getString("host", "blind4g-rstefxp"), false);
        EditText share = field(box, "Partage", "ex. E", p.getString("share", "E"), false);
        EditText sub = field(box, "Sous-dossier (laisser vide)", "", p.getString("sub", ""), false);
        EditText user = field(box, "Utilisateur (vide = invité)", "", p.getString("user", ""), false);
        EditText pass = field(box, "Mot de passe", "", p.getString("pass", ""), true);
        EditText domain = field(box, "Domaine (optionnel)", "", p.getString("domain", ""), false);

        TextView msg = new TextView(this);
        msg.setPadding(0, pad / 2, 0, 0);
        box.addView(msg);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(box);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Réglages réseau")
                .setView(scroll)
                .setCancelable(!forced)
                .setPositiveButton("Enregistrer", null)
                .setNeutralButton("Tester", null);
        if (!forced) builder.setNegativeButton("Annuler", null);
        AlertDialog dlg = builder.show();

        Runnable save = () -> {
            p.edit()
                    .putString("host", host.getText().toString().trim())
                    .putString("share", share.getText().toString().trim())
                    .putString("sub", sub.getText().toString().trim())
                    .putString("user", user.getText().toString().trim())
                    .putString("pass", pass.getText().toString())
                    .putString("domain", domain.getText().toString().trim())
                    .apply();
            // Important : service.reload() prépare la connexion SMB, donc fait potentiellement
            // du réseau (résolution de nom...). Android interdit ça sur le thread principal
            // (NetworkOnMainThreadException) : on ne l'appelle jamais ici directement.
        };

        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            save.run();
            dlg.dismiss();
            if (bound) new Thread(() -> service.reload()).start();
            web.reload();
        });
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            save.run();
            msg.setText("Test en cours…");
            new AsyncTask<Void, Void, String>() {
                @Override protected String doInBackground(Void... v2) {
                    if (!bound) return "Service non prêt.";
                    service.reload();
                    return service.testConnection();
                }
                @Override protected void onPostExecute(String r) { msg.setText(r); }
            }.execute();
        });
    }

    private EditText field(LinearLayout box, String label, String hint, String value, boolean password) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, 2);
        box.addView(l);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(password ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
                : InputType.TYPE_CLASS_TEXT);
        box.addView(e);
        return e;
    }

    private void launch(String url, String title) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(url), "video/*");
        i.putExtra("title", title);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Aucun lecteur vidéo installé (Nova, VLC, Just Player…)", Toast.LENGTH_LONG).show();
        }
    }

    /** Pont appelé par la page : window.Android.xxx */
    private class Bridge {
        @JavascriptInterface
        public String getBase() {
            return "http://127.0.0.1:" + SmbBridgeService.PORT + "/";
        }

        @JavascriptInterface
        public void play(final String url, final String title) {
            runOnUiThread(() -> launch(url, title));
        }

        @JavascriptInterface
        public void settings() {
            runOnUiThread(() -> askSettings(false));
        }
    }
}
