package com.hiddencam;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lets the user retry encryption for recordings retained after a failed save or interrupted session. */
public final class RecoveryActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout content;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ImmersiveMode.apply(this);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        render();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) ImmersiveMode.apply(this);
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));
        root.setBackgroundColor(0xFF090909);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        TextView heading = new TextView(this);
        heading.setText("Recording recovery"); heading.setTextColor(0xFFF7F7F7);
        heading.setTextSize(24); heading.setGravity(Gravity.CENTER);
        root.addView(heading, matchWrap());
        TextView description = new TextView(this);
        description.setText("These are app-private recordings retained because encryption did not complete. Secure each item into the encrypted vault before deleting or clearing app data.");
        description.setTextColor(0xFFA5A5A5); description.setTextSize(14);
        description.setPadding(0, dp(10), 0, dp(18));
        root.addView(description, matchWrap());
        content = root;
        setContentView(scroll);

        File[] files = VaultManager.listRecoveryFiles(this);
        if (files.length == 0) addText("No recovery recordings found.");
        DateFormat dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (File file : files) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackgroundColor(0xFF1B1B1B);
            LinearLayout.LayoutParams params = matchWrap(); params.bottomMargin = dp(10);
            content.addView(row, params);
            TextView info = new TextView(this);
            info.setText("Recovered video\n" + dates.format(new Date(file.lastModified())) + " · " + readableSize(file.length()));
            info.setTextColor(0xFFF7F7F7); info.setTextSize(14);
            row.addView(info, matchWrap());
            Button secure = new Button(this); secure.setText("Encrypt and save to vault"); secure.setAllCaps(false);
            row.addView(secure, matchWrap());
            secure.setOnClickListener(view -> {
                secure.setEnabled(false);
                worker.execute(() -> {
                    String message;
                    try {
                        VaultManager.encryptRecording(this, file);
                        if (file.delete()) message = "Recording secured in the encrypted vault.";
                        else message = "Encrypted copy saved. Original recovery copy could not be deleted; remove it only after verifying the vault copy.";
                    } catch (Exception exception) {
                        message = "Encryption failed. The recovery source was retained.";
                    }
                    String finalMessage = message;
                    handler.post(() -> {
                        Toast.makeText(this, finalMessage, Toast.LENGTH_LONG).show();
                        render();
                    });
                });
            });
        }
        Button back = new Button(this); back.setText("Back"); back.setAllCaps(false);
        LinearLayout.LayoutParams backParams = matchWrap(); backParams.topMargin = dp(8); content.addView(back, backParams);
        back.setOnClickListener(view -> finish());
    }

    private void addText(String value) {
        TextView text = new TextView(this); text.setText(value); text.setTextColor(0xFFA5A5A5);
        text.setTextSize(14); content.addView(text, matchWrap());
    }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String readableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
