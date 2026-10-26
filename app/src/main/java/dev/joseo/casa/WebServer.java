package dev.joseo.casa;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.MediaMetadata;
import android.content.ComponentName;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A tiny web panel so the phone can change wallpapers and the dock. It listens on the local network only while the
 * accessibility service (which keeps this process alive) runs. Every call except the page itself needs the PIN shown
 * on the TV; wrong guesses are slowed down and locked out after a few.
 */
final class WebServer {

    static final int PORT = 8080;
    private static final long MAX_UPLOAD = 96L * 1024 * 1024;

    private static WebServer instance;

    static synchronized void start(Context c) {
        if (instance != null && instance.running) return;
        instance = new WebServer(c.getApplicationContext());
        instance.begin();
    }

    static synchronized void stop() {
        if (instance != null) instance.end();
        instance = null;
    }

    static boolean isRunning() {
        WebServer i = instance;
        return i != null && i.running;
    }

    /** The PIN, created on first use. */
    static String pin(Context c) {
        SharedPreferences p = c.getSharedPreferences("casa", Context.MODE_PRIVATE);
        String pin = p.getString("pin", null);
        if (pin == null) {
            pin = String.format("%06d", new SecureRandom().nextInt(1_000_000));
            p.edit().putString("pin", pin).apply();
        }
        return pin;
    }

