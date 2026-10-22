package com.videotheque.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbRandomAccessFile;

/**
 * Sert le partage SMB (serveur / partage / sous-dossier configurés par l'utilisateur) en HTTP,
 * mais UNIQUEMENT sur la boucle locale (127.0.0.1) de la TV : la page web et le lecteur vidéo
 * externe (Nova, VLC…) s'y connectent tous les deux, aucun PC ni serveur externe n'est requis.
 * Gère les requêtes Range pour permettre l'avance / le retour dans les vidéos.
 */
public class SmbBridgeService extends Service {

    public static final String PREFS = "videotheque";
    private static final String TAG = "SmbBridge";
    public static final int PORT = 51824; // distinct de l'appli « Vidéothèque » (51823)

    private final IBinder binder = new LocalBinder();
    private ServerSocket server;
    private ExecutorService pool;
    private volatile boolean running;
    private volatile String status = "Démarrage…";
    private CIFSContext ctx;
    private String smbBase; // ex : smb://192.168.1.10/E/A/

    public class LocalBinder extends Binder {
        SmbBridgeService getService() { return SmbBridgeService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification());
        start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        if (pool != null) pool.shutdownNow();
        super.onDestroy();
    }

    public String getStatus() { return status; }
    public boolean isConfigured() { return smbBase != null; }
    public int getPort() { return PORT; }

    /** (Re)lit les réglages et (re)connecte le contexte SMB. Appelable depuis la boîte de dialogue Réglages. */
    public synchronized void reload() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String host = p.getString("host", "").trim();
        String share = p.getString("share", "").trim();
        String sub = p.getString("sub", "").trim();
        String domain = p.getString("domain", "").trim();
        String user = p.getString("user", "").trim();
        String pass = p.getString("pass", "");

        if (host.isEmpty() || share.isEmpty()) { smbBase = null; status = "Non configuré"; return; }

