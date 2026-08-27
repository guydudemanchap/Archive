package dev.dep.bluestarbridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.text.InputType;
import android.util.Base64;
import android.view.*;
import android.widget.*;

import org.json.JSONObject;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import blufi.espressif.*;
import blufi.espressif.response.*;

@SuppressLint("MissingPermission")
public class MainActivityV3 extends Activity {
    static final int PORT = 44542;
    final Handler h = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newCachedThreadPool();

    BluetoothAdapter bt;
    BluetoothLeScanner scanner;
    ScanResult best;
    BlufiClient blufi;
    boolean prepared, secured, negotiating;

    SharedPreferences prefs;
    String uat, pendingUat, thingMac, acIp;
    DatagramSocket udp;
    volatile boolean udpRun;

    TextView status, info, identity, lastState, log;
    EditText ssid, wifiPass, temp, ip, raw;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("lab", MODE_PRIVATE);
        uat = prefs.getString("uat", null);
        thingMac = prefs.getString("thing", null);
        acIp = prefs.getString("ip", null);
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bt = bm == null ? null : bm.getAdapter();
        buildUi();
        permissions();
        startUdp();
        render();
        L("v0.3 started. Exact Blue Star BLUFI + WLAN UDP/44542. Vendor ts field enabled.");
    }

    void buildUi() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(14), dp(10), dp(14), dp(16));
        r.addView(t("BlueStar IA518VXUS Local Lab v0.3", 22));
        r.addView(t("Pairing prep: COOL 16°C + FAN AUTO, then HEALTH / SENSAIR / DISPLAY / ROOM 5× within 7 sec. Close the official Blue Star app while testing.", 13));
        status = t("Idle", 16); info = t("", 12); identity = t("Identity: unknown", 12);
        r.addView(status); r.addView(info); r.addView(identity);
        r.addView(b("SCAN + CONNECT BLUAC", v -> scan()));

        r.addView(section("1 · LOCAL BIND + WI-FI"));
        row(r, b("CLAIM LOCAL TOKEN", v -> confirmClaim()), b("READ AC IDENTITY", v -> identity()));
        row(r, b("BLUFI VERSION", v -> { if (readyBle(false)) blufi.requestDeviceVersion(); }), b("DEVICE STATUS", v -> { if (readyBle(false)) blufi.requestDeviceStatus(); }));
        r.addView(b("AC WI-FI SCAN · BLUE STAR TYPE 3", v -> wifiScan()));
        ssid = e("2.4 GHz Wi-Fi SSID"); wifiPass = e("Wi-Fi password — never saved/logged"); wifiPass.setInputType(129);
        r.addView(ssid); r.addView(wifiPass);
        row(r, b("PROVISION WI-FI · TYPE 2", v -> provision()), b("STOP ONBOARDING · TYPE 7", v -> stopOnboarding()));
        r.addView(b("DISCONNECT BLE · KEEP LAN LISTENER", v -> disconnectBle()));

        r.addView(section("2 · POWER + TEMPERATURE"));
        row(r, b("AUTO POWER ON", v -> power(0, true)), b("AUTO POWER OFF", v -> power(0, false)));
        row(r, b("FORCE BLE ON", v -> power(1, true)), b("FORCE BLE OFF", v -> power(1, false)));
        row(r, b("FORCE LAN ON", v -> power(2, true)), b("FORCE LAN OFF", v -> power(2, false)));
        temp = e("Target temperature 16–30°C"); temp.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); r.addView(temp);
        row(r, b("SET TEMP AUTO", v -> setTemp(0)), b("SET TEMP LAN", v -> setTemp(2)));

        r.addView(section("3 · SAME-WI-FI LOCAL"));
        ip = e("AC LAN IP · learned automatically from UDP state"); if (acIp != null) ip.setText(acIp); r.addView(ip);
        r.addView(t("Blue Star's app listens on UDP 44542. State packets are (MAC|Base64Ciphertext). LAN commands use src=anlan and are repeated 25× at 100 ms, matching the vendor app.", 12));

        r.addView(section("4 · NORMAL FEATURE COMMANDS · AUTO LAN→BLE"));
        r.addView(t("These four are exact single-field commands recovered from Blue Star ThingService; values are integer 1/0.", 12));
        row(r, b("HEALTH ON", v -> feature("health", 1)), b("HEALTH OFF", v -> feature("health", 0)));
        row(r, b("DISPLAY ON", v -> feature("display", 1)), b("DISPLAY OFF", v -> feature("display", 0)));
        row(r, b("SELF CLEAN ON", v -> feature("s_clean", 1)), b("SELF CLEAN OFF", v -> feature("s_clean", 0)));
        row(r, b("DEFROST CLEAN ON", v -> feature("df_clean", 1)), b("DEFROST CLEAN OFF", v -> feature("df_clean", 0)));

        r.addView(section("5 · EXPERT NORMAL-STATE CONSOLE"));
        r.addView(t("Known vendor keys include pow, stemp, fspd, mode, vswing, hswing, swing_4way, health, display, s_clean, df_clean, m_buz, esave, ai. Fan/mode/swing numeric maps are model-specific, so we capture your state before guessing them.", 12));
        raw = e("Desired JSON only, e.g. {\"m_buz\":1}"); r.addView(raw);
        row(r, b("SEND AUTO", v -> raw(false)), b("SEND LAN ONLY", v -> raw(true)));

        r.addView(section("6 · STATE / DIAGNOSTICS"));
        lastState = t("Last decoded state: none", 11); lastState.setTextIsSelectable(true); r.addView(lastState);
        row(r, b("COPY LAST STATE", v -> copy(lastState.getText(), "State copied")), b("RESTART UDP", v -> { stopUdp(); h.postDelayed(this::startUdp, 250); }));
        row(r, b("COPY LOG", v -> copy(log == null ? "" : log.getText(), "Log copied")), b("CLEAR TOKEN + IP", v -> clearConfirm()));
        log = t("", 11); log.setTextIsSelectable(true); ScrollView ls = new ScrollView(this); ls.addView(log); r.addView(ls, new LinearLayout.LayoutParams(-1, dp(300)));
        ScrollView outer = new ScrollView(this); outer.addView(r); setContentView(outer);
    }

    void render() {
        info.setText("UAT " + mask(uat) + (thingMac == null ? "" : " · MAC " + thingMac) + (acIp == null ? "" : " · LAN " + acIp + ":" + PORT));
    }

    void permissions() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, 9);
        else if (Build.VERSION.SDK_INT < 31 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 9);
    }

    final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            String n = r.getDevice().getName();
            if (n != null && n.startsWith("BLUAC_") && (best == null || r.getRssi() > best.getRssi())) {
                best = r; status.setText("Found " + n + " RSSI " + r.getRssi());
            }
        }
        @Override public void onScanFailed(int e) { L("BLE scan failed=" + e); }
    };

    void scan() {
        if (bt == null || !bt.isEnabled()) { toast("Turn Bluetooth on"); return; }
        best = null; scanner = bt.getBluetoothLeScanner(); status.setText("Scanning BLUAC…");
        scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb);
        h.postDelayed(() -> {
            try { scanner.stopScan(scanCb); } catch (Exception ignored) {}
            if (best == null) { status.setText("No BLUAC found"); return; }
            connect(best.getDevice());
        }, 6500);
    }

    void connect(BluetoothDevice d) {
        disconnectBle();
        String n = d.getName(); if (n == null) n = "unnamed";
        if (n.startsWith("BLUAC_")) {
            thingMac = n.substring(6).replace(":", "").toLowerCase(Locale.ENGLISH);
            prefs.edit().putString("thing", thingMac).apply(); render();
        }
        status.setText("Connecting " + n); L("Connect " + n + " / " + d.getAddress());
        blufi = new BlufiClient(getApplicationContext(), d); blufi.setGattWriteTimeout(5000);
        blufi.setGattCallback(new BluetoothGattCallback() {
            @Override public void onMtuChanged(BluetoothGatt g, int mtu, int s) { L("MTU=" + mtu + " status=" + s); negotiate(); }
            @Override public void onConnectionStateChange(BluetoothGatt g, int s, int ns) { if (ns == BluetoothProfile.STATE_DISCONNECTED) L("GATT disconnected status=" + s); }
        });
        blufi.setBlufiCallback(new C()); blufi.connect();
    }

    void negotiate() { if (blufi != null && !negotiating) { negotiating = true; L("BLUFI security negotiation"); blufi.negotiateSecurity(); } }

    class C extends BlufiCallback {
        @Override public void onGattPrepared(BlufiClient c, BluetoothGatt g, BluetoothGattService s, BluetoothGattCharacteristic w, BluetoothGattCharacteristic n) {
            if (s == null || w == null || n == null) { L("Not a BLUFI endpoint"); return; }
            prepared = true; status.setText("BLUFI found · securing"); L("BLUFI FFFF/FF01/FF02 FOUND");
            boolean q = false; try { q = g.requestMtu(512); } catch (Exception ignored) {}
            if (!q) c.setPostPackageLengthLimit(20);
            h.postDelayed(MainActivityV3.this::negotiate, q ? 900 : 100);
        }
        @Override public void onNegotiateSecurityResult(BlufiClient c, int s) { secured = s == STATUS_SUCCESS; status.setText(secured ? "BLUFI secure / ready" : "Security failed"); L(secured ? "SECURITY SUCCESS" : "SECURITY FAIL " + s); }
        @Override public void onPostCustomDataResult(BlufiClient c, int s, byte[] d) { custom("POST", s, d); }
        @Override public void onReceiveCustomData(BlufiClient c, int s, byte[] d) { custom("RX", s, d); }
        @Override public void onDeviceScanResult(BlufiClient c, int s, List<BlufiScanResult> rs) { L("Wi-Fi scan status=" + s + " count=" + (rs == null ? 0 : rs.size())); if (rs != null) for (BlufiScanResult x : rs) L("AP " + x); }
        @Override public void onDeviceStatusResponse(BlufiClient c, int s, BlufiStatusResponse q) { if (q != null) L("STATUS mode=" + q.getOpMode() + " connected=" + q.isStaConnectWifi() + " ssid=" + q.getStaSSID() + " bssid=" + q.getStaBSSID()); }
        @Override public void onDeviceVersionResponse(BlufiClient c, int s, BlufiVersionResponse q) { L("BLUFI version status=" + s + " value=" + (q == null ? "null" : q.getVersionString())); }
        @Override public void onError(BlufiClient c, int e) { L("BLUFI ERROR " + e); }
    }

    void confirmClaim() {
        if (!readyBle(true)) return;
        new AlertDialog.Builder(this).setTitle("Claim local token?").setMessage("This sends Blue Star's real bind command (type 0). It can replace the official app's pairing token. Continue?")
                .setNegativeButton("Cancel", null).setPositiveButton("Claim", (d, w) -> claim()).show();
    }
    void claim() { try { pendingUat = UUID.randomUUID().toString(); JSONObject j = new JSONObject(); j.put("type", 0); j.put("uat", pendingUat); L("Bind type0 " + mask(pendingUat)); post(j); } catch (Exception e) { L("bind " + e); } }
    void identity() { if (!tokenBle()) return; try { JSONObject j = new JSONObject(); j.put("type", 6); j.put("uat", uat); post(j); L("type6 identity request"); } catch (Exception e) { L("id " + e); } }
    void wifiScan() { if (!tokenBle()) return; try { JSONObject j = new JSONObject(); j.put("type", 3); j.put("uat", uat); post(j); L("type3 Wi-Fi scan request"); } catch (Exception e) { L("scan " + e); } }
    void provision() {
        if (!tokenBle()) return; String s = ssid.getText().toString().trim(); if (s.isEmpty()) { toast("Enter SSID"); return; }
        try { JSONObject j = new JSONObject(); j.put("type", 2); j.put("uat", uat); j.put("ssid", s); j.put("pswd", wifiPass.getText().toString()); post(j); L("type2 provision SSID=" + s + " password hidden");
            for (int i = 1; i <= 6; i++) h.postDelayed(() -> { if (blufi != null) blufi.requestDeviceStatus(); }, 3000L * i);
        } catch (Exception e) { L("provision " + e); }
    }
    void stopOnboarding() { if (!tokenBle()) return; try { JSONObject j = new JSONObject(); j.put("type", 7); j.put("uat", uat); post(j); L("type7 stop onboarding sent"); } catch (Exception e) { L("stop " + e); } }
    void post(JSONObject j) { if (blufi != null) blufi.postCustomData(j.toString().getBytes(StandardCharsets.UTF_8)); }

    void custom(String src, int s, byte[] d) {
        if (s != 0 || d == null) return;
        String z = new String(d, StandardCharsets.UTF_8).trim(); L(src + " ← " + redact(z));
        try {
            JSONObject j = new JSONObject(z); int type = j.optInt("type", -1);
            if (type == 0 && pendingUat != null && pendingUat.equalsIgnoreCase(j.optString("uat"))) {
                uat = pendingUat; pendingUat = null; prefs.edit().putString("uat", uat).apply(); render(); L("BIND ACK; local UAT stored"); identity();
            } else if (type == 6) {
                String x = "PID=" + j.optString("pid") + " board=" + j.optString("boardId") + " rmt=" + j.optInt("rmtType", 1000) + " idu=" + j.optInt("iduType", -1) + " FW=" + j.optString("iduMainFv", "?");
                identity.setText(x); L("IDENTITY " + x);
            } else if (type == 4) {
                int r = j.optInt("reason", -1); String[] m = {"SUCCESS", "AP NOT FOUND", "INCORRECT PASSWORD", "ROUTER FILTERING", "ROUTER OVERLOAD"}; L("Wi-Fi result reason=" + r + " " + (r >= 0 && r < m.length ? m[r] : "UNKNOWN"));
            } else if (type == 5) envelope("BLE", null, j.optString("state"));
        } catch (Exception ignored) {}
    }

    void power(int route, boolean on) { try { JSONObject d = new JSONObject(); d.put("pow", on ? 1 : 0); desired(route, d); } catch (Exception e) { L("power " + e); } }
    void setTemp(int route) { try { double v = Double.parseDouble(temp.getText().toString()); if (v < 16 || v > 30) { toast("16–30°C only"); return; } JSONObject d = new JSONObject(); d.put("stemp", String.format(Locale.ENGLISH, "%.1f", v)); desired(route, d); } catch (Exception e) { toast("Enter temp like 24"); } }
    void feature(String key, int val) { try { JSONObject d = new JSONObject(); d.put(key, val); desired(0, d); } catch (Exception e) { L("feature " + e); } }

    void raw(boolean lanOnly) {
        try {
            String x = raw.getText().toString().trim(); if (x.isEmpty()) { toast("Enter JSON desired fields"); return; }
            JSONObject d = new JSONObject(x);
            for (String k : new String[]{"uat", "type", "state", "src", "ts"}) if (d.has(k)) { toast("Desired fields only; reserved key: " + k); return; }
            Iterator<String> it = d.keys(); while (it.hasNext()) { String k = it.next().toLowerCase(Locale.ENGLISH); if (k.contains("ota") || k.contains("firmware") || k.equals("fw") || k.contains("update")) { toast("Firmware/OTA fields are intentionally blocked"); return; } }
            desired(lanOnly ? 2 : 0, d);
        } catch (Exception e) { toast("Bad JSON: " + e.getMessage()); }
    }

    void desired(int route, JSONObject d) throws Exception {
        if (!hasToken()) { toast("Claim local token first"); return; }
        if (route == 0) route = (ip != null && !ip.getText().toString().trim().isEmpty()) ? 2 : 1;
        d.put("ts", System.currentTimeMillis());
        if (route == 1) {
            if (!readyBle(true)) return; d.put("src", "anble"); JSONObject st = new JSONObject(); st.put("desired", d); JSONObject j = new JSONObject(); j.put("type", 1); j.put("uat", uat); j.put("state", st); L("BLE desired → " + d); post(j); return;
        }
        String host = ip.getText().toString().trim(); if (host.isEmpty()) { toast("Need AC LAN IP; wait for UDP state or enter it"); return; }
        d.put("src", "anlan"); JSONObject st = new JSONObject(); st.put("desired", d); JSONObject j = new JSONObject(); j.put("type", 1); j.put("uat", uat); j.put("state", st);
        String enc = encrypt(j.toString(), uat.substring(0, 16)); L("LAN desired → " + d + " @ " + host + ":" + PORT); io.execute(() -> burst(host, enc));
    }

    void burst(String host, String data) {
        try (DatagramSocket s = new DatagramSocket()) {
            byte[] b = data.getBytes(StandardCharsets.US_ASCII); DatagramPacket p = new DatagramPacket(b, b.length, InetAddress.getByName(host), PORT);
            for (int i = 0; i < 25; i++) { s.send(p); Thread.sleep(100); }
            L("LAN burst complete");
        } catch (Exception e) { L("LAN send " + e); }
    }

    void startUdp() {
        if (udpRun) return; udpRun = true;
        io.execute(() -> {
            try {
                DatagramSocket s = new DatagramSocket(null); s.setReuseAddress(true); s.bind(new InetSocketAddress(PORT)); udp = s; L("UDP listener bound :44542");
                while (udpRun) {
                    byte[] b = new byte[4096]; DatagramPacket p = new DatagramPacket(b, b.length); s.receive(p);
                    String m = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.US_ASCII).trim(); String src = p.getAddress().getHostAddress();
                    if (m.startsWith("(") && m.endsWith(")") && m.contains("|")) h.post(() -> envelope("UDP", src, m));
                }
            } catch (Exception e) { if (udpRun) L("UDP listener error; close official app: " + e); }
            finally { udpRun = false; }
        });
    }
    void stopUdp() { udpRun = false; try { if (udp != null) udp.close(); } catch (Exception ignored) {} udp = null; }

    void envelope(String via, String src, String m) {
        try {
            int k = m.indexOf('|'); String mac = m.substring(1, k).trim().toLowerCase(Locale.ENGLISH); String b64 = m.substring(k + 1, m.length() - 1).trim();
            thingMac = mac; prefs.edit().putString("thing", mac).apply();
            if (src != null) { acIp = src; prefs.edit().putString("ip", src).apply(); ip.setText(src); }
            render();
            if (!hasToken()) { L(via + " state seen from " + mac + "; no UAT to decrypt"); return; }
            String js = clean(decrypt(b64, uat.substring(0, 16))); JSONObject q = new JSONObject(js);
            lastState.setText("Last decoded state (" + via + "):\n" + q.toString(2)); L(via + " STATE ← " + q);
        } catch (Exception e) { L(via + " decode " + e.getMessage()); }
    }

    static String encrypt(String raw, String key) throws Exception {
        byte[] iv = new byte[16]; new SecureRandom().nextBytes(iv);
        Cipher c; try { c = Cipher.getInstance("AES/CBC/PKCS7Padding"); } catch (Exception e) { c = Cipher.getInstance("AES/CBC/PKCS5Padding"); }
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII), "AES"), new IvParameterSpec(iv));
        byte[] x = raw.getBytes(StandardCharsets.US_ASCII), p = new byte[16 + x.length]; System.arraycopy(iv, 0, p, 0, 16); System.arraycopy(x, 0, p, 16, x.length);
        return Base64.encodeToString(c.doFinal(p), Base64.NO_WRAP);
    }
    static byte[] decrypt(String b64, String key) throws Exception {
        byte[] a = Base64.decode(b64, Base64.NO_WRAP); Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII), "AES"), new IvParameterSpec(Arrays.copyOfRange(a, 0, 16)));
        return c.doFinal(Arrays.copyOfRange(a, 16, a.length));
    }
    static String clean(byte[] b) { int n = b.length; while (n > 0 && (b[n - 1] & 255) <= 32) n--; return new String(b, 0, n, StandardCharsets.US_ASCII).trim(); }

    boolean readyBle(boolean secure) { if (blufi == null || !prepared) { toast("Connect BLUAC first"); return false; } if (secure && !secured) { toast("Wait for BLUFI security success"); return false; } return true; }
    boolean hasToken() { return uat != null && uat.length() >= 16; }
    boolean tokenBle() { if (!hasToken()) { toast("Claim local token first"); return false; } return readyBle(true); }
    void disconnectBle() { if (blufi != null) try { blufi.close(); } catch (Exception ignored) {} blufi = null; prepared = secured = negotiating = false; status.setText("BLE disconnected · UDP listener stays active"); }

    void clearConfirm() {
        new AlertDialog.Builder(this).setTitle("Clear this app's local pairing data?").setMessage("Forgets UAT, learned MAC and LAN IP. It does not erase the AC's Wi-Fi settings.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear", (d, w) -> { uat = pendingUat = thingMac = acIp = null; prefs.edit().clear().apply(); if (ip != null) ip.setText(""); identity.setText("Identity: unknown"); render(); L("Local token/IP cleared"); }).show();
    }

    String redact(String x) { try { JSONObject j = new JSONObject(x); if (j.has("uat")) j.put("uat", mask(j.optString("uat"))); if (j.has("pswd")) j.put("pswd", "***"); return j.toString(); } catch (Exception e) { return x; } }
    static String mask(String s) { return s == null ? "none" : s.length() < 10 ? "***" : s.substring(0, 4) + "…" + s.substring(s.length() - 4); }
    void L(String s) { h.post(() -> { if (log != null) log.append(String.format(Locale.ENGLISH, "%tT %s\n", System.currentTimeMillis(), s)); }); }
    void copy(CharSequence s, String msg) { ClipboardManager c = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE); if (c != null) c.setPrimaryClip(ClipData.newPlainText("BlueStar", s)); toast(msg); }
    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    void row(LinearLayout r, Button a, Button b) { LinearLayout x = new LinearLayout(this); x.addView(a, new LinearLayout.LayoutParams(0, -2, 1)); x.addView(b, new LinearLayout.LayoutParams(0, -2, 1)); r.addView(x); }
    TextView t(String s, int z) { TextView v = new TextView(this); v.setText(s); v.setTextSize(z); return v; }
    TextView section(String s) { TextView v = t(s, 16); v.setPadding(0, dp(12), 0, dp(3)); return v; }
    Button b(String s, View.OnClickListener l) { Button v = new Button(this); v.setText(s); v.setTextSize(11); v.setOnClickListener(l); return v; }
    EditText e(String s) { EditText v = new EditText(this); v.setHint(s); v.setSingleLine(true); return v; }
    int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() { try { if (scanner != null) scanner.stopScan(scanCb); } catch (Exception ignored) {} disconnectBle(); stopUdp(); io.shutdownNow(); super.onDestroy(); }
}
