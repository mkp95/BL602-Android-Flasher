package com.example.bl602tool;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ScaleGestureDetector;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.*;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;

import com.hoho.android.usbserial.driver.*;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.util.*;
import java.util.concurrent.*;
import java.text.SimpleDateFormat;

public class MainActivity extends Activity implements SerialInputOutputManager.Listener {
    private static final int PICK_FILES = 41;
    private UsbManager usbManager;
    private UsbSerialPort port;
    private SerialInputOutputManager ioManager;
    private ExecutorService executor;
    private TextView status, terminal, firmwareList, flashLog;
    private ScrollView terminalScroll;
    private boolean terminalAtLineStart = true;
    private EditText sendText, baudRate;
    private Spinner viewMode, newlineMode;
    private Button connectButton, autoScrollButton;
    private boolean autoScroll = true;
    private float terminalTextSize = 13f;
    private ScaleGestureDetector scaleDetector;
    private LinearLayout monitorPanel;
    private ScrollView flashPanel, settingsPanel;
    private final List<Uri> firmwareUris = new ArrayList<>();
    private boolean dtrState, rtsState, logMode = true;
    private final List<String> commandHistory = new ArrayList<>();
    private final List<TerminalRecord> terminalRecords = new ArrayList<>();
    private int formatMode = 0; // 0 = ASCII/UTF-8, 1 = hexadecimal
    private Button formatToggle;

