package com.hiddencam;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;

public final class PlaybackActivity extends Activity {
    static final String EXTRA_FILE_NAME = "encrypted_file_name";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private File temporaryPlaintext;
    private VideoView videoView;
    private TextView status;
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
        );
        getWindow().setStatusBarColor(0xFF10131B);
        getWindow().setNavigationBarColor(0xFF10131B);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(20, 24, 20, 24);
        layout.setBackgroundColor(0xFF10131B);

        TextView heading = new TextView(this);
        heading.setText("Private playback");
        heading.setTextColor(0xFFF4F6FA);
        heading.setTextSize(23);
        heading.setGravity(Gravity.CENTER);
        layout.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setText("Decrypting inside the app's private cache…");
        status.setTextColor(0xFFABB4C4);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = 16;
        statusParams.bottomMargin = 16;
        layout.addView(status, statusParams);

        videoView = new VideoView(this);
        LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        layout.addView(videoView, videoParams);
        setContentView(layout);

        String fileName = getIntent().getStringExtra(EXTRA_FILE_NAME);
        if (fileName == null || !fileName.matches("[0-9a-fA-F]{32}\\.hcam")) {
            status.setText("Invalid vault entry.");
            return;
        }

        File encrypted = new File(new File(getFilesDir(), "vault"), fileName);
        if (!encrypted.isFile()) {
            status.setText("The encrypted recording was not found.");
            return;
        }

        new Thread(() -> {
            File decrypted = null;
            try {
                decrypted = VaultManager.decryptToCache(this, encrypted);
                File result = decrypted;
                handler.post(() -> {
                    if (destroyed || isFinishing()) {
                        result.delete();
                        return;
                    }
                    temporaryPlaintext = result;
                    status.setText("Playback is local to HiddenCam. The temporary decrypted copy is deleted when this screen closes.");
                    MediaController controls = new MediaController(this);
                    controls.setAnchorView(videoView);
                    videoView.setMediaController(controls);
                    videoView.setVideoPath(result.getAbsolutePath());
                    videoView.setOnErrorListener((mediaPlayer, what, extra) -> {
                        status.setText("The recording could not be played.");
                        return true;
                    });
                    videoView.start();
                });
            } catch (Exception exception) {
                if (decrypted != null) {
                    decrypted.delete();
                }
                handler.post(() -> status.setText("Unable to decrypt this recording. The file or its key may be damaged."));
            }
        }, "HiddenCamPlaybackDecrypt").start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations() && !isFinishing()) {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (videoView != null) {
            videoView.stopPlayback();
        }
        if (temporaryPlaintext != null) {
            temporaryPlaintext.delete();
            temporaryPlaintext = null;
        }
        super.onDestroy();
    }
}
