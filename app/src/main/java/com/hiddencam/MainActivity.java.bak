package com.hiddencam;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.text.InputType;
import android.widget.EditText;
import android.view.Gravity;
import android.view.View;
import android.view.Surface;
import android.view.TextureView;
import android.util.Size;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_RECORDING_PERMISSIONS = 72;
    private static final int REQUEST_PREVIEW_PERMISSION = 73;
    private static final int PIN_LENGTH = 6;
    private static final long LOCKOUT_MILLIS = 30_000L;
    private static final int MAX_FAILED_ATTEMPTS = 5;

    private static final int COLOR_BACKGROUND = 0xFF090909;
    private static final int COLOR_CARD = 0xFF1B1B1B;
    private static final int COLOR_PRIMARY = 0xFFF2F2F2;
    private static final int COLOR_TEXT = 0xFFF7F7F7;
    private static final int COLOR_MUTED = 0xFFA5A5A5;

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
            if (!RecordingService.isActive && waitingForRecordingCameraRelease) {
                waitingForRecordingCameraRelease = false;
                if (previewRequested) startCameraPreview();
            }
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
    private TextView previewStatus;
    private TextureView cameraPreview;
    private CameraDevice previewCamera;
    private CameraCaptureSession previewSession;
    private Surface previewSurface;
    private HandlerThread previewThread;
    private Handler previewHandler;
    private boolean previewOpening;
    private boolean previewActive;
    private int previewGeneration;
    private boolean previewRequested;
    private boolean waitingForRecordingCameraRelease;
    private TextView folderStatus;
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
    private boolean folderSelectionPrompted;
    private String lastDisplayedRecordingResult = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ImmersiveMode.apply(this);
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

    private long lastVolumeUpPress = 0L;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.getRepeatCount() == 0 && authenticated) {
            long now = SystemClock.elapsedRealtime();
            if (lastVolumeUpPress != 0L && now - lastVolumeUpPress <= 3000L) {
                lastVolumeUpPress = 0L;
                triggerEmergencyLock();
                return true;
            }
            lastVolumeUpPress = now;
            return super.onKeyDown(keyCode, event);
        }
        return super.onKeyDown(keyCode, event);
    }

    private void triggerEmergencyLock() {
        authenticated = false;
        lockOnResume = true;
        if (RecordingService.isActive) {
            Intent emergency = new Intent(this, RecordingService.class)
                    .setAction(RecordingService.ACTION_EMERGENCY_SAVE_LOCK);
            try { startService(emergency); }
            catch (Exception exception) { Toast.makeText(this, "Could not request emergency save. Check the recording notification.", Toast.LENGTH_LONG).show(); }
        }
        SecurityCleanup.performLockCleanup(this);
        showUnlockScreen("Emergency lock activated. Hold = after entering your PIN.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        ImmersiveMode.apply(this);
        resumed = true;
        if (lockOnResume && PinManager.isConfigured(preferences)) {
            lockOnResume = false;
            setupRestartOnResume = false;
            authenticated = false;
            showUnlockScreen("HiddenCam is locked.");
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
            updateFolderStatus();
            lastDisplayedRecordingResult = RecordingService.lastResult == null ? "" : RecordingService.lastResult;
            updateRecordingStatus();
            mainHandler.removeCallbacks(statusRefresh);
            mainHandler.post(statusRefresh);
            if (previewRequested && cameraPreview != null && cameraPreview.isAvailable() && !RecordingService.isActive) startCameraPreview();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) ImmersiveMode.apply(this);
    }

    @Override
    protected void onPause() {
        resumed = false;
        stopCameraPreview();
        mainHandler.removeCallbacks(statusRefresh);
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Persistent unlocked sessions: do NOT lock on normal navigation or onStop.
        // Locking occurs only via the explicit Lock button or the emergency Volume-Up action.
        if (!isChangingConfigurations()
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
        stopCameraPreview();
        if (firstPin != null) {
            Arrays.fill(firstPin, '\0');
            firstPin = null;
        }
        super.onDestroy();
    }

    private void showEthicalWarning() {
        beginScreen("Responsible use", "Please read before configuring the application.");
        addCard(content,
                "HiddenCam records video using this device's camera. Recording requires your explicit action and Android camera permission. Android's camera privacy indicator and HiddenCam's ongoing recording notification remain visible whenever the camera is active. Use the camera only where you have authorization and respect the privacy and rights of others.");
        addButton(content, "Continue", () -> {
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
        showPinScreen("Create your PIN", "Choose 4–12 digits. Tap = to calculate; hold = to confirm your PIN.");
    }

    private void showUnlockScreen(String message) {
        confirmingPin = false;
        if (firstPin != null) {
            Arrays.fill(firstPin, '\0');
            firstPin = null;
        }
        showPinScreen("Calculator", message == null ? "Tap = to calculate. Hold = to unlock HiddenCam." : message + "  Hold = to unlock.");
    }

    private void showPinScreen(String title, String subtitle) {
        beginScreen(title, subtitle);
        TextView label = addText(content, "CALCULATOR", 12, COLOR_MUTED, 8);
        label.setGravity(Gravity.END);
        pinMessage = addText(content, "Tap = to calculate. Hold = to unlock.", 13, COLOR_MUTED, 8);
        pinMessage.setGravity(Gravity.END);
        pinDots = addText(content, "0", 38, COLOR_TEXT, 20);
        pinDots.setGravity(Gravity.END);
        pinDots.setSingleLine(true);
        pinDots.setPadding(dp(8), dp(16), dp(8), dp(16));
        pinDots.setBackgroundColor(COLOR_CARD);
        String[][] rows = {
            {"C", "⌫", "(", ")"},
            {"7", "8", "9", "÷"},
            {"4", "5", "6", "×"},
            {"1", "2", "3", "−"},
            {"0", ".", "%", "+"}
        };
        for (String[] values : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.bottomMargin = dp(8);
            content.addView(row, rp);
            for (String value : values) {
                Button key = createButton(value, false);
                key.setTextSize(20);
                LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(0, dp(62), 1f);
                kp.setMargins(dp(4), 0, dp(4), 0);
                row.addView(key, kp);
                key.setOnClickListener(v -> handlePinKey(value));
            }
        }
        Button equals = createButton("=", true);
        equals.setTextSize(22);
        LinearLayout.LayoutParams equalsParams = matchWrap();
        equalsParams.topMargin = dp(2);
        content.addView(equals, equalsParams);
        equals.setOnClickListener(v -> evaluateCalculatorEntry());
        equals.setOnLongClickListener(v -> {
            if (pinBuffer.length() > 0) completePinEntry();
            return true;
        });
        addText(content, PinManager.isConfigured(preferences)
            ? "Use the calculator normally. Hold the = key to submit the displayed value as your PIN."
            : "Create a 4–12 digit PIN, then hold = to confirm it. You will enter it the same way next time.",
            12, COLOR_MUTED, 16);
    }

    private void handlePinKey(String key) {
        if ("C".equals(key)) { pinBuffer.setLength(0); updatePinDots(); return; }
        if ("⌫".equals(key)) {
            if (pinBuffer.length()>0) pinBuffer.deleteCharAt(pinBuffer.length()-1);
            updatePinDots(); return;
        }
        if ("=".equals(key)) { evaluateCalculatorEntry(); return; }
        boolean configured=PinManager.isConfigured(preferences);
        if (!configured && !key.matches("[0-9]{1,2}")) return;
        String value=key;
        if ("×".equals(value)) value="*";
        else if ("÷".equals(value)) value="/";
        else if ("−".equals(value)) value="-";
        if (!value.matches("[0-9]{1,2}|[()+*/.%\\-]") || pinBuffer.length()+value.length()>120) return;
        pinBuffer.append(value); updatePinDots();
    }

    private void evaluateCalculatorEntry() {
        if (pinBuffer.length() == 0) return;
        try {
            String result = CalculatorExpression.evaluateForDisplay(pinBuffer.toString());
            pinBuffer.setLength(0);
            pinBuffer.append(result);
            updatePinDots();
        } catch (IllegalArgumentException exception) {
            if (pinDots != null) pinDots.setText("Error");
        }
    }

    private void completePinEntry() {
        String enteredText=pinBuffer.toString().trim();
        pinBuffer.setLength(0); updatePinDots();
        if (enteredText.isEmpty()) return;
        char[] enteredPin;
        if (!PinManager.isConfigured(preferences)) {
            if (!enteredText.matches("[0-9]{4,12}")) {
                Toast.makeText(this,"Use 4–12 digits for the PIN.",Toast.LENGTH_SHORT).show(); return;
            }
            enteredPin=enteredText.toCharArray();
        } else if (enteredText.matches("[0-9]{4,12}")) {
            enteredPin=enteredText.toCharArray();
        } else {
            try { enteredPin=CalculatorExpression.toSixDigitPin(enteredText); }
            catch (Exception e) {
                Toast.makeText(this,e.getMessage()==null?"Invalid calculator expression.":e.getMessage(),Toast.LENGTH_LONG).show();
                return;
            }
        }
        if (!PinManager.isConfigured(preferences)) {
            if (!confirmingPin) {
                firstPin = enteredPin;
                confirmingPin = true;
                if (pinMessage != null) {
                    pinMessage.setText("PIN entered. Hold = again to confirm.");
                }
                TextView heading = findHeading();
                if (heading != null) {
                    heading.setText("Confirm your PIN");
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
                            SessionManager.setApplicationUnlocked(true);
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
            // Do not reveal the authentication lockout state on the calculator screen.
            return;
        }

        final char[] pinCopy = Arrays.copyOf(enteredPin, enteredPin.length);
        Arrays.fill(enteredPin, '\0');
        pinExecutor.execute(() -> {
            boolean isDuress = false;
            boolean accepted = false;
            try {
                if (PinManager.isDuressConfigured(preferences) && PinManager.verifyDuress(preferences, pinCopy)) {
                    isDuress = true;
                } else {
                    accepted = PinManager.verify(preferences, pinCopy);
                }
            } catch (Exception ignored) {
            } finally {
                Arrays.fill(pinCopy, '\0');
            }
            final boolean duress = isDuress;
            final boolean result = accepted;
            mainHandler.post(() -> {
                if (duress) {
                    // Immediate silent wipe. No confirmation, no indication of the code used.
                    SecurityCleanup.performDuressWipe(MainActivity.this);
                    authenticated = false;
                    Toast.makeText(MainActivity.this, "Application data cleared.", Toast.LENGTH_LONG).show();
                    showEthicalWarning();
                    return;
                }
                if (result) {
                    preferences.edit().putInt("pin_failed_attempts", 0).putLong("pin_lock_until", 0L).apply();
                    if (!resumed || isFinishing()) {
                        authenticated = false;
                        lockOnResume = true;
                        return;
                    }
                    authenticated = true; SessionManager.setApplicationUnlocked(true);
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
                    // A rejected code leaves the ordinary calculator screen unchanged.
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
        return pinBuffer.length()==0 ? "0" : pinBuffer.toString();
    }

    private void updatePinDots() {
        if (pinDots!=null) pinDots.setText(pinProgress());
    }

    private void showDashboard() {
        authenticated = true; SessionManager.setApplicationUnlocked(true);
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
            stopCameraPreview();
            if (resumed && cameraPreview != null && cameraPreview.isAvailable()) startCameraPreview();
        });

        addText(content, "Live camera preview", 16, COLOR_TEXT, 8);
        cameraPreview = new TextureView(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, dp(220));
        previewParams.bottomMargin = dp(8);
        content.addView(cameraPreview, previewParams);
        previewStatus = addText(content, "Preview is off. Enable it to see the selected camera.", 12, COLOR_MUTED, 6);
        cameraPreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (resumed && authenticated && previewRequested) startCameraPreview();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                stopCameraPreview();
                if (resumed && authenticated && previewRequested) startCameraPreview();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                stopCameraPreview();
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
        });
        addButton(content, "Enable live preview", this::enableCameraPreview);

        folderStatus = addText(content, "", 14, COLOR_MUTED, 10);
        updateFolderStatus();
        addButton(content, "▣  Choose encrypted recording folder", () -> startActivity(new Intent(this, FolderVaultActivity.class)));
        addButton(content, "●  Start visible recording", this::requestRecordingPermissions);
        addButton(content, "■  Stop recording · Save or Delete", this::confirmStopRecording);
        addButton(content, "▣  Manage encrypted folders", () -> startActivity(new Intent(this, FolderVaultActivity.class)));
        addButton(content, "Recover failed recordings", () -> startActivity(new Intent(this, RecoveryActivity.class)));
        addButton(content, "Change app name and icon", this::showIdentityPicker);
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
        if (!hasRecordingDestination() && !folderSelectionPrompted) {
            folderSelectionPrompted = true;
            mainHandler.post(() -> {
                if (authenticated && !isFinishing() && !hasRecordingDestination())
                    startActivity(new Intent(this, FolderVaultActivity.class));
            });
        }

        addButton(content, "Lock HiddenCam", () -> {
            authenticated = false;
            SecurityCleanup.performLockCleanup(this);
            showUnlockScreen("HiddenCam is locked.");
        });
        addButton(content, "Security settings (Duress Code)", this::showSecuritySettings);
        addText(content,
                "No microphone, network, contacts, location or external-storage permission is requested. PIN entry uses an in-app keypad. Double Volume-Up triggers emergency lock while the app is in the foreground.",
                12, COLOR_MUTED, 20);
    }

    private void showSecuritySettings() {
        if (!authenticated) {
            return;
        }
        CharSequence[] items = new CharSequence[]{
                PinManager.isDuressConfigured(preferences) ? "Change Duress Code" : "Set Duress Code",
                "Disable Duress Code",
                "Cancel"
        };
        new AlertDialog.Builder(this)
                .setTitle("Duress Code")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        promptDuressCode(false);
                    } else if (which == 1) {
                        PinManager.clearDuressCode(preferences);
                        Toast.makeText(this, "Duress Code disabled.", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void promptDuressCode(boolean confirming) {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("4–12 digits, different from PIN");
        new AlertDialog.Builder(this)
                .setTitle(confirming ? "Confirm Duress Code" : "Set Duress Code")
                .setMessage("Entering this code on the unlock screen will permanently erase all HiddenCam private data. There is no confirmation dialog.")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    char[] code = input.getText().toString().toCharArray();
                    try {
                        PinManager.setDuressCode(preferences, code);
                        Toast.makeText(this, "Duress Code configured.", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, e.getMessage() == null ? "Could not set Duress Code." : e.getMessage(), Toast.LENGTH_LONG).show();
                    } finally {
                        java.util.Arrays.fill(code, '\0');
                        input.setText("");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showIdentityPicker() {
        new AlertDialog.Builder(this)
                .setTitle("Launcher name and icon")
                .setItems(AppIdentityManager.LABELS, (dialog, which) -> {
                    try {
                        AppIdentityManager.apply(this, which);
                        Toast.makeText(this, "Launcher identity changed. Android may take a moment to refresh the icon.", Toast.LENGTH_LONG).show();
                    } catch (Exception exception) {
                        Toast.makeText(this, "Could not change the launcher identity.", Toast.LENGTH_LONG).show();
                    }
                }).show();
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

    private boolean hasRecordingDestination() {
        String id=SessionManager.getActiveRecordingFolder();
        return id!=null && SessionManager.getFolderKey(id)!=null;
    }

    private void updateFolderStatus() {
        if (folderStatus==null) return;
        boolean ready=hasRecordingDestination();
        folderStatus.setText(ready ? "✓  Encrypted recording destination is unlocked."
            : "No place to save recording. Create or unlock an encrypted folder first.");
        folderStatus.setTextColor(ready ? COLOR_TEXT : COLOR_MUTED);
    }

    private void enableCameraPreview() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_PREVIEW_PERMISSION);
            return;
        }
        refreshCameraChoices();
        if (selectedCameraId == null) {
            setPreviewStatus("No compatible camera was found on this device.");
            return;
        }
        previewRequested = true;
        startCameraPreview();
    }

    private void refreshCameraChoices() {
        locateCameras();
        if (frontRadio != null) frontRadio.setEnabled(frontCameraId != null);
        if (rearRadio != null) rearRadio.setEnabled(rearCameraId != null);
        if (selectedCameraId == null
                || (!selectedCameraId.equals(frontCameraId) && !selectedCameraId.equals(rearCameraId))) {
            selectedCameraId = rearCameraId != null ? rearCameraId : frontCameraId;
        }
        if (frontRadio != null && selectedCameraId != null && selectedCameraId.equals(frontCameraId)) {
            frontRadio.setChecked(true);
        } else if (rearRadio != null && selectedCameraId != null && selectedCameraId.equals(rearCameraId)) {
            rearRadio.setChecked(true);
        }
    }

    private void setPreviewStatus(String message) {
        mainHandler.post(() -> {
            if (previewStatus != null) previewStatus.setText(message);
        });
    }

    private void startCameraPreview() {
        if (!resumed || !authenticated || RecordingService.isActive || waitingForRecordingCameraRelease
                || previewOpening || previewCamera != null || cameraPreview == null || !cameraPreview.isAvailable()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setPreviewStatus("Camera permission is required to show a live preview.");
            return;
        }
        if (selectedCameraId == null) {
            setPreviewStatus("Select an available front or rear camera.");
            return;
        }
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (manager == null) throw new IllegalStateException("Camera service is unavailable.");
            if (previewThread == null) {
                previewThread = new HandlerThread("HiddenCamPreviewThread");
                previewThread.start();
                previewHandler = new Handler(previewThread.getLooper());
            }
            previewOpening = true;
            final int generation = ++previewGeneration;
            String cameraId = selectedCameraId;
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            android.hardware.camera2.params.StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size previewSize = choosePreviewSize(map == null ? null : map.getOutputSizes(SurfaceTexture.class));
            SurfaceTexture texture = cameraPreview.getSurfaceTexture();
            if (texture == null) throw new IllegalStateException("Preview surface is unavailable.");
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            setPreviewStatus("Opening camera preview… Android may show its camera privacy indicator.");
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    if (generation != previewGeneration) { camera.close(); return; }
                    previewOpening = false;
                    if (!resumed || !authenticated || RecordingService.isActive || cameraPreview == null || !cameraPreview.isAvailable()) {
                        camera.close();
                        return;
                    }
                    previewCamera = camera;
                    try {
                        Surface surface = new Surface(cameraPreview.getSurfaceTexture());
                        previewSurface = surface;
                        CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        request.addTarget(surface);
                        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        camera.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession session) {
                                if (generation != previewGeneration || previewCamera != camera || previewSurface != surface || !resumed || RecordingService.isActive) {
                                    session.close();
                                    surface.release();
                                    if (previewSurface == surface) previewSurface = null;
                                    return;
                                }
                                previewSession = session;
                                try {
                                    session.setRepeatingRequest(request.build(), null, previewHandler);
                                    previewActive = true;
                                    setPreviewStatus("Live preview active. Android camera privacy indicator is enabled.");
                                } catch (CameraAccessException | IllegalStateException exception) {
                                    setPreviewStatus("Preview stopped: " + safeCameraMessage(exception));
                                    stopCameraPreview();
                                }
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession session) {
                                session.close();
                                surface.release();
                                if (previewSurface == surface) previewSurface = null;
                                setPreviewStatus("The camera could not configure a preview session.");
                                stopCameraPreview();
                            }
                        }, previewHandler);
                    } catch (Exception exception) {
                        setPreviewStatus("Could not start preview: " + safeCameraMessage(exception));
                        stopCameraPreview();
                    }
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    if (generation != previewGeneration) { camera.close(); return; }
                    camera.close();
                    if (previewCamera == camera) previewCamera = null;
                    stopCameraPreview();
                    setPreviewStatus("Camera disconnected. You can try enabling preview again.");
                }
                @Override public void onError(CameraDevice camera, int error) {
                    if (generation != previewGeneration) { camera.close(); return; }
                    camera.close();
                    if (previewCamera == camera) previewCamera = null;
                    stopCameraPreview();
                    setPreviewStatus("Camera unavailable or already in use. Stop any other camera app and try again.");
                }
            }, previewHandler);
        } catch (SecurityException exception) {
            stopCameraPreview();
            setPreviewStatus("Camera permission was denied.");
        } catch (Exception exception) {
            stopCameraPreview();
            setPreviewStatus("Preview unavailable: " + safeCameraMessage(exception));
        }
    }

    private Size choosePreviewSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1280, 720);
        Size best = sizes[0];
        long bestArea = 0;
        for (Size size : sizes) {
            long area = (long) size.getWidth() * size.getHeight();
            if (size.getWidth() <= 1920 && size.getHeight() <= 1080 && area > bestArea) {
                best = size;
                bestArea = area;
            }
        }
        return best;
    }

    private String safeCameraMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null ? "camera access error" : message;
    }

    private void stopCameraPreview() {
        previewGeneration++;
        previewOpening = false;
        if (previewSession != null) {
            try { previewSession.stopRepeating(); } catch (Exception ignored) { }
            previewSession.close();
            previewSession = null;
        }
        if (previewCamera != null) {
            previewCamera.close();
            previewCamera = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        if (previewThread != null) {
            previewThread.quitSafely();
            previewThread = null;
            previewHandler = null;
        }
        if (previewActive) {
            previewActive = false;
            setPreviewStatus("Preview paused. Android camera access has been released.");
        }
    }

    private void requestRecordingPermissions() {
        String folderId=SessionManager.getActiveRecordingFolder();
        if (folderId==null || SessionManager.getFolderKey(folderId)==null) {
            Toast.makeText(this,"No place to save recording. Create or unlock an encrypted folder first.",Toast.LENGTH_LONG).show();
            startActivity(new Intent(this,FolderVaultActivity.class)); return;
        }
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
        if (requestCode == REQUEST_PREVIEW_PERMISSION) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                refreshCameraChoices();
                previewRequested = true;
                startCameraPreview();
            } else {
                setPreviewStatus("Camera permission was denied. Preview remains off.");
            }
            return;
        }
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
        String folderId=SessionManager.getActiveRecordingFolder();
        if (folderId==null || SessionManager.getFolderKey(folderId)==null) {
            Toast.makeText(this,"No place to save recording. Unlock an encrypted folder first.",Toast.LENGTH_LONG).show(); return;
        }
        if (selectedCameraId == null) {
            Toast.makeText(this, "No usable camera was found on this device.", Toast.LENGTH_LONG).show();
            return;
        }
        stopCameraPreview();
        waitingForRecordingCameraRelease = true;
        setPreviewStatus("Preview released. The camera recording service will now own the camera.");
        Intent intent = new Intent(this, RecordingService.class)
                .setAction(RecordingService.ACTION_START)
                .putExtra(RecordingService.EXTRA_CAMERA_ID, selectedCameraId)
                .putExtra(RecordingService.EXTRA_FOLDER_ID, folderId);
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
                .setMessage("Choose whether to save the recording in the currently unlocked encrypted folder or discard it.")
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
        previewStatus = null;
        cameraPreview = null;
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
        Button button=new Button(this);
        button.setText(label); button.setAllCaps(false); button.setTextSize(16);
        button.setTypeface(null,android.graphics.Typeface.BOLD);
        button.setTextColor(primary ? COLOR_BACKGROUND : COLOR_TEXT);
        button.setMinHeight(dp(54));
        android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();
        shape.setColor(primary ? COLOR_PRIMARY : COLOR_CARD); shape.setCornerRadius(dp(18));
        button.setBackground(shape); button.setElevation(dp(2)); return button;
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
