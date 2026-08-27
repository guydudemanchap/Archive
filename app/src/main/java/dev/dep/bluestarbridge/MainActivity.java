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

import org.json.JSONArray;
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
public class MainActivity extends Activity {
    static final int PORT = 44542;
    static final Set<String> CONTROL_KEYS = new LinkedHashSet<>(Arrays.asList(
            "pow","stemp","fspd","mode","turbo","sleep","display","health",
            "hswing","vswing","swing_4way","ai","eco","esave","s_clean","df_clean",
            "m_buz","displayunit","irest","irest_tmr","fspd_lock","stemp_lock",
            "mode_lock","on_lock","off_lock","fixlock"
    ));

    final Handler h = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newCachedThreadPool();

    BluetoothAdapter bt;
    BluetoothLeScanner scanner;
    BlufiClient blufi;
    ScanResult best;
    boolean secure, securityStarted;

    SharedPreferences prefs;
    String uat, pendingUat, thingMac, acIp;
    volatile boolean udpListening;
    DatagramSocket udpSocket;
    long lastUdpMs;

    JSONObject lastPacket;
    JSONObject lastControlState;
    JSONObject trainerBaseline;
    String trainerLabel;
    boolean trainerArmed;
    final LinkedHashMap<String, JSONObject> learned = new LinkedHashMap<>();

    TextView stateText, infoText, lastText, diffText, logText;
    EditText ssidEdit, passwordEdit, tempEdit, ipEdit, trainLabelEdit;
    Spinner learnedSpinner;
    ArrayAdapter<String> learnedAdapter;

