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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Encrypted recordings are decrypted only into app-private cache for local Media3 playback. */
public final class PlaybackActivity extends Activity {
    static final String EXTRA_FILE_NAME = "encrypted_file_name";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decryptExecutor = Executors.newSingleThreadExecutor();
    private File temporaryPlaintext;
    private PlayerView playerView;
    private ExoPlayer player;
    private TextView status;
    private volatile boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(0xFF10131B);
        getWindow().setNavigationBarColor(0xFF10131B);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(20, 24, 20, 24);
        layout.setBackgroundColor(0xFF10131B);
        TextView heading = new TextView(this);
        heading.setText("Private playback"); heading.setTextColor(0xFFF4F6FA);
        heading.setTextSize(23); heading.setGravity(Gravity.CENTER);
        layout.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        status = new TextView(this); status.setText("Decrypting inside the app's private cache…");
        status.setTextColor(0xFFABB4C4); status.setTextSize(14); status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = 16; statusParams.bottomMargin = 16; layout.addView(status, statusParams);
        playerView = new PlayerView(this);
        playerView.setUseController(true);
        layout.addView(playerView, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(layout);

        String fileName = getIntent().getStringExtra(EXTRA_FILE_NAME);
        if (fileName == null || !fileName.matches("[0-9a-fA-F]{32}\\.hcam")) {
            status.setText("Invalid vault entry."); return;
        }
        File encrypted = new File(new File(getFilesDir(), "vault"), fileName);
        if (!encrypted.isFile()) { status.setText("The encrypted recording was not found."); return; }

        decryptExecutor.execute(() -> {
            File decrypted = null;
            try {
                decrypted = VaultManager.decryptToCache(this, encrypted);
                File result = decrypted;
                handler.post(() -> {
                    if (destroyed || isFinishing()) { result.delete(); return; }
                    temporaryPlaintext = result;
                    try {
                        player = new ExoPlayer.Builder(this).build();
                        playerView.setPlayer(player);
                        player.addListener(new Player.Listener() {
                            @Override public void onPlayerError(PlaybackException error) {
                                status.setText("The recording could not be played.");
                            }
                        });
                        player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(result)));
                        player.prepare(); player.play();
                        status.setText("Local playback. The decrypted cache copy is removed when you leave this screen.");
                    } catch (Exception exception) {
                        status.setText("Could not initialize secure playback.");
                        cleanupPlaintext();
                    }
                });
            } catch (Exception exception) {
                if (decrypted != null) decrypted.delete();
                handler.post(() -> status.setText("Unable to decrypt this recording. The file or its key may be damaged."));
            }
        });
    }

    private void cleanupPlaintext() {
        if (temporaryPlaintext != null) { temporaryPlaintext.delete(); temporaryPlaintext = null; }
    }

    @Override protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations() && !isFinishing()) finish();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (player != null) { player.stop(); player.release(); player = null; }
        if (playerView != null) playerView.setPlayer(null);
        cleanupPlaintext();
        decryptExecutor.shutdownNow();
        super.onDestroy();
    }
}
