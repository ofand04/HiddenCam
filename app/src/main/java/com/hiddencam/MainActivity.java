package com.hiddencam;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_RECORDING_PERMISSIONS = 72;
    private static final int PIN_LENGTH = 6;
    private static final long LOCKOUT_MILLIS = 30_000L;
    private static final int MAX_FAILED_ATTEMPTS = 5;

    private static final int COLOR_BACKGROUND = 0xFF10131B;
    private static final int COLOR_CARD = 0xFF1A2030;
    private static final int COLOR_PRIMARY = 0xFFE53935;
    private static final int COLOR_TEXT = 0xFFF4F6FA;
    private static final int COLOR_MUTED = 0xFFABB4C4;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService pinExecutor = Executors.newSingleThreadExecutor();
    private final StringBuilder pinBuffer = new StringBuilder(PIN_LENGTH);
    private final Runnable statusRefresh = new Runnable() {
        @Override
        public void run() {
            if (!authenticated || isFinishing()) {
                return;
            }
            updateRecordingStatus();
            String result = RecordingService.lastResult;
            if (!RecordingService.isActive && result != null && !result.equals(lastDisplayedRecordingResult)) {
                lastDisplayedRecordingResult = result;
                refreshVaultList();
            }
            mainHandler.postDelayed(this, 750L);
        }
    };

    private SharedPreferences preferences;
    private LinearLayout content;
    private TextView pinDots;
    private TextView pinMessage;
    private TextView recordingStatus;
    private LinearLayout vaultContainer;
    private RadioButton frontRadio;
    private RadioButton rearRadio;
    private String frontCameraId;
    private String rearCameraId;
    private String selectedCameraId;
    private char[] firstPin;
    private boolean confirmingPin;
    private boolean authenticated;
    private boolean lockOnResume;
    private boolean permissionRequestWasForRecording;
    private boolean setupRestartOnResume;
    private boolean resumed;
    private String lastDisplayedRecordingResult = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
        );
        getWindow().setStatusBarColor(COLOR_BACKGROUND);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND);
        preferences = getSharedPreferences("private_settings", MODE_PRIVATE);
        VaultManager.cleanupStaleTemporaryFiles(this);

        if (!preferences.getBoolean("ethics_acknowledged", false)) {
            showEthicalWarning();
        } else if (!PinManager.isConfigured(preferences)) {
            showPinSetup();
        } else {
            showUnlockScreen(null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (lockOnResume && PinManager.isConfigured(preferences)) {
            lockOnResume = false;
            setupRestartOnResume = false;
            authenticated = false;
            showUnlockScreen("Enter your PIN to unlock HiddenCam.");
        } else if (setupRestartOnResume) {
            setupRestartOnResume = false;
            if (!preferences.getBoolean("ethics_acknowledged", false)) {
                showEthicalWarning();
            } else if (PinManager.isConfigured(preferences)) {
                showUnlockScreen("Please re-enter your PIN to continue.");
            } else {
                showPinSetup();
            }
        } else if (authenticated && vaultContainer != null) {
            refreshVaultList();
            lastDisplayedRecordingResult = RecordingService.lastResult == null ? "" : RecordingService.lastResult;
            updateRecordingStatus();
            mainHandler.removeCallbacks(statusRefresh);
            mainHandler.post(statusRefresh);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        mainHandler.removeCallbacks(statusRefresh);
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (authenticated && !isChangingConfigurations()) {
            lockOnResume = true;
        } else if (!isChangingConfigurations()
                && (pinBuffer.length() > 0 || firstPin != null || confirmingPin)) {
            pinBuffer.setLength(0);
            if (firstPin != null) {
                Arrays.fill(firstPin, '\0');
                firstPin = null;
            }
            confirmingPin = false;
            setupRestartOnResume = true;
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(statusRefresh);
        pinBuffer.setLength(0);
        pinExecutor.shutdown();
        if (firstPin != null) {
            Arrays.fill(firstPin, '\0');
            firstPin = null;
        }
        super.onDestroy();
    }

    private void showEthicalWarning() {
        beginScreen("Ethical use", "Please read before configuring the application.");
        addCard(content,
                "This application is an ethical privacy tool. Do not use this app to harm anyone, violate anyone's privacy, or record anyone without their knowledge and consent. You are responsible for ensuring that your use of this application complies with applicable laws and regulations.");
        addText(content,
                "Camera recording remains subject to Android permissions, camera privacy indicators, foreground-service rules and applicable laws.",
                14, COLOR_MUTED, 18);
        addButton(content, "I understand and agree", () -> {
            preferences.edit().putBoolean("ethics_acknowledged", true).apply();
            showPinSetup();
        });
    }

    private void showPinSetup() {
        confirmingPin = false;
        if (firstPin != null) {
            Arrays.fill(firstPin, '\0');
            firstPin = null;
        }
        showPinScreen("Create a six-digit PIN", "Use the built-in keypad. You will confirm the PIN once more.");
    }

    private void showUnlockScreen(String message) {
        confirmingPin = false;
        if (firstPin != null) {
            Arrays.fill(firstPin, '\0');
            firstPin = null;
        }
        showPinScreen("Unlock HiddenCam", message == null ? "Enter your six-digit PIN." : message);
    }

    private void showPinScreen(String title, String subtitle) {
        beginScreen(title, subtitle);
        pinMessage = addText(content, "Your PIN is entered only through this in-app keypad.", 14, COLOR_MUTED, 18);
        pinDots = addText(content, pinProgress(), 30, COLOR_TEXT, 24);
        pinDots.setGravity(Gravity.CENTER);

        String[][] rows = new String[][]{
                {"1", "2", "3"},
                {"4", "5", "6"},
                {"7", "8", "9"},
                {"Clear", "0", "Delete"}
        };
        for (String[] rowValues : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            rowParams.bottomMargin = dp(8);
            content.addView(row, rowParams);
            for (String value : rowValues) {
                Button key = createButton(value, true);
                LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(0, dp(58), 1f);
                keyParams.setMargins(dp(4), 0, dp(4), 0);
                row.addView(key, keyParams);
                key.setOnClickListener(view -> handlePinKey(value));
            }
        }

        addText(content, "PINs are stored as salted PBKDF2-HMAC-SHA256 verifiers. The vault uses device-keystore encryption keys.", 12, COLOR_MUTED, 24);
    }

    private void handlePinKey(String key) {
        if ("Clear".equals(key)) {
            pinBuffer.setLength(0);
            updatePinDots();
            return;
        }
        if ("Delete".equals(key)) {
            if (pinBuffer.length() > 0) {
                pinBuffer.deleteCharAt(pinBuffer.length() - 1);
            }
            updatePinDots();
            return;
        }
        if (pinBuffer.length() >= PIN_LENGTH || key.length() != 1 || key.charAt(0) < '0' || key.charAt(0) > '9') {
            return;
        }
        pinBuffer.append(key);
        updatePinDots();
        if (pinBuffer.length() == PIN_LENGTH) {
            mainHandler.postDelayed(this::completePinEntry, 120L);
        }
    }

    private void completePinEntry() {
        if (pinBuffer.length() != PIN_LENGTH) {
            return;
        }
        char[] enteredPin = new char[PIN_LENGTH];
        pinBuffer.getChars(0, PIN_LENGTH, enteredPin, 0);
        pinBuffer.setLength(0);
        updatePinDots();

        if (!PinManager.isConfigured(preferences)) {
            if (!confirmingPin) {
                firstPin = enteredPin;
                confirmingPin = true;
                if (pinMessage != null) {
                    pinMessage.setText("PIN entered. Enter the same six digits again to confirm.");
                }
                TextView heading = findHeading();
                if (heading != null) {
                    heading.setText("Confirm your six-digit PIN");
                }
            } else {
                if (firstPin == null || !Arrays.equals(firstPin, enteredPin)) {
                    Arrays.fill(enteredPin, '\0');
                    if (firstPin != null) {
                        Arrays.fill(firstPin, '\0');
                    }
                    firstPin = null;
                    confirmingPin = false;
                    Toast.makeText(this, "PINs did not match. Start again.", Toast.LENGTH_SHORT).show();
                    showPinSetup();
                    return;
                }
                char[] pinToStore = Arrays.copyOf(enteredPin, enteredPin.length);
                Arrays.fill(enteredPin, '\0');
                Arrays.fill(firstPin, '\0');
                firstPin = null;
                pinExecutor.execute(() -> {
                    try {
                        PinManager.setPin(preferences, pinToStore);
                        mainHandler.post(() -> {
                            if (isFinishing()) {
                                return;
                            }
                            if (!resumed) {
                                authenticated = false;
                                lockOnResume = true;
                                return;
                            }
                            confirmingPin = false;
                            authenticated = true;
                            showDashboard();
                        });
                    } catch (Exception exception) {
                        mainHandler.post(() -> {
                            if (!isFinishing()) {
                                confirmingPin = false;
                                showPinSetup();
                                Toast.makeText(this, "Unable to configure PIN storage.", Toast.LENGTH_LONG).show();
                            }
                        });
                    } finally {
                        Arrays.fill(pinToStore, '\0');
                    }
                });
            }
            return;
        }

        long lockUntil = preferences.getLong("pin_lock_until", 0L);
        if (System.currentTimeMillis() < lockUntil) {
            Arrays.fill(enteredPin, '\0');
            long remaining = Math.max(1L, (lockUntil - System.currentTimeMillis() + 999L) / 1000L);
            Toast.makeText(this, "Try again in " + remaining + " seconds.", Toast.LENGTH_SHORT).show();
            return;
        }

        pinExecutor.execute(() -> {
            boolean accepted = false;
            try {
                accepted = PinManager.verify(preferences, enteredPin);
            } catch (Exception ignored) {
            } finally {
                Arrays.fill(enteredPin, '\0');
            }
            boolean result = accepted;
            mainHandler.post(() -> {
                if (result) {
                    preferences.edit().putInt("pin_failed_attempts", 0).putLong("pin_lock_until", 0L).apply();
                    if (!resumed || isFinishing()) {
                        authenticated = false;
                        lockOnResume = true;
                        return;
                    }
                    authenticated = true;
                    showDashboard();
                } else {
                    int attempts = preferences.getInt("pin_failed_attempts", 0) + 1;
                    SharedPreferences.Editor editor = preferences.edit();
                    if (attempts >= MAX_FAILED_ATTEMPTS) {
                        editor.putInt("pin_failed_attempts", 0)
                                .putLong("pin_lock_until", System.currentTimeMillis() + LOCKOUT_MILLIS);
                    } else {
                        editor.putInt("pin_failed_attempts", attempts);
                    }
                    editor.apply();
                    Toast.makeText(this, "Incorrect PIN.", Toast.LENGTH_SHORT).show();
                    updatePinDots();
                }
            });
        });
    }

    private TextView findHeading() {
        if (content == null || content.getChildCount() == 0) {
            return null;
        }
        View first = content.getChildAt(0);
        return first instanceof TextView ? (TextView) first : null;
    }

    private String pinProgress() {
        StringBuilder display = new StringBuilder();
        for (int index = 0; index < PIN_LENGTH; index++) {
            display.append(index < pinBuffer.length() ? "● " : "○ ");
        }
        return display.toString().trim();
    }

    private void updatePinDots() {
        if (pinDots != null) {
            pinDots.setText(pinProgress());
        }
    }

    private void showDashboard() {
        authenticated = true;
        lockOnResume = false;
        locateCameras();
        beginScreen("HiddenCam", "Private camera recording and encrypted local storage.");

        addCard(content,
                "Recording is visible. Android displays its camera privacy indicator and an ongoing notification while the camera is active. Start recording while HiddenCam is open; supported devices may continue recording after the screen locks.");

        addText(content, "Camera", 18, COLOR_TEXT, 8);
        RadioGroup cameraGroup = new RadioGroup(this);
        cameraGroup.setOrientation(RadioGroup.VERTICAL);
        frontRadio = new RadioButton(this);
        frontRadio.setId(View.generateViewId());
        frontRadio.setText("Front camera");
        frontRadio.setTextColor(COLOR_TEXT);
        frontRadio.setEnabled(frontCameraId != null);
        rearRadio = new RadioButton(this);
        rearRadio.setId(View.generateViewId());
        rearRadio.setText("Rear camera");
        rearRadio.setTextColor(COLOR_TEXT);
        rearRadio.setEnabled(rearCameraId != null);
        cameraGroup.addView(rearRadio);
        cameraGroup.addView(frontRadio);
        content.addView(cameraGroup, matchWrap());

        if (rearCameraId != null) {
            rearRadio.setChecked(true);
            selectedCameraId = rearCameraId;
        } else if (frontCameraId != null) {
            frontRadio.setChecked(true);
            selectedCameraId = frontCameraId;
        }
        cameraGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (frontRadio != null && checkedId == frontRadio.getId()) {
                selectedCameraId = frontCameraId;
            } else if (rearRadio != null && checkedId == rearRadio.getId()) {
                selectedCameraId = rearCameraId;
            }
        });

        addButton(content, "Start visible recording", this::requestRecordingPermissions);
        addButton(content, "Stop recording and choose Save or Delete", this::confirmStopRecording);
        recordingStatus = addText(content, "", 14, COLOR_MUTED, 16);
        updateRecordingStatus();
        lastDisplayedRecordingResult = RecordingService.lastResult == null ? "" : RecordingService.lastResult;
        mainHandler.removeCallbacks(statusRefresh);
        mainHandler.post(statusRefresh);

        addText(content, "Encrypted recordings", 18, COLOR_TEXT, 8);
        vaultContainer = new LinearLayout(this);
        vaultContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(vaultContainer, matchWrap());
        refreshVaultList();

        addButton(content, "Lock HiddenCam", () -> {
            authenticated = false;
            showUnlockScreen("HiddenCam is locked.");
        });
        addText(content,
                "No microphone, network, contacts, location or external-storage permission is requested. PIN entry uses an in-app keypad.",
                12, COLOR_MUTED, 20);
    }

    private void locateCameras() {
        frontCameraId = null;
        rearCameraId = null;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (manager == null) {
                return;
            }
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT && frontCameraId == null) {
                    frontCameraId = id;
                } else if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK && rearCameraId == null) {
                    rearCameraId = id;
                }
            }
        } catch (CameraAccessException | SecurityException ignored) {
        }
    }

    private void requestRecordingPermissions() {
        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!missing.isEmpty()) {
            permissionRequestWasForRecording = true;
            requestPermissions(missing.toArray(new String[0]), REQUEST_RECORDING_PERMISSIONS);
        } else {
            locateCameras();
            if (selectedCameraId == null) {
                selectedCameraId = rearCameraId != null ? rearCameraId : frontCameraId;
            }
            startRecordingService();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORDING_PERMISSIONS || !permissionRequestWasForRecording) {
            return;
        }
        permissionRequestWasForRecording = false;
        boolean cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean notificationsGranted = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        if (cameraGranted && notificationsGranted) {
            showDashboard();
            startRecordingService();
        } else {
            Toast.makeText(this,
                    "Camera and notification permissions are required so camera use and recording controls remain visible.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void startRecordingService() {
        if (selectedCameraId == null) {
            Toast.makeText(this, "No usable camera was found on this device.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(this, RecordingService.class)
                .setAction(RecordingService.ACTION_START)
                .putExtra(RecordingService.EXTRA_CAMERA_ID, selectedCameraId);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            updateRecordingStatus();
        } catch (SecurityException exception) {
            Toast.makeText(this, "Android did not allow the camera service to start. Keep HiddenCam visible and review permissions.", Toast.LENGTH_LONG).show();
        }
    }

    private void confirmStopRecording() {
        if (!RecordingService.isActive) {
            Toast.makeText(this, "No recording service is active.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Finish recording")
                .setMessage("Choose whether to keep an encrypted copy in the private vault or discard this recording.")
                .setPositiveButton("Save encrypted", (dialog, which) -> sendStopAction(RecordingService.ACTION_SAVE_STOP))
                .setNegativeButton("Delete recording", (dialog, which) -> sendStopAction(RecordingService.ACTION_DISCARD_STOP))
                .setNeutralButton("Cancel", null)
                .show();
    }

    private void sendStopAction(String action) {
        Intent intent = new Intent(this, RecordingService.class).setAction(action);
        startService(intent);
        if (recordingStatus != null) {
            recordingStatus.setText("Finalizing recording. Temporary data will be removed after processing.");
        }
    }

    private void updateRecordingStatus() {
        if (recordingStatus == null) {
            return;
        }
        if (RecordingService.isRecording) {
            recordingStatus.setText("Camera is active. Check the Android privacy indicator and recording notification.");
        } else if (RecordingService.isActive) {
            recordingStatus.setText("Camera service is starting or finalizing a recording.");
        } else if (RecordingService.lastResult != null && !RecordingService.lastResult.isEmpty()) {
            recordingStatus.setText(RecordingService.lastResult);
        } else {
            recordingStatus.setText("Camera is idle.");
        }
    }

    private void refreshVaultList() {
        if (vaultContainer == null) {
            return;
        }
        vaultContainer.removeAllViews();
        File[] recordings = VaultManager.listEncryptedFiles(this);
        if (recordings.length == 0) {
            addText(vaultContainer, "No encrypted recordings yet.", 14, COLOR_MUTED, 12);
            return;
        }
        DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (File recording : recordings) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackgroundTintList(null);
            row.setBackgroundColor(COLOR_CARD);
            LinearLayout.LayoutParams rowParams = matchWrap();
            rowParams.bottomMargin = dp(10);
            vaultContainer.addView(row, rowParams);

            TextView details = addText(row,
                    "Encrypted video\n" + dateFormat.format(new Date(recording.lastModified()))
                            + " · " + readableSize(recording.length()),
                    14, COLOR_TEXT, 8);
            details.setTextIsSelectable(false);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(actions, matchWrap());
            Button play = createButton("Play privately", false);
            Button delete = createButton("Delete", false);
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
            actionParams.setMargins(0, 0, dp(6), 0);
            actions.addView(play, actionParams);
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
            deleteParams.setMargins(dp(6), 0, 0, 0);
            actions.addView(delete, deleteParams);

            play.setOnClickListener(view -> {
                Intent intent = new Intent(this, PlaybackActivity.class);
                intent.putExtra(PlaybackActivity.EXTRA_FILE_NAME, recording.getName());
                startActivity(intent);
            });
            delete.setOnClickListener(view -> new AlertDialog.Builder(this)
                    .setTitle("Delete encrypted recording")
                    .setMessage("The recording's device-keystore key will be deleted first. This makes the encrypted file unrecoverable if key deletion succeeds.")
                    .setPositiveButton("Delete", (dialog, which) -> {
                        try {
                            boolean removed = VaultManager.deleteEncrypted(this, recording);
                            Toast.makeText(this, removed ? "Recording deleted." : "Could not remove the encrypted file.", Toast.LENGTH_SHORT).show();
                            refreshVaultList();
                        } catch (Exception exception) {
                            Toast.makeText(this, "Could not delete the vault entry.", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
        }
    }

    private String readableSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void beginScreen(String title, String subtitle) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER_HORIZONTAL);
        outer.setPadding(dp(22), dp(28), dp(22), dp(30));
        outer.setBackgroundColor(COLOR_BACKGROUND);
        scroll.addView(outer, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(COLOR_TEXT);
        heading.setTextSize(27);
        heading.setGravity(Gravity.CENTER);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams headingParams = matchWrap();
        headingParams.bottomMargin = dp(8);
        outer.addView(heading, headingParams);

        TextView description = new TextView(this);
        description.setText(subtitle);
        description.setTextColor(COLOR_MUTED);
        description.setTextSize(14);
        description.setGravity(Gravity.CENTER);
        description.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams descriptionParams = matchWrap();
        descriptionParams.bottomMargin = dp(22);
        outer.addView(description, descriptionParams);

        content = outer;
        setContentView(scroll);
        pinDots = null;
        pinMessage = null;
        recordingStatus = null;
        vaultContainer = null;
    }

    private TextView addText(LinearLayout parent, String value, int size, int color, int bottomMargin) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(bottomMargin);
        parent.addView(text, params);
        return text;
    }

    private void addCard(LinearLayout parent, String value) {
        TextView card = new TextView(this);
        card.setText(value);
        card.setTextSize(15);
        card.setTextColor(COLOR_TEXT);
        card.setLineSpacing(dp(4), 1f);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackgroundColor(COLOR_CARD);
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(18);
        parent.addView(card, params);
    }

    private void addButton(LinearLayout parent, String label, Runnable action) {
        Button button = createButton(label, true);
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(10);
        parent.addView(button, params);
        button.setOnClickListener(view -> action.run());
    }

    private Button createButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(COLOR_TEXT);
        button.setMinHeight(dp(46));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary ? COLOR_PRIMARY : COLOR_CARD));
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
