package com.hiddencam;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.SecretKey;

/** Plays legacy and password-folder videos from app-private cache files only. */
public final class PlaybackActivity extends Activity {
    static final String EXTRA_FILE_NAME = "encrypted_file_name";
    static final String EXTRA_FOLDER_ID = "encrypted_folder_id";
    static final String EXTRA_ENTRY_ID = "encrypted_folder_entry_id";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decryptExecutor = Executors.newSingleThreadExecutor();
    private File temporaryPlaintext;
    private PlayerView playerView;
    private ExoPlayer player;
    private TextView status;
    private volatile boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ImmersiveMode.apply(this);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(0xFF090909);
        getWindow().setNavigationBarColor(0xFF090909);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(20, 24, 20, 24);
        layout.setBackgroundColor(0xFF090909);
        TextView heading = new TextView(this);
        heading.setText("Private playback");
        heading.setTextColor(0xFFF7F7F7);
        heading.setTextSize(23);
        heading.setGravity(Gravity.CENTER);
        layout.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        status = new TextView(this);
        status.setText("Verifying encrypted recording…");
        status.setTextColor(0xFFA5A5A5);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = 16;
        statusParams.bottomMargin = 16;
        layout.addView(status, statusParams);
        playerView = new PlayerView(this);
        playerView.setUseController(true);
        layout.addView(playerView, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(layout);

        String folderId = getIntent().getStringExtra(EXTRA_FOLDER_ID);
        String entryId = getIntent().getStringExtra(EXTRA_ENTRY_ID);
        if (folderId != null || entryId != null) {
            playProtectedFolderEntry(folderId, entryId);
        } else {
            playLegacyEntry(getIntent().getStringExtra(EXTRA_FILE_NAME));
        }
    }

    private void playLegacyEntry(String fileName) {
        if (fileName == null || !fileName.matches("[0-9a-fA-F]{32}\\.hcam")) {
            status.setText("Invalid vault entry.");
            return;
        }
        File encrypted = new File(new File(getFilesDir(), "vault"), fileName);
        if (!encrypted.isFile()) {
            status.setText("The encrypted recording was not found.");
            return;
        }
        decryptExecutor.execute(() -> {
            File decrypted = null;
            try {
                decrypted = VaultManager.decryptToCache(this, encrypted);
                File result = decrypted;
                handler.post(() -> openVerifiedVideo(result, "legacy recording"));
            } catch (Exception exception) {
                if (decrypted != null) decrypted.delete();
                handler.post(() -> showPlaybackError("Unable to decrypt this recording. Its file or key may be damaged."));
            }
        });
    }

    private void playProtectedFolderEntry(String folderId, String entryId) {
        if (folderId == null || !folderId.matches("[0-9a-f]{32}")
                || entryId == null || !entryId.matches("[0-9a-f]{32}")) {
            status.setText("Invalid protected-folder entry.");
            return;
        }
        if (!SessionManager.isApplicationUnlocked() || !SessionManager.isFolderUnlocked(folderId)) {
            status.setText("Unlock the application and this protected folder before playback.");
            return;
        }
        SecretKey key = SessionManager.getFolderKey(folderId);
        if (key == null) {
            status.setText("The protected folder is locked.");
            return;
        }

        decryptExecutor.execute(() -> {
            FolderVaultManager.DecryptedEntry decrypted = null;
            try {
                decrypted = FolderVaultManager.decryptEntryToCache(this, folderId, entryId, key);
                FolderVaultManager.DecryptedEntry result = decrypted;
                handler.post(() -> {
                    if (!isSupportedVideo(result.originalName)) {
                        result.file.delete();
                        showPlaybackError("This encrypted entry is not a supported video. Export it explicitly to open it elsewhere.");
                    } else {
                        openVerifiedVideo(result.file, result.originalName);
                    }
                });
            } catch (Exception exception) {
                if (decrypted != null) decrypted.file.delete();
                handler.post(() -> showPlaybackError("Unable to authenticate or decrypt this protected-folder entry."));
            }
        });
    }

    private boolean isSupportedVideo(String name) {
        String value = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return value.endsWith(".mp4") || value.endsWith(".m4v") || value.endsWith(".3gp")
                || value.endsWith(".webm") || value.endsWith(".mkv");
    }

    private void openVerifiedVideo(File file, String label) {
        if (destroyed || isFinishing()) {
            file.delete();
            return;
        }
        temporaryPlaintext = file;
        try {
            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);
            player.addListener(new Player.Listener() {
                @Override public void onPlayerError(PlaybackException error) {
                    status.setText("The recording could not be played.");
                }
            });
            player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)));
            player.prepare();
            player.play();
            status.setText("Playing locally. The decrypted cache copy is removed when you leave this screen.");
        } catch (Exception exception) {
            showPlaybackError("Could not initialize private playback.");
        }
    }

    private void showPlaybackError(String message) {
        if (!destroyed && status != null) status.setText(message);
        cleanupPlaintext();
    }

    private void cleanupPlaintext() {
        if (temporaryPlaintext != null) {
            temporaryPlaintext.delete();
            temporaryPlaintext = null;
        }
    }

    @Override protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations() && !isFinishing()) finish();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) ImmersiveMode.apply(this);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
        if (playerView != null) playerView.setPlayer(null);
        cleanupPlaintext();
        decryptExecutor.shutdownNow();
        super.onDestroy();
    }
}