        try {
            Properties props = new Properties();
            // Pas de minVersion : on laisse la plage par défaut (SMB1 à SMB2.10) pour rester
            // compatible avec les NAS anciens qui ne parlent que SMB1 ; on autorise en plus SMB3.
            props.setProperty("jcifs.smb.client.maxVersion", "SMB311");
            props.setProperty("jcifs.smb.client.responseTimeout", "15000");
            props.setProperty("jcifs.smb.client.connTimeout", "8000");
            CIFSContext base = new BaseContext(new PropertyConfiguration(props));
            ctx = user.isEmpty() ? base.withGuestCrendentials()
                    : base.withCredentials(new NtlmPasswordAuthenticator(domain.isEmpty() ? null : domain, user, pass));
            String b = "smb://" + host + "/" + share + "/";
            if (!sub.isEmpty()) b += trimSlashes(sub) + "/";
            smbBase = b;
            status = "Connexion…";
        } catch (Throwable e) {
            smbBase = null; status = "Erreur de configuration : " + e.getClass().getSimpleName() + (e.getMessage() != null ? " : " + e.getMessage() : "");
        }
    }

    /** Test de connexion, appelé par la boîte de dialogue Réglages (fait un accès réseau : à lancer hors du thread UI). */
    public String testConnection() {
        if (smbBase == null) {
            if (status != null && !status.equals("Non configuré")) return "Configuration refusée : " + status;
            return "Renseigne au moins le serveur et le partage.";
        }
        try {
            SmbFile f = new SmbFile(smbBase, ctx);
            if (!f.exists()) return "Le dossier n'existe pas ou le partage est introuvable : " + smbBase;
            if (!f.canRead()) return "Dossier trouvé, mais illisible (identifiants ?) : " + smbBase;
            String[] l = f.list();
            status = "Connecté (" + (l == null ? 0 : l.length) + " éléments)";
            return "OK — connecté à " + smbBase + ", " + (l == null ? 0 : l.length) + " éléments à la racine.";
        } catch (Throwable e) {
            String detail = e.getClass().getSimpleName() + (e.getMessage() != null ? " : " + e.getMessage() : "");
            status = "Erreur : " + detail;
            return "Échec (" + smbBase + ") — " + detail;
        }
    }

    private void start() {
        if (running) return;
        running = true;
        pool = Executors.newCachedThreadPool();
        new Thread(() -> {
            reload(); // fait potentiellement du réseau : jamais sur le thread principal
            try {
                server = new ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"));
                while (running) {
                    Socket c = server.accept();
                    pool.execute(() -> handle(c));
                }
            } catch (IOException e) {
                if (running) Log.e(TAG, "Serveur local arrêté", e);
            }
        }, "smb-http-accept").start();
    }

    private static final Map<String, String> MIME = new HashMap<>();
    static {
        MIME.put("mp4", "video/mp4"); MIME.put("m4v", "video/mp4"); MIME.put("mkv", "video/x-matroska");
        MIME.put("webm", "video/webm"); MIME.put("avi", "video/x-msvideo"); MIME.put("ts", "video/mp2t");
        MIME.put("mov", "video/quicktime"); MIME.put("jpg", "image/jpeg"); MIME.put("jpeg", "image/jpeg");
        MIME.put("png", "image/png"); MIME.put("webp", "image/webp");
    }

    private void handle(Socket c) {
        try (Socket s = c) {
            s.setSoTimeout(30000);
            InputStream in = s.getInputStream();
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] rl = lines[0].split(" ");
            if (rl.length < 2) return;
            String method = rl[0];
            String path = URLDecoder.decode(rl[1].split("\\?")[0], "UTF-8");
            String range = null;
            for (String l : lines) if (l.regionMatches(true, 0, "Range:", 0, 6)) range = l.substring(6).trim();

            OutputStream out = s.getOutputStream();
            if (smbBase == null) { sendText(out, "503 Service Unavailable", "SMB non configuré. Ouvre les réglages (⚙) dans l'application."); return; }
            if (path.equals("/") || path.isEmpty()) { sendText(out, "404 Not Found", "—"); return; }

            SmbFile f;
            try {
                f = new SmbFile(smbBase + trimSlashes(path), ctx);
                if (!f.exists()) { sendText(out, "404 Not Found", "Introuvable sur le partage : " + path); return; }
            } catch (SmbException e) {
                sendText(out, "502 Bad Gateway", "Erreur SMB : " + e.getMessage()); return;
            }

            String ext = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1).toLowerCase() : "";
            String type = MIME.getOrDefault(ext, "application/octet-stream");

            try (SmbRandomAccessFile raf = new SmbRandomAccessFile(f, "r")) {
                long size = raf.length(), start = 0, end = size - 1;
                boolean partial = false;
                if (range != null && range.regionMatches(true, 0, "bytes=", 0, 6)) {
                    String r = range.substring(6).split(",")[0].trim();
                    int d = r.indexOf('-');
                    if (d >= 0) {
                        String sa = r.substring(0, d), sb = r.substring(d + 1);
                        if (sa.isEmpty()) { long suf = Long.parseLong(sb); start = Math.max(0, size - suf); }
                        else { start = Long.parseLong(sa); if (!sb.isEmpty()) end = Math.min(Long.parseLong(sb), size - 1); }
                        partial = true;
                    }
                }
                if (start > end || start >= size) { send(out, "416 Range Not Satisfiable", "text/plain", 0, "Content-Range: bytes */" + size + "\r\n"); return; }
                long len = end - start + 1;
                send(out, partial ? "206 Partial Content" : "200 OK", type, len,
                        partial ? "Content-Range: bytes " + start + "-" + end + "/" + size + "\r\n" : "");
                if (method.equals("HEAD")) return;
                raf.seek(start);
                byte[] buf = new byte[131072];
                long left = len;
                while (left > 0) {
                    int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n <= 0) break;
                    out.write(buf, 0, n);
                    left -= n;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "requête : " + e.getMessage());
        }
    }

    private static String trimSlashes(String s) {
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String readHead(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        int state = 0, c;
        while (b.size() < 16384 && (c = in.read()) >= 0) {
            b.write(c);
            if ((state == 0 || state == 2) && c == 13) state++;
            else if ((state == 1 || state == 3) && c == 10) state++;
            else state = (c == 13) ? 1 : 0;
            if (state == 4) return b.toString("US-ASCII");
        }
        return b.size() > 0 ? b.toString("US-ASCII") : null;
    }

    private static void send(OutputStream out, String status, String type, long len, String extra) throws IOException {
        String h = "HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + len +
                "\r\nAccept-Ranges: bytes\r\nConnection: close\r\n" + extra + "\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
    }

    private static void sendText(OutputStream out, String status, String msg) throws IOException {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
        send(out, status, "text/plain; charset=utf-8", b.length, "");
        out.write(b);
    }

    private Notification buildNotification() {
        String chan = "smb-bridge";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel c = new NotificationChannel(chan, "Médiathèque", NotificationManager.IMPORTANCE_MIN);
            nm.createNotificationChannel(c);
        }
        return new NotificationCompat.Builder(this, chan)
                .setContentTitle("Médiathèque")
                .setContentText("Accès au partage réseau actif")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
    }
}
