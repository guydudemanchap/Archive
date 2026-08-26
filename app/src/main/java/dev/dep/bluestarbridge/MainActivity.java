package dev.dep.bluestarbridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import blufi.espressif.BlufiCallback;
import blufi.espressif.BlufiClient;
import blufi.espressif.params.BlufiConfigureParams;
import blufi.espressif.params.BlufiParameter;
import blufi.espressif.response.BlufiScanResult;
import blufi.espressif.response.BlufiStatusResponse;
import blufi.espressif.response.BlufiVersionResponse;

@SuppressLint("MissingPermission")
public class MainActivity extends Activity {
    private static final int PERM_REQ = 44;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, ScanResult> found = new LinkedHashMap<>();
    private final List<ScanResult> shown = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BlufiClient client;
    private boolean ready;
    private ArrayAdapter<String> listAdapter;
    private TextView state, log;
    private EditText ssid, password, custom;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        buildUi();
        requestPerms();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView title = text("BlueStar IA518VXUS Bridge Lab", 22);
        root.addView(title);
        root.addView(text("AC prep: ON → COOL → 16°C → FAN AUTO → press HEALTH / SENSAIR / DISPLAY(LIGHT) / ROOM 5× within 7 seconds. Then Scan BLE.", 14));
        state = text("State: idle", 16); root.addView(state);
        root.addView(button("Scan BLE (8 sec)", v -> scan()));