    private static class TerminalRecord {
        final String text;
        final byte[] received;
        final int color;
        TerminalRecord(String text, byte[] received, int color) {
            this.text = text;
            this.received = received;
            this.color = color;
        }
    }
    private final String[] shortcutNames = new String[7];
    private final String[] shortcutCommands = new String[7];

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        usbManager = (UsbManager)getSystemService(USB_SERVICE);
        executor = Executors.newSingleThreadExecutor();
        status = findViewById(R.id.status);
        terminal = findViewById(R.id.terminal);
        terminalScroll = findViewById(R.id.terminalScroll);
        terminal.setText("");
        firmwareList = findViewById(R.id.firmwareList);
        flashLog = findViewById(R.id.flashLog);
        sendText = findViewById(R.id.sendText);
        baudRate = findViewById(R.id.baudRate);
        baudRate.setText("500000");
        viewMode = findViewById(R.id.viewMode);
        newlineMode = findViewById(R.id.newlineMode);
        viewMode.setAdapter(createDarkSpinnerAdapter(new String[]{"ASCII / UTF-8","Hexadecimal"}));
        formatToggle = findViewById(R.id.formatToggle);
        formatToggle.setOnClickListener(v -> setFormatMode(formatMode == 0 ? 1 : 0));
        viewMode.setSelection(formatMode);
        viewMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { setFormatMode(position); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        updateFormatToggle();
        newlineMode.setAdapter(createDarkSpinnerAdapter(new String[]{"CR + LF","LF","CR","None"}));
        connectButton = findViewById(R.id.connect);
        autoScrollButton = findViewById(R.id.autoScroll);
        connectButton.setOnClickListener(v -> { if (port == null) connectUsb(); else disconnectUsb(); });
        findViewById(R.id.clearTerminal).setOnClickListener(v -> clearTerminal());
        autoScrollButton.setOnClickListener(v -> {
            autoScroll = !autoScroll;
            updateAutoScrollButton();
            if (autoScroll) terminalScroll.post(() -> terminalScroll.fullScroll(View.FOCUS_DOWN));
        });
        findViewById(R.id.baudControl).setOnClickListener(v -> showBaudPicker());
        terminalTextSize = getSharedPreferences("terminal", MODE_PRIVATE).getFloat("textSize", 13f);
        terminal.setTextSize(terminalTextSize);
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                terminalTextSize = Math.max(8f, Math.min(28f, terminalTextSize * detector.getScaleFactor()));
                terminal.setTextSize(terminalTextSize);
                getSharedPreferences("terminal", MODE_PRIVATE).edit().putFloat("textSize", terminalTextSize).apply();
                return true;
            }
        });
        terminal.setOnTouchListener((v, event) -> { scaleDetector.onTouchEvent(event); return false; });
        monitorPanel = findViewById(R.id.monitorPanel);
        flashPanel = findViewById(R.id.flashPanel);
        settingsPanel = findViewById(R.id.settingsPanel);
        findViewById(R.id.menu).setOnClickListener(v -> showMenu(v));
        findViewById(R.id.backToTerminal).setOnClickListener(v -> showPanel(1));
        loadShortcuts();
        setupShortcuts();
        findViewById(R.id.send).setOnClickListener(v -> sendText());

        findViewById(R.id.pickFirmware).setOnClickListener(v -> pickFirmware());
        findViewById(R.id.startFlash).setOnClickListener(v -> startFlash());
        findViewById(R.id.dtr).setOnClickListener(v -> setDtr());
        findViewById(R.id.rts).setOnClickListener(v -> setRts());
        applyInterfaceTheme();
        updateAutoScrollButton();
        updateModemOutputButtons();
        showPanel(1);
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
            connectButton.setText("⏻");
            connectButton.setContentDescription("Disconnect USB serial");
            findViewById(R.id.baudControl).setEnabled(false);
            appendTerminal("\n[connected @ " + baud + " baud]\n");
            refreshModemStatus();
        } catch (Exception e) { toast("Connect failed: " + e.getMessage()); }
    }

    private void disconnectUsb() {
        try {
            if (ioManager != null) { ioManager.stop(); ioManager = null; }
            if (port != null) { port.close(); port = null; }
        } catch (Exception ignored) {}
        status.setText("USB disconnected");
        status.setTextColor(0xffb00020);
        if (connectButton != null) { connectButton.setText("⏻"); connectButton.setContentDescription("Connect USB serial"); }
        if (findViewById(R.id.baudControl) != null) findViewById(R.id.baudControl).setEnabled(true);
        appendTerminal("\n[disconnected]\n");
    }

    private void sendText() {
        if (port == null) { toast("Connect USB serial first."); return; }
        String s = sendText.getText().toString();
        try {
            String ending = "";
            int nl = newlineMode.getSelectedItemPosition();
            if (nl == 0) ending = "\r\n";
            else if (nl == 1) ending = "\n";
            else if (nl == 2) ending = "\r";
            port.write((s + ending).getBytes("UTF-8"), 1000);
            appendTerminal("\nTX> " + s + "\n", 0xff65c7ff);
            if (!s.trim().isEmpty()) {
                commandHistory.add(0, s);
                while (commandHistory.size() > 100) commandHistory.remove(commandHistory.size() - 1);
            }
            sendText.setText("");
        } catch (Exception e) { toast("TX failed: " + e.getMessage()); }
    }

    @Override public void onNewData(byte[] data) {
        final byte[] received = data.clone();
        runOnUiThread(() -> {
            appendReceivedData(received);
            refreshModemStatus();
        });
    }

    private void appendReceivedData(byte[] data) {
        terminalRecords.add(new TerminalRecord(null, data.clone(), 0xff8ee68b));
        trimTerminalRecords();
        appendTerminalRendered(formatReceivedData(data), 0xff8ee68b);
    }

    private String formatReceivedData(byte[] data) {
        if (formatMode == 1) {
            StringBuilder hex = new StringBuilder(data.length * 3 + 1);
            for (byte value : data) hex.append(String.format(Locale.US, "%02X ", value & 0xff));
            return hex.toString().trim() + "\n";
        }
        return new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void setFormatMode(int mode) {
        int selected = mode == 1 ? 1 : 0;
        if (formatMode == selected && viewMode.getSelectedItemPosition() == selected) {
            updateFormatToggle();
            return;
        }
        formatMode = selected;
        if (viewMode.getSelectedItemPosition() != selected) viewMode.setSelection(selected);
        updateFormatToggle();
        rerenderTerminal();
    }

    private void updateFormatToggle() {
        if (formatToggle != null) {
            formatToggle.setText(formatMode == 0 ? "Aa" : "0x");
            formatToggle.setContentDescription(formatMode == 0 ? "ASCII receive format" : "Hexadecimal receive format");
        }
    }

    private void clearTerminal() {
        terminalRecords.clear();
        terminal.setText("");
        terminalAtLineStart = true;
    }

    private void trimTerminalRecords() {
        while (terminalRecords.size() > 5000) terminalRecords.remove(0);
    }

    private void rerenderTerminal() {
        terminal.setText("");
        terminalAtLineStart = true;
        for (TerminalRecord record : terminalRecords) {
            String content = record.received == null ? record.text : formatReceivedData(record.received);
            appendTerminalRendered(content, record.color);
        }
        if (autoScroll) terminalScroll.post(() -> terminalScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override public void onRunError(Exception e) {
        runOnUiThread(() -> appendTerminal("\n[serial error: " + e.getMessage() + "]\n"));
    }
    private static final int TIMESTAMP_COLOR = 0xff77838f;

    private void appendTerminal(String s) {
        appendTerminal(s, 0xffe0e0e0);
    }

    private void appendTerminal(String s, int dataColor) {
        if (s == null || s.length() == 0) return;
        terminalRecords.add(new TerminalRecord(s, null, dataColor));
        trimTerminalRecords();
        appendTerminalRendered(s, dataColor);
    }

    private void appendTerminalRendered(String s, int dataColor) {
        if (s == null || s.length() == 0) return;
        final int previousScrollY = terminalScroll.getScrollY();
        SpannableStringBuilder output = new SpannableStringBuilder();
        List<int[]> timestampRanges = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) {
            if (terminalAtLineStart) {
                if (logMode) {
                    int start = output.length();
                    output.append(new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date()));
                    timestampRanges.add(new int[]{start, output.length()});
                    output.append("  ");
                }
                terminalAtLineStart = false;
            }
            char ch = s.charAt(i);
            output.append(ch);
            if (ch == '\n') terminalAtLineStart = true;
        }
        output.setSpan(new ForegroundColorSpan(dataColor), 0, output.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        for (int[] range : timestampRanges) {
            output.setSpan(new ForegroundColorSpan(TIMESTAMP_COLOR), range[0], range[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        terminal.append(output);
        int excess = terminal.length() - 50000;
        if (excess > 0) {
            CharSequence text = terminal.getText();
            int cut = excess;
            while (cut < text.length() && text.charAt(cut) != '\n') cut++;
            if (cut < text.length()) cut++;
            if (cut < text.length()) terminal.setText(text.subSequence(cut, text.length()));
        }
        if (autoScroll) terminalScroll.post(() -> terminalScroll.fullScroll(View.FOCUS_DOWN));
        else terminalScroll.post(() -> terminalScroll.scrollTo(0, previousScrollY));
    }

    private void showBaudPicker() {
        String[] choices = {"9600","19200","38400","57600","74880","115200","230400","460800","500000","921600","1000000","1500000","2000000","Custom..."};
        new AlertDialog.Builder(this).setTitle("UART baud rate").setItems(choices, (dialog, which) -> {
            if (which == choices.length - 1) {
                EditText custom = new EditText(this);
                custom.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                custom.setText(baudRate.getText());
                new AlertDialog.Builder(this).setTitle("Custom baud rate").setView(custom)
                    .setPositiveButton("Apply", (d, w) -> { baudRate.setText(custom.getText()); applyBaudLabel(); })
                    .setNegativeButton("Cancel", null).show();
            } else {
                baudRate.setText(choices[which]);
                applyBaudLabel();
            }
        }).show();
    }

    private void applyBaudLabel() {
        String value = baudRate.getText().toString().trim();
        ((Button)findViewById(R.id.baudControl)).setText(value.isEmpty() ? "Baud" : value);
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("◷  History");
        menu.getMenu().add(logMode ? "◉  Log mode: On" : "◯  Log mode: Off");
        menu.getMenu().add("⚙  Settings");
        menu.getMenu().add("⇧  Flash tools");
        menu.getMenu().add("▣  Copy terminal");
        menu.getMenu().add("▤  Paste");
        menu.getMenu().add("⌫  Clear terminal");
        menu.setOnMenuItemClickListener(item -> {
            String action = item.getTitle().toString();
            if (action.contains("History")) showHistory();
            else if (action.contains("Log mode:")) {
                logMode = !logMode;
                toast("Timestamp log mode " + (logMode ? "enabled" : "disabled"));
            } else if (action.contains("Settings")) showPanel(2);
            else if (action.contains("Flash tools")) showPanel(0);
            else if (action.contains("Copy terminal")) copyTerminal();
            else if (action.contains("Paste")) pasteClipboard();
            else if (action.contains("Clear terminal")) clearTerminal();
            return true;
        });
        menu.show();
    }

    private void showHistory() {
        if (commandHistory.isEmpty()) { toast("No sent commands yet."); return; }
        String[] items = commandHistory.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("Command history")
            .setItems(items, (dialog, which) -> {
                sendText.setText(items[which]);
                sendText.setSelection(sendText.length());
                showPanel(1);
            }).setNegativeButton("Close", null).show();
    }

    private void loadShortcuts() {
        android.content.SharedPreferences prefs = getSharedPreferences("uart_shortcuts", MODE_PRIVATE);
        for (int i = 0; i < 7; i++) {
            shortcutNames[i] = prefs.getString("name" + i, "M" + (i + 1));
            shortcutCommands[i] = prefs.getString("command" + i, "");
        }
    }

    private void setupShortcuts() {
        int[] ids = {R.id.m1, R.id.m2, R.id.m3, R.id.m4, R.id.m5, R.id.m6, R.id.m7};
        for (int i = 0; i < ids.length; i++) {
            final int slot = i;
            Button button = findViewById(ids[i]);
            button.setText(shortcutNames[i]);
            button.setOnClickListener(v -> {
                if (shortcutCommands[slot].isEmpty()) editShortcut(slot);
                else transmitCommand(shortcutCommands[slot]);
            });
            button.setOnLongClickListener(v -> { editShortcut(slot); return true; });
        }
    }

    private void editShortcut(int slot) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(20), dp(8), dp(20), 0);
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Button label");
        name.setText(shortcutNames[slot]);
        EditText command = new EditText(this);
        command.setSingleLine(true);
        command.setHint("Command to transmit");
        command.setText(shortcutCommands[slot]);
        fields.addView(name);
        fields.addView(command);
        new AlertDialog.Builder(this).setTitle("Edit shortcut M" + (slot + 1))
            .setView(fields).setPositiveButton("Save", (d, w) -> {
                String n = name.getText().toString().trim();
                shortcutNames[slot] = n.isEmpty() ? "M" + (slot + 1) : n;
                shortcutCommands[slot] = command.getText().toString();
                getSharedPreferences("uart_shortcuts", MODE_PRIVATE).edit()
                    .putString("name" + slot, shortcutNames[slot])
                    .putString("command" + slot, shortcutCommands[slot]).apply();
                setupShortcuts();
            }).setNeutralButton("Clear", (d, w) -> {
                shortcutNames[slot] = "M" + (slot + 1);
                shortcutCommands[slot] = "";
                getSharedPreferences("uart_shortcuts", MODE_PRIVATE).edit()
                    .remove("name" + slot).remove("command" + slot).apply();
                setupShortcuts();
            }).setNegativeButton("Cancel", null).show();
    }

    private void transmitCommand(String command) {
        sendText.setText(command);
        sendText.setSelection(sendText.length());
        sendText();
    }

    private void refreshModemStatus() {
        if (port == null) return;
        try {
            ((TextView)findViewById(R.id.cts)).setText(port.getCTS() ? "CTS ●" : "CTS ○");
            ((TextView)findViewById(R.id.dsr)).setText(port.getDSR() ? "DSR ●" : "DSR ○");
            ((TextView)findViewById(R.id.cd)).setText(port.getCD() ? "CD ●" : "CD ○");
            ((TextView)findViewById(R.id.ri)).setText(port.getRI() ? "RI ●" : "RI ○");
        } catch (Exception ignored) { }
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
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

    private void updateAutoScrollButton() {
        autoScrollButton.setText(autoScroll ? "⇣" : "↕");
        autoScrollButton.setContentDescription(autoScroll ? "Auto-scroll on" : "Auto-scroll off");
        autoScrollButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(autoScroll ? 0xff167c70 : 0xff394957));
    }

    private void updateModemOutputButtons() {
        Button dtr = findViewById(R.id.dtr), rts = findViewById(R.id.rts);
        if (dtr != null) { dtr.setText(dtrState ? "DTR ●" : "DTR ○"); dtr.setContentDescription("Toggle DTR, currently " + (dtrState ? "on" : "off")); dtr.setBackgroundTintList(android.content.res.ColorStateList.valueOf(dtrState ? 0xff167c70 : 0xff394957)); }
        if (rts != null) { rts.setText(rtsState ? "RTS ●" : "RTS ○"); rts.setContentDescription("Toggle RTS, currently " + (rtsState ? "on" : "off")); rts.setBackgroundTintList(android.content.res.ColorStateList.valueOf(rtsState ? 0xff167c70 : 0xff394957)); }
    }

    private ArrayAdapter<String> createDarkSpinnerAdapter(String[] values) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
            private View style(View view) { TextView t=(TextView)view; t.setTextColor(0xfff1f5f9); t.setTextSize(14); t.setPadding(dp(12),dp(10),dp(12),dp(10)); t.setBackgroundColor(0xff263746); return t; }
            @Override public View getView(int p, View v, android.view.ViewGroup parent) { return style(super.getView(p,v,parent)); }
            @Override public View getDropDownView(int p, View v, android.view.ViewGroup parent) { return style(super.getDropDownView(p,v,parent)); }
        };
    }

    private void applyInterfaceTheme() {
        int[] ids={R.id.connect,R.id.clearTerminal,R.id.formatToggle,R.id.autoScroll,R.id.baudControl,R.id.menu,R.id.send,R.id.m1,R.id.m2,R.id.m3,R.id.m4,R.id.m5,R.id.m6,R.id.m7,R.id.pickFirmware,R.id.startFlash,R.id.backToTerminal};
        for(int id:ids){ Button b=findViewById(id); if(b!=null){ b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff263746)); b.setTextColor(0xfff1f5f9); b.setAllCaps(false); } }
        int[] accent={R.id.connect,R.id.send,R.id.startFlash};
        for(int id:accent){ Button b=findViewById(id); if(b!=null)b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff167c70)); }
        int[] labels={R.id.firmwareList,R.id.flashLog};
        for(int id:labels){ TextView t=findViewById(id); if(t!=null)t.setTextColor(0xffe6edf3); }
    }

    private void setDtr() {
        if (port == null) { toast("Connect first."); return; }
        try { boolean next = !dtrState; port.setDTR(next); dtrState = next; updateModemOutputButtons(); }
        catch (Exception e) { toast("DTR unavailable: " + e.getMessage()); }
    }
    private void setRts() {
        if (port == null) { toast("Connect first."); return; }
        try { boolean next = !rtsState; port.setRTS(next); rtsState = next; updateModemOutputButtons(); }
        catch (Exception e) { toast("RTS unavailable: " + e.getMessage()); }
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    @Override protected void onDestroy() {
        disconnectUsb();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }
}