    Button claimBtn, identityBtn, wifiScanBtn, provisionBtn, stopOnboardingBtn, disconnectBleBtn;
    Button bleOnBtn, bleOffBtn, bleTempBtn, lanOnBtn, lanOffBtn, lanTempBtn;
    Button trainBtn, replayBleBtn, replayLanBtn;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("lab", 0);
        uat = prefs.getString("uat", null);
        thingMac = prefs.getString("thing", null);
        acIp = prefs.getString("ip", null);
        loadLearned();
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bt = bm == null ? null : bm.getAdapter();
        buildUi();
        requestPermissionsIfNeeded();
        startUdpListener();
        log("v0.3: timestamps + trainer + replay + type7 + standalone LAN.");
    }

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        root.addView(text("BlueStar IA518VXUS Local Lab v0.3", 22));
        root.addView(text("Pairing: COOL 16°C + FAN AUTO → HEALTH / SENSAIR / DISPLAY / ROOM 5× in 7 sec. Force-stop the official Blue Star app while testing.", 13));
        stateText = text("Idle", 16);
        infoText = text("", 12);
        root.addView(stateText);
        root.addView(infoText);

        root.addView(button("SCAN + CONNECT BLUAC", v -> scan()));
        claimBtn = button("CLAIM LOCAL TOKEN", v -> confirmClaim());
        identityBtn = button("READ AC IDENTITY", v -> requestIdentity());
        addRow(root, claimBtn, identityBtn);

        wifiScanBtn = button("AC WI-FI SCAN (TYPE 3)", v -> requestWifiScan());
        root.addView(wifiScanBtn);
        ssidEdit = edit("2.4 GHz SSID");
        passwordEdit = edit("Wi-Fi password (never saved/logged)");
        passwordEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(ssidEdit);
        root.addView(passwordEdit);
        provisionBtn = button("PROVISION WI-FI (TYPE 2)", v -> provisionWifi());
        root.addView(provisionBtn);

        stopOnboardingBtn = button("STOP ONBOARDING (TYPE 7)", v -> stopOnboarding());
        disconnectBleBtn = button("DISCONNECT BLUETOOTH", v -> closeBle());
        addRow(root, stopOnboardingBtn, disconnectBleBtn);

        root.addView(text("Direct control", 17));
        bleOnBtn = button("BLE ON", v -> power(false, true));
        bleOffBtn = button("BLE OFF", v -> power(false, false));
        addRow(root, bleOnBtn, bleOffBtn);
        tempEdit = edit("Temperature 16–30");
        tempEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        root.addView(tempEdit);
        bleTempBtn = button("SET TEMP BLE", v -> temperature(false));
        root.addView(bleTempBtn);

        ipEdit = edit("AC LAN IP (auto-learns from UDP)");
        if (acIp != null) ipEdit.setText(acIp);
        root.addView(ipEdit);
        lanOnBtn = button("LAN ON", v -> power(true, true));
        lanOffBtn = button("LAN OFF", v -> power(true, false));
        addRow(root, lanOnBtn, lanOffBtn);
        lanTempBtn = button("SET TEMP LAN", v -> temperature(true));
        root.addView(lanTempBtn);

        root.addView(text("Protocol trainer", 17));
        root.addView(text("Give the change a name, arm the trainer, then press ONE button on the physical remote. The next AC state packet is diffed and saved as a replayable command.", 12));
        trainLabelEdit = edit("Label, e.g. Fan Low / Swing / Turbo");
        root.addView(trainLabelEdit);
        trainBtn = button("ARM TRAINER FROM CURRENT STATE", v -> armTrainer());
        root.addView(trainBtn);
        diffText = text("Trainer: idle", 12);
        diffText.setTextIsSelectable(true);
        root.addView(diffText);

        learnedSpinner = new Spinner(this);
        learnedAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>(learned.keySet()));
        learnedAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        learnedSpinner.setAdapter(learnedAdapter);
        root.addView(learnedSpinner);
        replayBleBtn = button("REPLAY LEARNED VIA BLE", v -> replayLearned(false));
        replayLanBtn = button("REPLAY LEARNED VIA LAN", v -> replayLearned(true));
        addRow(root, replayBleBtn, replayLanBtn);

        lastText = text("Last decoded state: none", 11);
        lastText.setTextIsSelectable(true);
        root.addView(lastText);

        addRow(root,
                button("DEVICE STATUS", v -> { if (blufi != null) blufi.requestDeviceStatus(); }),
                button("COPY LOG", v -> copyLog()));

        logText = text("", 11);
        logText.setTextIsSelectable(true);
        ScrollView logs = new ScrollView(this);
        logs.addView(logText);
        root.addView(logs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));

        ScrollView outer = new ScrollView(this);
        outer.addView(root);
        setContentView(outer);
        refreshButtons();
        refreshInfo();
    }

    void addRow(LinearLayout root, Button a, Button b) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(a, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(row);
    }

    TextView text(String s, int size) { TextView v = new TextView(this); v.setText(s); v.setTextSize(size); return v; }
    Button button(String s, View.OnClickListener l) { Button b = new Button(this); b.setText(s); b.setOnClickListener(l); return b; }
    EditText edit(String hint) { EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(true); return e; }
    int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }

    void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, 9);
        }
    }

    final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult r) {
            String n = r.getDevice().getName();
            if (n != null && n.startsWith("BLUAC_") && (best == null || r.getRssi() > best.getRssi())) {
                best = r;
                h.post(() -> stateText.setText("Found " + n + " RSSI " + r.getRssi()));
            }
        }
    };

    void scan() {
        if (bt == null || !bt.isEnabled()) { toast("Turn Bluetooth on"); return; }
        best = null;
        scanner = bt.getBluetoothLeScanner();
        stateText.setText("Scanning BLUAC…");
        scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
        h.postDelayed(() -> {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
            if (best == null) { stateText.setText("No BLUAC found"); return; }
            connect(best.getDevice());
        }, 6000);
    }

    void connect(BluetoothDevice d) {
        closeBle();
        String name = d.getName();
        if (name != null && name.startsWith("BLUAC_")) {
            thingMac = name.substring(6).toLowerCase(Locale.ENGLISH);
            prefs.edit().putString("thing", thingMac).apply();
        }
        refreshInfo();
        log("Connect " + name + " / " + d.getAddress());
        blufi = new BlufiClient(getApplicationContext(), d);
        blufi.setGattWriteTimeout(5000);
        blufi.setGattCallback(new BluetoothGattCallback() {
            @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
                log("MTU " + mtu + " status " + status);
                startSecurity();
            }
        });
        blufi.setBlufiCallback(new ProtocolCallback());
        blufi.connect();
    }

    void startSecurity() {
        if (blufi != null && !securityStarted) {
            securityStarted = true;
            log("Security negotiation");
            blufi.negotiateSecurity();
        }
    }

    class ProtocolCallback extends BlufiCallback {
        @Override public void onGattPrepared(BlufiClient c, BluetoothGatt g, BluetoothGattService s, BluetoothGattCharacteristic w, BluetoothGattCharacteristic n) {
            if (s == null || w == null || n == null) { log("Not BLUFI"); return; }
            log("BLUFI FFFF/FF01/FF02 FOUND");
            boolean requested = false;
            try { requested = g.requestMtu(512); } catch (Exception ignored) {}
            if (!requested) {
                c.setPostPackageLengthLimit(20);
                startSecurity();
            } else {
                h.postDelayed(MainActivity.this::startSecurity, 1500);
            }
        }

        @Override public void onNegotiateSecurityResult(BlufiClient c, int status) {
            secure = status == STATUS_SUCCESS;
            log(secure ? "SECURITY SUCCESS" : "SECURITY FAIL " + status);
            stateText.setText(secure ? "BLUFI secure / ready" : "Security failed");
            refreshButtons();
        }

        @Override public void onPostCustomDataResult(BlufiClient c, int status, byte[] data) { handleCustom("POST", status, data); }
        @Override public void onReceiveCustomData(BlufiClient c, int status, byte[] data) { handleCustom("RX", status, data); }

        @Override public void onDeviceScanResult(BlufiClient c, int status, List<BlufiScanResult> results) {
            log("Wi-Fi scan status=" + status + " count=" + (results == null ? 0 : results.size()));
            if (results != null) for (BlufiScanResult x : results) log("AP " + x);
        }

        @Override public void onDeviceStatusResponse(BlufiClient c, int status, BlufiStatusResponse q) {
            if (q != null) log("STATUS mode=" + q.getOpMode() + " connected=" + q.isStaConnectWifi() + " ssid=" + q.getStaSSID() + " bssid=" + q.getStaBSSID());
        }

        @Override public void onError(BlufiClient c, int e) { log("BLUFI ERROR " + e); }
    }

    void confirmClaim() {
        if (!secure) return;
        new AlertDialog.Builder(this)
                .setTitle("Claim local token?")
                .setMessage("Sends Blue Star bind type 0. This can replace the official app's existing local pairing token.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Claim", (d, w) -> claimToken())
                .show();
    }

    void claimToken() {
        try {
            pendingUat = UUID.randomUUID().toString();
            JSONObject j = new JSONObject(); j.put("type", 0); j.put("uat", pendingUat);
            log("Bind type0 " + mask(pendingUat));
            postBle(j);
        } catch (Exception e) { log("bind " + e); }
    }

    void requestIdentity() { sendSimpleType(6, "type6 identity request"); }
    void requestWifiScan() { sendSimpleType(3, "type3 Wi-Fi scan request"); }

    void sendSimpleType(int type, String message) {
        if (!bleReady()) return;
        try { JSONObject j = new JSONObject(); j.put("type", type); j.put("uat", uat); postBle(j); log(message); }
        catch (Exception e) { log("type" + type + " " + e); }
    }

    void provisionWifi() {
        if (!bleReady()) return;
        String ssid = ssidEdit.getText().toString();
        if (ssid.trim().isEmpty()) { toast("Enter SSID"); return; }
        try {
            JSONObject j = new JSONObject();
            j.put("type", 2); j.put("uat", uat); j.put("ssid", ssid); j.put("pswd", passwordEdit.getText().toString());
            postBle(j);
            log("type2 provision SSID=" + ssid + " password hidden");
            for (int i = 1; i <= 8; i++) h.postDelayed(() -> { if (blufi != null) blufi.requestDeviceStatus(); }, 3000L * i);
        } catch (Exception e) { log("provision " + e); }
    }

    void stopOnboarding() {
        if (!bleReady()) return;
        try {
            JSONObject j = new JSONObject(); j.put("type", 7); j.put("uat", uat);
            postBle(j); log("type7 stop onboarding sent");
        } catch (Exception e) { log("type7 " + e); }
    }

    void handleCustom(String src, int status, byte[] data) {
        if (status != BlufiCallback.STATUS_SUCCESS || data == null) return;
        String raw = new String(data, StandardCharsets.UTF_8).trim();
        log(src + " ← " + redact(raw));
        try {
            JSONObject j = new JSONObject(raw);
            int type = j.optInt("type", -1);
            if (type == 0 && pendingUat != null && pendingUat.equalsIgnoreCase(j.optString("uat"))) {
                uat = pendingUat; pendingUat = null;
                prefs.edit().putString("uat", uat).apply();
                refreshInfo(); refreshButtons();
                log("BIND ACK; UAT accepted");
                requestIdentity();
            } else if (type == 6) {
                log("IDENTITY pid=" + j.optString("pid") + " board=" + j.optString("boardId") + " rmt=" + j.optInt("rmtType", 1000)
                        + " idu=" + j.optString("iduType") + " iduMain=" + j.optString("iduMainFv") + " iduEE=" + j.optString("iduEEFv")
                        + " oduMain=" + j.optString("oduMainFv") + " boot=" + j.optString("bootFv"));
            } else if (type == 4) {
                log("Wi-Fi result reason=" + j.optInt("reason", -1) + " (0 success,1 AP missing,2 bad password,3 filter,4 overload)");
            } else if (type == 5) {
                decodeEnvelope("BLE", null, j.optString("state"));
            }
        } catch (Exception ignored) {}
    }

    void postBle(JSONObject j) {
        if (blufi == null) return;
        blufi.postCustomData(j.toString().getBytes(StandardCharsets.UTF_8));
    }

    boolean bleReady() {
        if (!secure || uat == null) { toast("Need secure BLE + local token"); return false; }
        return true;
    }

    void power(boolean lan, boolean on) {
        try { JSONObject d = new JSONObject(); d.put("pow", on ? 1 : 0); sendDesired(lan, d); }
        catch (Exception e) { log("power " + e); }
    }

    void temperature(boolean lan) {
        try {
            double value = Double.parseDouble(tempEdit.getText().toString());
            if (value < 16 || value > 30) { toast("16–30 only"); return; }
            JSONObject d = new JSONObject(); d.put("stemp", String.format(Locale.ENGLISH, "%.1f", value));
            sendDesired(lan, d);
        } catch (Exception e) { toast("Enter temperature like 24"); }
    }

    void sendDesired(boolean lan, JSONObject d) throws Exception {
        if (uat == null || uat.length() < 16) { toast("Need local token"); return; }
        if (!lan && !bleReady()) return;
        d.put("src", lan ? "anlan" : "anble");
        d.put("ts", System.currentTimeMillis());
        JSONObject state = new JSONObject(); state.put("desired", d);
        JSONObject packet = new JSONObject(); packet.put("type", 1); packet.put("uat", uat); packet.put("state", state);
        if (!lan) {
            log("BLE desired → " + d);
            postBle(packet);
            return;
        }
        String host = ipEdit.getText().toString().trim();
        if (host.isEmpty()) { toast("Wait for LAN IP or enter it"); return; }
        String encrypted = encryptVendor(packet.toString(), uat.substring(0, 16));
        log("LAN desired → " + d + " @ " + host + ":" + PORT);
        io.execute(() -> sendLanBurst(host, encrypted));
    }

    void sendLanBurst(String host, String data) {
        try (DatagramSocket s = new DatagramSocket()) {
            byte[] b = data.getBytes(StandardCharsets.US_ASCII);
            DatagramPacket p = new DatagramPacket(b, b.length, InetAddress.getByName(host), PORT);
            for (int i = 0; i < 25; i++) { s.send(p); Thread.sleep(100); }
            log("LAN burst complete");
        } catch (Exception e) { log("LAN send " + e); }
    }

    void startUdpListener() {
        udpListening = true;
        io.execute(() -> {
            try {
                DatagramSocket s = new DatagramSocket(null);
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(PORT));
                udpSocket = s;
                log("UDP listener bound :" + PORT);
                while (udpListening) {
                    byte[] b = new byte[4096];
                    DatagramPacket p = new DatagramPacket(b, b.length);
                    s.receive(p);
                    String message = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII).trim();
                    String src = p.getAddress().getHostAddress();
                    if (message.startsWith("(") && message.endsWith(")") && message.contains("|")) {
                        h.post(() -> decodeEnvelope("UDP", src, message));
                    }
                }
            } catch (Exception e) { if (udpListening) log("UDP listener error; close official app: " + e); }
        });
    }

    void decodeEnvelope(String via, String src, String envelope) {
        try {
            int split = envelope.indexOf('|');
            String mac = envelope.substring(1, split).toLowerCase(Locale.ENGLISH);
            String b64 = envelope.substring(split + 1, envelope.length() - 1);
            thingMac = mac;
            prefs.edit().putString("thing", mac).apply();
            if (src != null) {
                acIp = src; lastUdpMs = System.currentTimeMillis();
                prefs.edit().putString("ip", src).apply();
                ipEdit.setText(src);
            }
            refreshInfo();
            if (uat == null || uat.length() < 16) { log(via + " state seen from " + mac + "; no UAT to decrypt"); return; }
            String json = clean(decryptVendor(b64, uat.substring(0, 16)));
            JSONObject packet = new JSONObject(json);
            lastPacket = packet;
            JSONObject controls = extractControlState(packet);
            if (controls != null) {
                lastControlState = controls;
                processTrainer(controls);
            }
            lastText.setText("Last state (" + via + "): " + packet.toString(2));
            log(via + " STATE ← " + packet);
        } catch (Exception e) { log(via + " decode " + e.getMessage()); }
    }

    JSONObject extractControlState(JSONObject packet) {
        try {
            Object state = packet.opt("state");
            if (state instanceof JSONObject) {
                JSONObject s = (JSONObject) state;
                if (s.opt("reported") instanceof JSONObject) return copyJson((JSONObject) s.opt("reported"));
                if (s.opt("current") instanceof JSONObject) return copyJson((JSONObject) s.opt("current"));
                if (containsControlKey(s)) return copyJson(s);
            }
            if (packet.opt("reported") instanceof JSONObject) return copyJson((JSONObject) packet.opt("reported"));
            if (containsControlKey(packet)) return copyJson(packet);
        } catch (Exception ignored) {}
        return null;
    }

    boolean containsControlKey(JSONObject o) {
        for (String k : CONTROL_KEYS) if (o.has(k)) return true;
        return false;
    }

    void armTrainer() {
        String label = trainLabelEdit.getText().toString().trim();
        if (label.isEmpty()) { toast("Give the change a label first"); return; }
        if (lastControlState == null) { toast("Need at least one decoded AC state first"); return; }
        trainerBaseline = copyJson(lastControlState);
        trainerLabel = label;
        trainerArmed = true;
        diffText.setText("ARMED: now press ONE physical-remote button for “" + label + "”.");
        log("Trainer armed label=" + label + " baseline=" + trainerBaseline);
    }

    void processTrainer(JSONObject now) {
        if (!trainerArmed || trainerBaseline == null) return;
        JSONObject diff = controlDiff(trainerBaseline, now);
        if (diff.length() == 0) return;
        trainerArmed = false;
        learned.put(trainerLabel, diff);
        saveLearned();
        refreshLearnedSpinner();
        diffText.setText("LEARNED “" + trainerLabel + "”: " + diff);
        log("TRAINED " + trainerLabel + " => " + diff);
        trainerBaseline = null;
        trainerLabel = null;
    }

    JSONObject controlDiff(JSONObject before, JSONObject after) {
        JSONObject out = new JSONObject();
        for (String k : CONTROL_KEYS) {
            if (!after.has(k)) continue;
            Object a = after.opt(k), b = before.opt(k);
            if (!jsonEqual(a, b)) try { out.put(k, deepCopyValue(a)); } catch (Exception ignored) {}
        }
        return out;
    }

    boolean jsonEqual(Object a, Object b) {
        if (a == null || a == JSONObject.NULL) return b == null || b == JSONObject.NULL;
        if (b == null || b == JSONObject.NULL) return false;
        if (a instanceof Number && b instanceof Number) return Double.compare(((Number)a).doubleValue(), ((Number)b).doubleValue()) == 0;
        return a.toString().equals(b.toString());
    }

    Object deepCopyValue(Object v) throws Exception {
        if (v instanceof JSONObject) return new JSONObject(v.toString());
        if (v instanceof JSONArray) return new JSONArray(v.toString());
        return v;
    }

    void replayLearned(boolean lan) {
        Object selected = learnedSpinner.getSelectedItem();
        if (selected == null) { toast("No learned commands yet"); return; }
        JSONObject command = learned.get(selected.toString());
        if (command == null) return;
        try {
            JSONObject copy = new JSONObject(command.toString());
            log("Replay “" + selected + "” via " + (lan ? "LAN" : "BLE") + " => " + copy);
            sendDesired(lan, copy);
        } catch (Exception e) { log("replay " + e); }
    }

    void loadLearned() {
        try {
            String raw = prefs.getString("learned", "{}");
            JSONObject all = new JSONObject(raw);
            Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = all.opt(k);
                if (v instanceof JSONObject) learned.put(k, (JSONObject) v);
            }
        } catch (Exception ignored) {}
    }

    void saveLearned() {
        try {
            JSONObject all = new JSONObject();
            for (Map.Entry<String, JSONObject> e : learned.entrySet()) all.put(e.getKey(), e.getValue());
            prefs.edit().putString("learned", all.toString()).apply();
        } catch (Exception ignored) {}
    }

    void refreshLearnedSpinner() {
        if (learnedAdapter == null) return;
        learnedAdapter.clear();
        learnedAdapter.addAll(learned.keySet());
        learnedAdapter.notifyDataSetChanged();
    }

    static JSONObject copyJson(JSONObject j) { try { return new JSONObject(j.toString()); } catch (Exception e) { return new JSONObject(); } }

    static String encryptVendor(String raw, String key) throws Exception {
        byte[] random = new byte[16]; new SecureRandom().nextBytes(random);
        Cipher c;
        try { c = Cipher.getInstance("AES/CBC/PKCS7Padding"); }
        catch (Exception e) { c = Cipher.getInstance("AES/CBC/PKCS5Padding"); }
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII), "AES"), new IvParameterSpec(random));
        byte[] json = raw.getBytes(StandardCharsets.US_ASCII);
        byte[] input = new byte[16 + json.length];
        System.arraycopy(random, 0, input, 0, 16);
        System.arraycopy(json, 0, input, 16, json.length);
        return Base64.encodeToString(c.doFinal(input), Base64.NO_WRAP);
    }

    static byte[] decryptVendor(String b64, String key) throws Exception {
        byte[] all = Base64.decode(b64, Base64.NO_WRAP);
        if (all.length < 32) throw new IllegalArgumentException("short ciphertext");
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII), "AES"), new IvParameterSpec(Arrays.copyOfRange(all, 0, 16)));
        return c.doFinal(Arrays.copyOfRange(all, 16, all.length));
    }

    static String clean(byte[] b) {
        int n = b.length;
        while (n > 0 && (b[n - 1] & 255) <= 32) n--;
        return new String(b, 0, n, StandardCharsets.US_ASCII).trim();
    }

    void refreshInfo() {
        if (infoText == null) return;
        String udp = lastUdpMs == 0 ? "no UDP yet" : ((System.currentTimeMillis() - lastUdpMs) / 1000) + "s since UDP";
        infoText.setText("UAT " + mask(uat) + (thingMac == null ? "" : " · MAC " + thingMac) + (acIp == null ? "" : " · LAN " + acIp + ":" + PORT) + " · " + udp);
    }

    void refreshButtons() {
        boolean token = uat != null && uat.length() >= 16;
        if (claimBtn == null) return;
        claimBtn.setEnabled(secure);
        identityBtn.setEnabled(secure && token);
        wifiScanBtn.setEnabled(secure && token);
        provisionBtn.setEnabled(secure && token);
        stopOnboardingBtn.setEnabled(secure && token);
        disconnectBleBtn.setEnabled(blufi != null);
        bleOnBtn.setEnabled(secure && token); bleOffBtn.setEnabled(secure && token); bleTempBtn.setEnabled(secure && token);
        lanOnBtn.setEnabled(token); lanOffBtn.setEnabled(token); lanTempBtn.setEnabled(token);
        replayBleBtn.setEnabled(secure && token && !learned.isEmpty());
        replayLanBtn.setEnabled(token && !learned.isEmpty());
        trainBtn.setEnabled(lastControlState != null);
    }

    void closeBle() {
        if (blufi != null) try { blufi.close(); } catch (Exception ignored) {}
        blufi = null; secure = false; securityStarted = false;
        if (stateText != null) stateText.setText("Bluetooth disconnected; LAN remains available if provisioned");
        if (claimBtn != null) refreshButtons();
    }

    void stopUdp() {
        udpListening = false;
        try { if (udpSocket != null) udpSocket.close(); } catch (Exception ignored) {}
    }

    String redact(String raw) {
        try {
            JSONObject j = new JSONObject(raw);
            if (j.has("uat")) j.put("uat", mask(j.optString("uat")));
            if (j.has("pswd")) j.put("pswd", "***");
            return j.toString();
        } catch (Exception e) { return raw; }
    }

    static String mask(String s) { return s == null ? "none" : s.length() < 10 ? "***" : s.substring(0, 4) + "…" + s.substring(s.length() - 4); }

    void log(String s) { h.post(() -> { if (logText != null) logText.append(String.format(Locale.ENGLISH, "%tT %s\n", System.currentTimeMillis(), s)); refreshInfo(); refreshButtons(); }); }
    void copyLog() { ClipboardManager c = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE); c.setPrimaryClip(ClipData.newPlainText("BlueStar log", logText.getText())); toast("Copied"); }
    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override protected void onDestroy() {
        try { if (scanner != null) scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        closeBle(); stopUdp(); io.shutdownNow();
        super.onDestroy();
    }
}