        ListView devices = new ListView(this);
        listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        devices.setAdapter(listAdapter);
        devices.setOnItemClickListener((p,v,pos,id) -> { if (pos < shown.size()) connect(shown.get(pos).getDevice()); });
        root.addView(devices, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));

        LinearLayout r1 = row();
        r1.addView(button("Security", v -> { if (ok()) client.negotiateSecurity(); }), weight());
        r1.addView(button("AC Wi-Fi Scan", v -> { if (ok()) client.requestDeviceWifiScan(); }), weight());
        root.addView(r1);

        LinearLayout r2 = row();
        r2.addView(button("BLUFI Version", v -> { if (ok()) client.requestDeviceVersion(); }), weight());
        r2.addView(button("Device Status", v -> { if (ok()) client.requestDeviceStatus(); }), weight());
        root.addView(r2);

        ssid = edit("2.4 GHz Wi-Fi SSID"); root.addView(ssid);
        password = edit("Wi-Fi password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(password);
        root.addView(button("Provision AC onto Wi-Fi", v -> provision()));

        custom = edit("Experimental custom BLUFI HEX e.g. 010203"); root.addView(custom);
        root.addView(button("Send custom HEX", v -> sendHex()));
        root.addView(button("Disconnect", v -> disconnect()));
        root.addView(text("Diagnostic log", 18));
        log = text("", 12); log.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this); sv.addView(log);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void requestPerms() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), PERM_REQ);
    }

    private boolean perms() {
        if (Build.VERSION.SDK_INT >= 31) return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private final ScanCallback cb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) { add(r); }
        @Override public void onBatchScanResults(List<ScanResult> rs) { for (ScanResult r: rs) add(r); }
        @Override public void onScanFailed(int code) { append("BLE scan failed: " + code); }
    };

    private void add(ScanResult r) {
        if (found.put(r.getDevice().getAddress(), r) == null) main.post(this::refresh);
    }

    private void refresh() {
        shown.clear(); shown.addAll(found.values());
        shown.sort((a,b) -> Integer.compare(b.getRssi(), a.getRssi()));
        labels.clear();
        for (ScanResult r: shown) {
            String n = r.getDevice().getName(); if (n == null || n.trim().isEmpty()) n = "(unnamed BLE device)";
            labels.add(n + "\n" + r.getDevice().getAddress() + "  RSSI " + r.getRssi());
        }
        listAdapter.notifyDataSetChanged();
    }

    private void scan() {
        if (!perms()) { requestPerms(); toast("Grant Bluetooth permission and tap Scan again."); return; }
        if (adapter == null || !adapter.isEnabled()) { toast("Turn Bluetooth on."); return; }
        scanner = adapter.getBluetoothLeScanner(); if (scanner == null) { toast("BLE scanner unavailable."); return; }
        found.clear(); refresh(); state.setText("State: scanning…"); append("Scanning all BLE devices for 8 seconds…");
        scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb);
        main.postDelayed(this::stopScan, 8000);
    }

    private void stopScan() {
        try { if (scanner != null) scanner.stopScan(cb); } catch (Exception ignored) {}
        state.setText("State: scan done — tap likely AC device"); append("Scan done: " + found.size() + " unique BLE device(s).");
    }

    private void connect(BluetoothDevice d) {
        stopScan(); disconnect(); ready = false;
        String n = d.getName(); if (n == null) n = "unnamed";
        state.setText("State: connecting to " + n); append("Connecting candidate " + n + " / " + d.getAddress());
        client = new BlufiClient(getApplicationContext(), d);
        client.setGattWriteTimeout(5000);
        client.setBlufiCallback(new BlufiCallback() {
            @Override public void onGattPrepared(BlufiClient c, BluetoothGatt gatt, BluetoothGattService service, BluetoothGattCharacteristic write, BluetoothGattCharacteristic notify) {
                if (service != null && write != null && notify != null) { ready = true; state.setText("State: BLUFI ready"); append("BLUFI service + FF01/FF02 discovered. Jackpot."); }
                else { append("Not a usable BLUFI endpoint."); state.setText("State: not BLUFI"); }
            }
            @Override public void onNegotiateSecurityResult(BlufiClient c, int s) { append("Security result=" + s); }
            @Override public void onPostConfigureParams(BlufiClient c, int s) { append("Provision payload result=" + s + (s==STATUS_SUCCESS ? " — request status/check router" : "")); }
            @Override public void onDeviceScanResult(BlufiClient c, int s, List<BlufiScanResult> rs) {
                append("AC Wi-Fi scan status=" + s + " count=" + (rs==null?0:rs.size()));
                if (rs != null) for (BlufiScanResult x: rs) append("  " + x.toString());
            }
            @Override public void onDeviceStatusResponse(BlufiClient c, int s, BlufiStatusResponse x) {
                if (s == STATUS_SUCCESS && x != null) append("STATUS opMode="+x.getOpMode()+" connected="+x.isStaConnectWifi()+" ssid="+x.getStaSSID()+" bssid="+x.getStaBSSID());
                else append("Status request failed=" + s);
            }
            @Override public void onDeviceVersionResponse(BlufiClient c, int s, BlufiVersionResponse x) { append("Version status=" + s + " value=" + (x==null?"null":x.getVersionString())); }
            @Override public void onPostCustomDataResult(BlufiClient c, int s, byte[] data) { append("Custom TX status="+s+" hex="+hex(data)); }
            @Override public void onReceiveCustomData(BlufiClient c, int s, byte[] data) { append("Custom RX status="+s+" hex="+hex(data)); }
            @Override public void onError(BlufiClient c, int code) { append("BLUFI ERROR=" + code); }
        });
        client.connect();
    }

    private void provision() {
        if (!ok()) return;
        String s = ssid.getText().toString().trim(); if (s.isEmpty()) { toast("Enter SSID."); return; }
        BlufiConfigureParams p = new BlufiConfigureParams(); p.setOpMode(BlufiParameter.OP_MODE_STA);
        p.setStaSSIDBytes(s.getBytes(StandardCharsets.UTF_8)); p.setStaPassword(password.getText().toString());
        append("Provisioning SSID=" + s + " (password intentionally not logged)"); client.configure(p);
    }

    private void sendHex() {
        if (!ok()) return;
        try { byte[] b = parseHex(custom.getText().toString()); append("Custom TX " + hex(b)); client.postCustomData(b); }
        catch (IllegalArgumentException e) { toast(e.getMessage()); }
    }

    private boolean ok() { if (client == null || !ready) { toast("Connect to a BLUFI device first."); return false; } return true; }
    private void disconnect() { if (client != null) { try { client.close(); } catch (Exception ignored) {} client = null; } ready = false; }
    private void append(String s) { main.post(() -> log.append(String.format(Locale.ENGLISH, "%tT  %s\n", System.currentTimeMillis(), s))); }

    private static byte[] parseHex(String raw) {
        String s = raw.replace(" ", "").replace(":", ""); if ((s.length() & 1) != 0) throw new IllegalArgumentException("Hex needs an even number of characters.");
        byte[] out = new byte[s.length()/2]; for (int i=0;i<out.length;i++) { int a=Character.digit(s.charAt(i*2),16), b=Character.digit(s.charAt(i*2+1),16); if(a<0||b<0) throw new IllegalArgumentException("Invalid hex."); out[i]=(byte)((a<<4)|b); } return out;
    }
    private static String hex(byte[] b) { if (b==null) return "null"; StringBuilder s=new StringBuilder(); for(byte x:b)s.append(String.format(Locale.ENGLISH,"%02X",x&255)); return s.toString(); }
    private TextView text(String s, int size) { TextView v=new TextView(this); v.setText(s); v.setTextSize(size); return v; }
    private Button button(String s, android.view.View.OnClickListener l) { Button b=new Button(this); b.setText(s); b.setOnClickListener(l); return b; }
    private EditText edit(String h) { EditText e=new EditText(this); e.setHint(h); e.setSingleLine(true); return e; }
    private LinearLayout row() { LinearLayout r=new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); return r; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); }
    private int dp(int x) { return Math.round(x*getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    @Override protected void onDestroy() { stopScan(); disconnect(); super.onDestroy(); }
}
