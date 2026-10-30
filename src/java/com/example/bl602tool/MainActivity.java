package com.example.bl602tool;

import android.app.Activity;
import android.content.*;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.*;
import android.content.ClipData;
import android.content.ClipboardManager;

import com.hoho.android.usbserial.driver.*;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity implements SerialInputOutputManager.Listener {
    private static final int PICK_FILES = 41;
    private UsbManager usbManager;
    private UsbSerialPort port;
    private SerialInputOutputManager ioManager;
    private ExecutorService executor;
    private TextView status, terminal, firmwareList, flashLog;
    private EditText sendText, baudRate;
    private LinearLayout monitorPanel;
    private ScrollView flashPanel, settingsPanel;
    private final List<Uri> firmwareUris = new ArrayList<>();
    private boolean dtrState, rtsState;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        usbManager = (UsbManager)getSystemService(USB_SERVICE);
        executor = Executors.newSingleThreadExecutor();
        status = findViewById(R.id.status);
        terminal = findViewById(R.id.terminal);
        terminal.setMovementMethod(new ScrollingMovementMethod());
        firmwareList = findViewById(R.id.firmwareList);
        flashLog = findViewById(R.id.flashLog);
        sendText = findViewById(R.id.sendText);
        baudRate = findViewById(R.id.baudRate);
        monitorPanel = findViewById(R.id.monitorPanel);
        flashPanel = findViewById(R.id.flashPanel);
        settingsPanel = findViewById(R.id.settingsPanel);
        findViewById(R.id.tabFlash).setOnClickListener(v -> showPanel(0));
        findViewById(R.id.tabMonitor).setOnClickListener(v -> showPanel(1));
        findViewById(R.id.tabSettings).setOnClickListener(v -> showPanel(2));
        findViewById(R.id.connect).setOnClickListener(v -> connectUsb());
        findViewById(R.id.disconnect).setOnClickListener(v -> disconnectUsb());
        findViewById(R.id.send).setOnClickListener(v -> sendText());
        findViewById(R.id.copy).setOnClickListener(v -> copyTerminal());
        findViewById(R.id.paste).setOnClickListener(v -> pasteClipboard());
        findViewById(R.id.clear).setOnClickListener(v -> terminal.setText(""));
        findViewById(R.id.pickFirmware).setOnClickListener(v -> pickFirmware());
        findViewById(R.id.startFlash).setOnClickListener(v -> startFlash());
        findViewById(R.id.dtr).setOnClickListener(v -> setDtr());
        findViewById(R.id.rts).setOnClickListener(v -> setRts());
        showPanel(0);
    }

    private void showPanel(int which) {
        flashPanel.setVisibility(which == 0 ? View.VISIBLE : View.GONE);
        monitorPanel.setVisibility(which == 1 ? View.VISIBLE : View.GONE);
        settingsPanel.setVisibility(which == 2 ? View.VISIBLE : View.GONE);
    }

    private int getBaud() {
        try {
            int value = Integer.parseInt(baudRate.getText().toString().trim());
            if (value < 1200 || value > 3000000) throw new NumberFormatException();
            return value;
        } catch (Exception e) {
            throw new IllegalArgumentException("Enter a baud rate from 1200 to 3000000.");
        }
    }

    private void connectUsb() {
        try {
            int baud = getBaud();
            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
            if (drivers.isEmpty()) { toast("No supported USB serial device found."); return; }
            UsbSerialDriver chosen = null;
            for (UsbSerialDriver d : drivers) {
                if (usbManager.hasPermission(d.getDevice())) { chosen = d; break; }
            }
            if (chosen == null) {
                UsbDevice device = drivers.get(0).getDevice();
                PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                    new Intent("com.example.bl602tool.USB_PERMISSION"),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                usbManager.requestPermission(device, pi);
                toast("USB permission requested. Tap Connect again after granting permission.");
                return;
            }
            UsbDeviceConnection connection = usbManager.openDevice(chosen.getDevice());
            if (connection == null) { toast("Could not open USB device."); return; }
            port = chosen.getPorts().get(0);
            port.open(connection);
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            port.setDTR(dtrState);
            port.setRTS(rtsState);
            ioManager = new SerialInputOutputManager(port, this);
            executor.submit(ioManager);
            status.setText("Connected: " + chosen.getDevice().getDeviceName() + " @ " + baud);
            status.setTextColor(0xff16803c);
            appendTerminal("\n[connected @ " + baud + " baud]\n");
        } catch (Exception e) { toast("Connect failed: " + e.getMessage()); }
    }

    private void disconnectUsb() {
        try {
            if (ioManager != null) { ioManager.stop(); ioManager = null; }
            if (port != null) { port.close(); port = null; }
        } catch (Exception ignored) {}
        status.setText("USB disconnected");
        status.setTextColor(0xffb00020);
        appendTerminal("\n[disconnected]\n");
    }

    private void sendText() {
        if (port == null) { toast("Connect USB serial first."); return; }
        String s = sendText.getText().toString();
        try {
            port.write((s + "\r\n").getBytes("UTF-8"), 1000);
            appendTerminal("\nTX> " + s + "\n");
            sendText.setText("");
        } catch (Exception e) { toast("TX failed: " + e.getMessage()); }
    }

    @Override public void onNewData(byte[] data) {
        final String text = new String(data);
        runOnUiThread(() -> appendTerminal(text));
    }
    @Override public void onRunError(Exception e) {
        runOnUiThread(() -> appendTerminal("\n[serial error: " + e.getMessage() + "]\n"));
    }
    private void appendTerminal(String s) {
        terminal.append(s);
        int excess = terminal.length() - 50000;
        if (excess > 0) terminal.setText(terminal.getText().subSequence(excess, terminal.length()));
        terminal.post(() -> {
            android.text.Layout l = terminal.getLayout();
            if (l != null) terminal.scrollTo(0, l.getHeight());
        });
    }
    private void copyTerminal() {
        ClipboardManager cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("UART terminal", terminal.getText()));
        toast("Terminal copied.");
    }
    private void pasteClipboard() {
        ClipboardManager cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            sendText.setText(t);
            sendText.setSelection(sendText.length());
        }
    }
    private void pickFirmware() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PICK_FILES);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FILES || result != RESULT_OK || data == null) return;
        firmwareUris.clear();
        if (data.getClipData() != null) {
            ClipData clip = data.getClipData();
            for (int n = 0; n < clip.getItemCount(); n++) firmwareUris.add(clip.getItemAt(n).getUri());
        } else if (data.getData() != null) firmwareUris.add(data.getData());
        StringBuilder b = new StringBuilder();
        for (Uri u : firmwareUris) b.append(u.getLastPathSegment()).append("\n");
        firmwareList.setText(b.length() == 0 ? "No firmware selected" : b.toString());
    }
    private void startFlash() {
        if (port == null) { toast("Connect to the CH340 first."); return; }
        if (firmwareUris.isEmpty()) { toast("Select at least one BIN file."); return; }
        appendFlashLog("BL602 flashing engine is not implemented in this starter build.\n");
        appendFlashLog("Firmware selection and UART transport are ready; next step is porting the BL602 loader protocol and bundled loader/config assets.\n");
        appendFlashLog("No erase or write command was sent.\n");
        toast("Flashing is not enabled in this starter build.");
    }
    private void appendFlashLog(String s) { flashLog.append(s); }
    private void setDtr() {
        if (port == null) { toast("Connect first."); return; }
        try { dtrState = !dtrState; port.setDTR(dtrState); toast("DTR " + dtrState); }
        catch (Exception e) { toast("DTR unavailable: " + e.getMessage()); }
    }
    private void setRts() {
        if (port == null) { toast("Connect first."); return; }
        try { rtsState = !rtsState; port.setRTS(rtsState); toast("RTS " + rtsState); }
        catch (Exception e) { toast("RTS unavailable: " + e.getMessage()); }
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    @Override protected void onDestroy() {
        disconnectUsb();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }
}