    /** The TV's address on the local network, or null. */
    static String address() {
        try {
            for (Enumeration<NetworkInterface> n = NetworkInterface.getNetworkInterfaces(); n.hasMoreElements(); ) {
                NetworkInterface ni = n.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements(); ) {
                    InetAddress ia = a.nextElement();
                    if (ia.isSiteLocalAddress() && ia.getAddress().length == 4) return ia.getHostAddress();
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private final Context ctx;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile boolean running;
    private ServerSocket server;
    private int failures;
    private long lockedUntil;

    private WebServer(Context c) {
        ctx = c;
    }

    private void begin() {
        running = true;
        Thread t = new Thread(() -> {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new java.net.InetSocketAddress(PORT));
                while (running) {
                    final Socket s = server.accept();
                    pool.execute(() -> handle(s));
                }
            } catch (IOException e) {
                running = false;
            }
        }, "casa-web");
        t.setDaemon(true);
        t.start();
    }

    private void end() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    // ---- one request -------------------------------------------------------------------------------------------

    private static final class Req {
        String method, path;
        final Map<String, String> query = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        InputStream in;
        long length;
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(30000);
            Req r = new Req();
            r.in = new BufferedInputStream(sock.getInputStream(), 16384);
            String line = readLine(r.in);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            r.method = parts[0];
            String target = parts[1];
            int q = target.indexOf('?');
            r.path = q < 0 ? target : target.substring(0, q);
            if (q >= 0) {
                for (String kv : target.substring(q + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e > 0) r.query.put(dec(kv.substring(0, e)), dec(kv.substring(e + 1)));
                }
            }
            String h;
            while ((h = readLine(r.in)) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c > 0) r.headers.put(h.substring(0, c).trim().toLowerCase(), h.substring(c + 1).trim());
            }
            String cl = r.headers.get("content-length");
            r.length = cl == null ? 0 : Long.parseLong(cl);
            OutputStream out = sock.getOutputStream();
            route(r, out);
            out.flush();
        } catch (Exception ignored) {
        }
    }

    private void route(Req r, OutputStream out) throws IOException, JSONException {
        if (r.method.equals("GET") && (r.path.equals("/") || r.path.equals("/index.html"))) {
            byte[] page = readAsset("index.html");
            send(out, 200, "text/html; charset=utf-8", page);
            return;
        }
        if (r.method.equals("GET") && r.path.equals("/icon.png")) {
            send(out, 200, "image/png", appIcon());
            return;
        }
        if (!authorized(r)) {
            send(out, 401, "application/json", "{\"error\":\"pin\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        switch (r.path) {
            case "/api/state":
                sendJson(out, state());
                break;
            case "/api/wallpaper":
                if (!r.method.equals("POST")) {
                    send(out, 405, "text/plain", new byte[0]);
                    break;
                }
                uploadWallpaper(r, out);
                break;
            case "/api/wallpaper/thumb":
                thumb(r, out);
                break;
            case "/api/wallpaper/use": {
                String name = r.query.get("name");
                SharedPreferences.Editor e = ctx.getSharedPreferences("casa", Context.MODE_PRIVATE).edit();
                if (name == null || name.isEmpty() || name.equals("rotate")) e.remove("wp");
                else if (safeFile(name) != null) e.putString("wp", name);
                e.apply();
                Bus.changed();
                sendJson(out, state());
                break;
            }
            case "/api/wallpaper/delete": {
                File f = safeFile(r.query.get("name"));
                if (f != null) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                SharedPreferences p = ctx.getSharedPreferences("casa", Context.MODE_PRIVATE);
                if (f != null && f.getName().equals(p.getString("wp", ""))) p.edit().remove("wp").apply();
                Bus.changed();
                sendJson(out, state());
                break;
            }
            case "/api/icon":
                icon(r, out);
                break;
            case "/api/dock": {
                byte[] body = readBody(r, 64 * 1024);
                JSONArray a = new JSONArray(new String(body, StandardCharsets.UTF_8));
                List<String> pkgs = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) pkgs.add(a.getString(i));
                AppRepo.saveDock(ctx, pkgs);
                Bus.changed();
                sendJson(out, state());
                break;
            }
            case "/api/now":
                sendJson(out, now());
                break;
            case "/api/media":
                media(r.query.get("cmd"));
                sendJson(out, now());
                break;
            case "/api/volume":
                sendJson(out, volume(r.query.get("dir")));
                break;
            case "/api/nav": {
                HomeRedirectService svc = HomeRedirectService.instance;
                String act = r.query.get("action");
                boolean ok = false;
                if (svc != null && act != null) {
                    if (act.equals("home")) ok = svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
                    else if (act.equals("back")) ok = svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                }
                send(out, 200, "application/json", ("{\"ok\":" + ok + "}").getBytes(StandardCharsets.UTF_8));
                break;
            }
            case "/api/launch": {
                String pkg = r.query.get("pkg");
                for (AppRepo.Entry e : AppRepo.load(ctx)) {
                    if (e.pkg.equals(pkg)) {
                        ctx.startActivity(new Intent(e.launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        break;
                    }
                }
                send(out, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
                break;
            }
            default:
                send(out, 404, "text/plain", "no".getBytes(StandardCharsets.UTF_8));
        }
    }

    // ---- auth ---------------------------------------------------------------------------------------------------------

    private synchronized boolean authorized(Req r) {
        long now = System.currentTimeMillis();
        if (now < lockedUntil) return false;
        String given = r.query.get("pin");
        if (given == null) given = r.headers.get("x-pin");
        boolean ok = given != null && MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8), pin(ctx).getBytes(StandardCharsets.UTF_8));
        if (ok) {
            failures = 0;
            return true;
        }
        if (given != null && ++failures >= 8) {
            lockedUntil = now + 60_000;   // a minute's pause after eight wrong PINs
            failures = 0;
        }
        try {
            Thread.sleep(400);
        } catch (InterruptedException ignored) {
        }
        return false;
    }

    // ---- API ------------------------------------------------------------------------------------------------------------

    static File wallpaperDir(Context c) {
        return c.getExternalFilesDir("wallpapers");
    }

    private File safeFile(String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.startsWith(".")) return null;
        File f = new File(wallpaperDir(ctx), name);
        return f.isFile() ? f : null;
    }

    private JSONObject state() throws JSONException {
        JSONObject o = new JSONObject();
        JSONArray w = new JSONArray();
        File[] files = wallpaperDir(ctx).listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                if (f.isFile() && !f.getName().startsWith(".") && !f.getName().endsWith(".tmp")) {
                    w.put(new JSONObject().put("name", f.getName()).put("size", f.length()));
                }
            }
        }
        o.put("wallpapers", w);
        o.put("mode", ctx.getSharedPreferences("casa", Context.MODE_PRIVATE).getString("wp", ""));
        List<AppRepo.Entry> all = AppRepo.load(ctx);
        JSONArray apps = new JSONArray();
        for (AppRepo.Entry e : all) apps.put(new JSONObject().put("pkg", e.pkg).put("label", e.label));
        o.put("apps", apps);
        JSONArray dock = new JSONArray();
        for (String pkg : AppRepo.dock(ctx)) dock.put(pkg);
        o.put("dock", dock);
        return o;
    }

    private void uploadWallpaper(Req r, OutputStream out) throws IOException, JSONException {
        if (r.length <= 0 || r.length > MAX_UPLOAD) {
            send(out, 413, "application/json", "{\"error\":\"size\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        File dir = wallpaperDir(ctx);
        File tmp = new File(dir, ".upload-" + System.nanoTime() + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            long left = r.length;
            while (left > 0) {
                int n = r.in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("short body");
                fo.write(buf, 0, n);
                left -= n;
            }
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(tmp.getPath(), o);
        if (o.outWidth <= 0) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            send(out, 415, "application/json", "{\"error\":\"image\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String base = r.query.get("name");
        base = base == null ? "foto" : base.replaceAll("\\.[A-Za-z0-9]{1,5}$", "").replaceAll("[^A-Za-z0-9._-]+", "-");
        if (base.isEmpty() || base.startsWith(".")) base = "foto";
        if (base.length() > 40) base = base.substring(0, 40);
        File dest = new File(dir, base + ".jpg");
        if (dest.exists()) dest = new File(dir, base + "-" + System.currentTimeMillis() % 100000 + ".jpg");
        if (!tmp.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("rename");
        }
        Bus.changed();
        sendJson(out, state());
    }

    private void thumb(Req r, OutputStream out) throws IOException {
        File f = safeFile(r.query.get("name"));
        if (f == null) {
            send(out, 404, "text/plain", new byte[0]);
            return;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= 480) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) {
            send(out, 415, "text/plain", new byte[0]);
            return;
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 72, bo);
        b.recycle();
        send(out, 200, "image/jpeg", bo.toByteArray());
    }

    private void icon(Req r, OutputStream out) throws IOException {
        try {
            Drawable d = ctx.getPackageManager().getApplicationIcon(r.query.get("pkg"));
            Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 96, 96);
            d.draw(c);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bo);
            b.recycle();
            send(out, 200, "image/png", bo.toByteArray());
        } catch (Exception e) {
            send(out, 404, "text/plain", new byte[0]);
        }
    }

    // ---- remote control ------------------------------------------------------------------------------------------

    private List<MediaController> sessions() {
        try {
            MediaSessionManager m = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            return m.getActiveSessions(new ComponentName(ctx, NowPlayingListener.class));
        } catch (SecurityException e) {
            return new ArrayList<>();   // notification access not allowed yet
        }
    }

    /** The session the user means: a playing one, else the first paused one. */
    private MediaController pick() {
        MediaController paused = null;
        for (MediaController c : sessions()) {
            PlaybackState st = c.getPlaybackState();
            if (st == null) continue;
            if (st.getState() == PlaybackState.STATE_PLAYING) return c;
            if (paused == null && st.getState() == PlaybackState.STATE_PAUSED) paused = c;
        }
        return paused;
    }

    private JSONObject now() throws JSONException {
        JSONObject o = new JSONObject();
        MediaController c = pick();
        o.put("active", c != null);
        if (c != null) {
            MediaMetadata md = c.getMetadata();
            o.put("playing", c.getPlaybackState().getState() == PlaybackState.STATE_PLAYING);
            o.put("title", md == null ? "" : String.valueOf(md.getText(MediaMetadata.METADATA_KEY_TITLE)));
            CharSequence a = md == null ? null : md.getText(MediaMetadata.METADATA_KEY_ARTIST);
            o.put("artist", a == null ? "" : a.toString());
            o.put("pkg", c.getPackageName());
        }
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        o.put("volume", am.getStreamVolume(AudioManager.STREAM_MUSIC));
        o.put("max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        o.put("muted", am.isStreamMute(AudioManager.STREAM_MUSIC));
        return o;
    }

    private void media(String cmd) {
        MediaController c = pick();
        if (c == null || cmd == null) return;
        MediaController.TransportControls t = c.getTransportControls();
        boolean playing = c.getPlaybackState().getState() == PlaybackState.STATE_PLAYING;
        switch (cmd) {
            case "toggle": if (playing) t.pause(); else t.play(); break;
            case "play": t.play(); break;
            case "pause": t.pause(); break;
            case "next": t.skipToNext(); break;
            case "prev": t.skipToPrevious(); break;
            default: break;
        }
    }

    private JSONObject volume(String dir) throws JSONException {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int direction = "up".equals(dir) ? AudioManager.ADJUST_RAISE : "down".equals(dir) ? AudioManager.ADJUST_LOWER
                : "mute".equals(dir) ? AudioManager.ADJUST_TOGGLE_MUTE : AudioManager.ADJUST_SAME;
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        return now();
    }

    /** The picture iPhone uses when the panel is added to the Home Screen. */
    private byte[] appIcon() {
        Bitmap b = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.LinearGradient(0, 0, 180, 180, 0xFF5B8CFF, 0xFF7A4DFF, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 180, 180, p);
        p.setShader(null);
        p.setColor(0xFFFFFFFF);
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(12);
        p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        android.graphics.Path roof = new android.graphics.Path();   // a little house
        roof.moveTo(42, 92);
        roof.lineTo(90, 50);
        roof.lineTo(138, 92);
        roof.moveTo(54, 84);
        roof.lineTo(54, 132);
        roof.lineTo(126, 132);
        roof.lineTo(126, 84);
        c.drawPath(roof, p);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, bo);
        return bo.toByteArray();
    }

    // ---- plumbing ---------------------------------------------------------------------------------------------------

    private byte[] readAsset(String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private static byte[] readBody(Req r, int max) throws IOException {
        if (r.length > max) throw new IOException("body too large");
        byte[] b = new byte[(int) r.length];
        int off = 0;
        while (off < b.length) {
            int n = r.in.read(b, off, b.length - off);
            if (n < 0) throw new IOException("short body");
            off += n;
        }
        return b;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("line too long");
        }
        return c < 0 && sb.length() == 0 ? null : sb.toString();
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static void sendJson(OutputStream out, JSONObject o) throws IOException {
        send(out, 200, "application/json; charset=utf-8", o.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String status = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 404 ? "Not Found"
                : code == 413 ? "Payload Too Large" : code == 415 ? "Unsupported Media Type" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
    }
}
