package com.hiddencam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;

/** Native UI for independent password-protected folders. */
public final class FolderVaultActivity extends Activity {
    private static final int REQUEST_IMPORT = 8101;
    private static final int REQUEST_EXPORT = 8102;
    private static final int COLOR_BACKGROUND = 0xFF10131B;
    private static final int COLOR_CARD = 0xFF1A2030;
    private static final int COLOR_TEXT = 0xFFF4F6FA;
    private static final int COLOR_MUTED = 0xFFABB4C4;

    private LinearLayout content;
    private String activeFolderId;
    private String activeFolderName;
    private SecretKey activeKey;
    private String pendingExportId;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        showFolders();
    }

    private void begin(String title, String subtitle) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));
        root.setBackgroundColor(COLOR_BACKGROUND);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        TextView heading = new TextView(this);
        heading.setText(title); heading.setTextColor(COLOR_TEXT); heading.setTextSize(24);
        heading.setGravity(Gravity.CENTER); heading.setPadding(0, 0, 0, dp(8));
        root.addView(heading, matchWrap());
        TextView description = new TextView(this);
        description.setText(subtitle); description.setTextColor(COLOR_MUTED); description.setTextSize(14);
        description.setGravity(Gravity.CENTER); description.setPadding(0, 0, 0, dp(18));
        root.addView(description, matchWrap());
        content = root;
        setContentView(scroll);
    }

    private void showFolders() {
        activeFolderId = null; activeFolderName = null; activeKey = null;
        begin("Protected folders", "Each folder has its own password and AES-256-GCM encryption key. Passwords are not stored.");
        addButton("Create protected folder", this::showCreateDialog, true);
        List<FolderVaultManager.Folder> folders = FolderVaultManager.listFolders(this);
        if (folders.isEmpty()) addText("No protected folders yet.");
        for (FolderVaultManager.Folder folder : folders) {
            addCard(folder.name, "Open with this folder's password", () -> showUnlockDialog(folder));
        }
        addButton("Back to HiddenCam", this::finish, false);
    }

    private void showCreateDialog() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        EditText name = new EditText(this); name.setSingleLine(true); name.setHint("Folder name");
        EditText password = new EditText(this); password.setSingleLine(true); password.setHint("Folder password (8+ characters)");
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(name); fields.addView(password);
        new AlertDialog.Builder(this).setTitle("Create protected folder").setView(fields)
                .setPositiveButton("Create", (dialog, which) -> {
                    char[] secret = password.getText().toString().toCharArray();
                    try {
                        FolderVaultManager.Folder folder = FolderVaultManager.createFolder(this,
                                name.getText().toString(), secret);
                        activeKey = FolderVaultManager.unlock(this, folder.id, secret);
                        activeFolderId = folder.id; activeFolderName = folder.name;
                        showFolderContents();
                    } catch (Exception exception) {
                        Toast.makeText(this, messageFor(exception), Toast.LENGTH_LONG).show();
                    } finally { java.util.Arrays.fill(secret, '\0'); password.setText(""); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void showUnlockDialog(FolderVaultManager.Folder folder) {
        EditText password = new EditText(this);
        password.setSingleLine(true); password.setHint("Folder password");
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this).setTitle("Unlock " + folder.name).setView(password)
                .setPositiveButton("Unlock", (dialog, which) -> {
                    char[] secret = password.getText().toString().toCharArray();
                    try {
                        activeKey = FolderVaultManager.unlock(this, folder.id, secret);
                        activeFolderId = folder.id; activeFolderName = folder.name;
                        showFolderContents();
                    } catch (Exception exception) {
                        Toast.makeText(this, messageFor(exception), Toast.LENGTH_LONG).show();
                    } finally { java.util.Arrays.fill(secret, '\0'); password.setText(""); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void showFolderContents() {
        if (activeKey == null || activeFolderId == null) { showFolders(); return; }
        begin(activeFolderName, "Unlocked only in this screen. Lock the folder by leaving this screen or returning to the folder list.");
        addButton("Add files to this folder", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQUEST_IMPORT);
        }, true);
        addButton("Copy legacy .hcam recordings here", () -> {
            new AlertDialog.Builder(this).setTitle("Copy legacy recordings")
                    .setMessage("Existing .hcam recordings will be decrypted inside the app and copied into this password-protected folder. Original .hcam files are kept unchanged until you verify the copies.")
                    .setPositiveButton("Copy", (dialog, which) -> {
                        try {
                            int count = FolderVaultManager.copyLegacyRecordings(this, activeFolderId, activeKey);
                            Toast.makeText(this, "Copied " + count + " legacy recording(s). Originals were retained.", Toast.LENGTH_LONG).show();
                            showFolderContents();
                        } catch (Exception exception) {
                            Toast.makeText(this, "Migration stopped safely: " + messageFor(exception), Toast.LENGTH_LONG).show();
                        }
                    }).setNegativeButton("Cancel", null).show();
        }, false);

        try {
            List<FolderVaultManager.Entry> entries = FolderVaultManager.listEntries(this, activeFolderId);
            if (entries.isEmpty()) addText("This folder is empty.");
            DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
            for (FolderVaultManager.Entry entry : entries) {
                String label = "Encrypted file · " + entry.id.substring(0, 8)
                        + "\n" + format.format(new Date(entry.modified)) + " · " + readableSize(entry.size);
                addCard(label, "Encrypted independently with this folder's password.", () -> showEntryActions(entry));
            }
        } catch (Exception exception) {
            addText("Could not read this folder: " + messageFor(exception));
        }
        addButton("Lock folder", this::showFolders, false);
    }

    private void showEntryActions(FolderVaultManager.Entry entry) {
        new AlertDialog.Builder(this).setTitle("Encrypted folder entry")
                .setItems(new String[]{"Export decrypted copy…", "Delete encrypted entry"}, (dialog, which) -> {
                    if (which == 0) {
                        pendingExportId = entry.id;
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("*/*")
                                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "HiddenCam-export-" + entry.id.substring(0, 8) + ".bin");
                        startActivityForResult(intent, REQUEST_EXPORT);
                    } else {
                        new AlertDialog.Builder(this).setTitle("Delete encrypted entry?")
                                .setMessage("This permanently removes the encrypted file.")
                                .setPositiveButton("Delete", (d, w) -> {
                                    try {
                                        FolderVaultManager.deleteEntry(this, activeFolderId, entry.id);
                                        showFolderContents();
                                    } catch (Exception exception) { Toast.makeText(this, messageFor(exception), Toast.LENGTH_LONG).show(); }
                                }).setNegativeButton("Cancel", null).show();
                    }
                }).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == REQUEST_IMPORT && activeKey != null) {
                FolderVaultManager.importUri(this, activeFolderId, uri, activeKey);
                Toast.makeText(this, "File encrypted and added.", Toast.LENGTH_SHORT).show();
                showFolderContents();
            } else if (requestCode == REQUEST_EXPORT && activeKey != null && pendingExportId != null) {
                FolderVaultManager.exportToUri(this, activeFolderId, pendingExportId, activeKey, uri);
                Toast.makeText(this, "Decrypted copy exported to the location you selected.", Toast.LENGTH_LONG).show();
                pendingExportId = null;
            }
        } catch (Exception exception) {
            Toast.makeText(this, "Operation failed: " + messageFor(exception), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onDestroy() {
        activeKey = null; activeFolderId = null;
        super.onDestroy();
    }

    private void addText(String value) {
        TextView text = new TextView(this); text.setText(value); text.setTextColor(COLOR_MUTED); text.setTextSize(14);
        LinearLayout.LayoutParams params = matchWrap(); params.bottomMargin = dp(12); content.addView(text, params);
    }

    private void addCard(String title, String detail, Runnable action) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12)); card.setBackgroundColor(COLOR_CARD);
        LinearLayout.LayoutParams params = matchWrap(); params.bottomMargin = dp(10); content.addView(card, params);
        TextView heading = new TextView(this); heading.setText(title); heading.setTextColor(COLOR_TEXT); heading.setTextSize(16);
        card.addView(heading, matchWrap());
        TextView description = new TextView(this); description.setText(detail); description.setTextColor(COLOR_MUTED); description.setTextSize(13);
        LinearLayout.LayoutParams detailParams = matchWrap(); detailParams.topMargin = dp(4); card.addView(description, detailParams);
        card.setOnClickListener(view -> action.run());
    }

    private void addButton(String label, Runnable action, boolean primary) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextColor(COLOR_TEXT);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary ? 0xFFE53935 : COLOR_CARD));
        LinearLayout.LayoutParams params = matchWrap(); params.bottomMargin = dp(10); content.addView(button, params);
        button.setOnClickListener(view -> action.run());
    }

    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String messageFor(Exception exception) {
        String value = exception.getMessage(); return value == null ? "Unexpected error." : value;
    }
    private String readableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
