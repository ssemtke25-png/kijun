package io.github.ssemtke25.kijun;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class MainActivity extends Activity {

    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    static final int REQ_PERM = 1;
    static final int REQ_FILE = 2;

    WebView web;
    WebViewAssetLoader loader;
    boolean shimAtStart = false;
    String shim = "";

    ValueCallback<Uri[]> fileCb;
    Uri cameraUri;

    BluetoothAdapter bt;
    volatile BluetoothSocket sock;
    volatile boolean wantConnected = false;
    volatile boolean destroyed = false;
    volatile int ioGen = 0;
    volatile String statusJson = "{\"state\":\"idle\",\"device\":\"\"}";
    SharedPreferences prefs;
    final Handler ui = new Handler(Looper.getMainLooper());

    AlertDialog pickDlg;
    ArrayAdapter<String> pickAdapter;
    final ArrayList<BluetoothDevice> pickDevs = new ArrayList<>();
    BroadcastReceiver scanReceiver;

    // ------------------------------------------------------------ lifecycle
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("kijun", MODE_PRIVATE);
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bt = bm != null ? bm.getAdapter() : null;

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        loader = new WebViewAssetLoader.Builder()
                .setDomain(BuildConfig.WEB_HOST)
                .addPathHandler(BuildConfig.WEB_BASE, new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.addJavascriptInterface(new Bridge(), "AndroidGNSS");
        shim = readAsset("_native/gnss-bridge.js");
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(web, shim, Collections.singleton("*"));
                shimAtStart = true;
            } catch (Exception ignored) { }
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (!shimAtStart) v.evaluateJavascript(shim, null);
                pushStatus();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sch = u.getScheme();
                if ("http".equals(sch) || "https".equals(sch)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, true, false);
            }

            @Override
            public void onPermissionRequest(PermissionRequest req) {
                ui.post(() -> req.grant(req.getResources()));
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                return openFileChooser(cb, p);
            }
        });

        web.loadUrl("https://" + BuildConfig.WEB_HOST + BuildConfig.WEB_BASE + "index.html");

        requestNeededPermissions();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        wantConnected = false;
        stopIo();
        stopScan();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) { web.goBack(); return; }
        new AlertDialog.Builder(this)
                .setMessage("앱을 종료할까요?")
                .setPositiveButton("종료", (d, w) -> finish())
                .setNegativeButton("취소", null)
                .show();
    }

    // ------------------------------------------------------------ permissions
    boolean granted(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    boolean hasBtPerm() {
        if (Build.VERSION.SDK_INT >= 31)
            return granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT);
        return granted(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    void requestNeededPermissions() {
        List<String> need = new ArrayList<>();
        need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        need.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 31) {
            need.add(Manifest.permission.BLUETOOTH_SCAN);
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT <= 28) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        List<String> ask = new ArrayList<>();
        for (String p : need) if (!granted(p)) ask.add(p);
        if (ask.isEmpty()) autoConnect();
        else requestPermissions(ask.toArray(new String[0]), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_PERM) autoConnect();
    }

    // ------------------------------------------------------------ bluetooth
    void autoConnect() {
        String addr = prefs.getString("addr", null);
        if (addr == null || !prefs.getBoolean("auto", false)) return;
        if (bt == null || !hasBtPerm()) return;
        try {
            if (!bt.isEnabled()) return;
            BluetoothDevice d = bt.getRemoteDevice(addr);
            wantConnected = true;
            startIo(d);
        } catch (Exception ignored) { }
    }

    String devName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return (n == null || n.isEmpty()) ? "(이름 없음)" : n;
        } catch (SecurityException e) {
            return "(이름 없음)";
        }
    }

    String devLabel(BluetoothDevice d, boolean bonded) {
        return devName(d) + "\n" + d.getAddress() + (bonded ? "  · 페어링됨" : "");
    }

    void openPicker() {
        if (bt == null) { toast("이 기기는 블루투스를 지원하지 않습니다"); return; }
        if (!hasBtPerm()) {
            requestNeededPermissions();
            toast("블루투스(주변 기기) 권한을 허용한 뒤 다시 눌러 주세요");
            return;
        }
        try {
            if (!bt.isEnabled()) {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                return;
            }
        } catch (SecurityException e) { toast("블루투스 권한이 없습니다"); return; }

        stopScan();
        pickDevs.clear();
        pickAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        try {
            for (BluetoothDevice d : bt.getBondedDevices()) {
                pickDevs.add(d);
                pickAdapter.add(devLabel(d, true));
            }
        } catch (SecurityException ignored) { }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("GNSS 장비 선택 (주변 검색 중…)")
                .setAdapter(pickAdapter, (dlg, which) -> {
                    stopScan();
                    connectTo(pickDevs.get(which));
                })
                .setNegativeButton("닫기", null);
        if (wantConnected) b.setNeutralButton("연결 끊기", (d, w) -> doDisconnect());
        pickDlg = b.create();
        pickDlg.setOnDismissListener(d -> stopScan());

        scanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(a)) {
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d == null) return;
                    try { if (d.getType() == BluetoothDevice.DEVICE_TYPE_LE) return; } catch (SecurityException ignored) { }
                    for (BluetoothDevice x : pickDevs) if (x.getAddress().equals(d.getAddress())) return;
                    pickDevs.add(d);
                    pickAdapter.add(devLabel(d, false));
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) {
                    if (pickDlg != null && pickDlg.isShowing()) pickDlg.setTitle("GNSS 장비 선택");
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(scanReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(scanReceiver, f);
        try { bt.startDiscovery(); } catch (SecurityException ignored) { }
        pickDlg.show();
    }

    void stopScan() {
        try { if (bt != null) bt.cancelDiscovery(); } catch (SecurityException ignored) { }
        if (scanReceiver != null) {
            try { unregisterReceiver(scanReceiver); } catch (Exception ignored) { }
            scanReceiver = null;
        }
    }

    void connectTo(BluetoothDevice d) {
        prefs.edit().putString("addr", d.getAddress()).putBoolean("auto", true).apply();
        wantConnected = true;
        startIo(d);
    }

    void doDisconnect() {
        wantConnected = false;
        prefs.edit().putBoolean("auto", false).apply();
        stopIo();
        setStatus("idle", "");
    }

    BluetoothSocket openSocket(BluetoothDevice d) throws Exception {
        Exception last = null;
        try {
            BluetoothSocket s = d.createRfcommSocketToServiceRecord(SPP);
            s.connect();
            return s;
        } catch (Exception e) { last = e; }
        try {
            BluetoothSocket s = d.createInsecureRfcommSocketToServiceRecord(SPP);
            s.connect();
            return s;
        } catch (Exception e) { last = e; }
        try {
            Method m = d.getClass().getMethod("createRfcommSocket", int.class);
            BluetoothSocket s = (BluetoothSocket) m.invoke(d, 1);
            s.connect();
            return s;
        } catch (Exception e) { last = e; }
        throw last;
    }

    void startIo(final BluetoothDevice dev) {
        stopIo();
        final int gen = ++ioGen;
        final String name = devName(dev);
        Thread t = new Thread(() -> {
            while (wantConnected && gen == ioGen && !destroyed) {
                setStatus("connecting", name);
                BluetoothSocket s = null;
                try {
                    try { bt.cancelDiscovery(); } catch (SecurityException ignored) { }
                    s = openSocket(dev);
                    if (gen != ioGen) { closeQuiet(s); break; }
                    sock = s;
                    setStatus("connected", name);
                    BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while (gen == ioGen && (line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.length() > 6 && line.charAt(0) == '$') sendLine(line);
                    }
                } catch (Exception ignored) {
                } finally {
                    closeQuiet(s);
                    if (sock == s) sock = null;
                }
                if (!wantConnected || gen != ioGen || destroyed) break;
                setStatus("retry", name);
                try { Thread.sleep(4000); } catch (InterruptedException e) { break; }
            }
        }, "gnss-io");
        t.setDaemon(true);
        t.start();
    }

    void stopIo() {
        ioGen++;
        closeQuiet(sock);
        sock = null;
    }

    void closeQuiet(BluetoothSocket s) {
        if (s == null) return;
        try { s.close(); } catch (Exception ignored) { }
    }

    void setStatus(String state, String device) {
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("device", device);
            statusJson = o.toString();
        } catch (Exception ignored) { }
        pushStatus();
    }

    void pushStatus() {
        final String js = "window.__gnssNative&&window.__gnssNative('status'," + statusJson + ")";
        ui.post(() -> { if (!destroyed) web.evaluateJavascript(js, null); });
    }

    void sendLine(String line) {
        final String js = "window.__gnssNative&&window.__gnssNative('line'," + JSONObject.quote(line) + ")";
        ui.post(() -> { if (!destroyed) web.evaluateJavascript(js, null); });
    }

    // ------------------------------------------------------------ file chooser / camera
    Intent makeCameraIntent() {
        if (Build.VERSION.SDK_INT < 29) return null;
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "kijun_" + System.currentTimeMillis() + ".jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/kijun");
            cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (cameraUri == null) return null;
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return i;
        } catch (Exception e) {
            cameraUri = null;
            return null;
        }
    }

    boolean openFileChooser(ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams p) {
        if (fileCb != null) fileCb.onReceiveValue(null);
        fileCb = cb;
        cameraUri = null;
        boolean image = false;
        String[] acc = p.getAcceptTypes();
        if (acc != null) for (String a : acc) if (a != null && a.startsWith("image")) image = true;

        Intent cam = image ? makeCameraIntent() : null;
        Intent chooser;
        if (cam != null && p.isCaptureEnabled()) {
            chooser = cam;
        } else {
            Intent pick = p.createIntent();
            if (p.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
                pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            chooser = Intent.createChooser(pick, "파일 선택");
            if (cam != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
        }
        try {
            startActivityForResult(chooser, REQ_FILE);
            return true;
        } catch (Exception e) {
            if (cameraUri != null) deleteQuiet(cameraUri);
            cameraUri = null;
            fileCb = null;
            return false;
        }
    }

    void deleteQuiet(Uri u) {
        try { getContentResolver().delete(u, null, null); } catch (Exception ignored) { }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FILE) return;
        List<Uri> out = new ArrayList<>();
        if (res == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    Uri u = data.getClipData().getItemAt(i).getUri();
                    if (u != null) out.add(u);
                }
            } else if (data.getData() != null) {
                out.add(data.getData());
            }
        }
        if (res == RESULT_OK && out.isEmpty() && cameraUri != null) out.add(cameraUri);
        if (cameraUri != null && !out.contains(cameraUri)) deleteQuiet(cameraUri);
        if (fileCb != null) fileCb.onReceiveValue(out.isEmpty() ? null : out.toArray(new Uri[0]));
        fileCb = null;
        cameraUri = null;
    }

    // ------------------------------------------------------------ helpers
    String readAsset(String path) {
        try (InputStream in = getAssets().open(path)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    void toast(String m) {
        ui.post(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
    }

    String saveToDownloads(String name, String mime, byte[] bytes) throws Exception {
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (mime == null || mime.isEmpty() || mime.equals("application/octet-stream")) {
            int dot = name.lastIndexOf('.');
            String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            mime = m != null ? m : "application/octet-stream";
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, mime);
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/kijun");
            Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (u == null) throw new Exception("저장 위치를 만들 수 없음");
            try (OutputStream os = getContentResolver().openOutputStream(u)) {
                os.write(bytes);
            }
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "kijun");
            dir.mkdirs();
            try (FileOutputStream os = new FileOutputStream(new File(dir, name))) {
                os.write(bytes);
            }
        }
        return "Download/kijun/" + name;
    }

    // ------------------------------------------------------------ JS bridge
    class Bridge {
        @JavascriptInterface
        public void pick() { ui.post(MainActivity.this::openPicker); }

        @JavascriptInterface
        public void disconnect() { ui.post(MainActivity.this::doDisconnect); }

        @JavascriptInterface
        public String status() { return statusJson; }

        @JavascriptInterface
        public void toast(String m) { MainActivity.this.toast(m); }

        @JavascriptInterface
        public String saveFile(String name, String mime, String b64) {
            try {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                String where = saveToDownloads(name, mime, bytes);
                MainActivity.this.toast("저장됨: " + where);
                return "ok:" + where;
            } catch (Exception e) {
                MainActivity.this.toast("저장 실패: " + e.getMessage());
                return "err:" + e.getMessage();
            }
        }
    }
}
