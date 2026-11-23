package com.hiddencam;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.media.MediaRecorder;

public final class RecordingService extends Service {
    static final String ACTION_START = "com.hiddencam.action.START";
    static final String ACTION_SAVE_STOP = "com.hiddencam.action.SAVE_STOP";
    static final String ACTION_DISCARD_STOP = "com.hiddencam.action.DISCARD_STOP";
    static final String EXTRA_CAMERA_ID = "camera_id";

    private static final String CHANNEL_ID = "hiddencam_recording";
    private static final int NOTIFICATION_ID = 4102;
    private static final int REQUEST_SAVE = 4103;
    private static final int REQUEST_DISCARD = 4104;

    static volatile boolean isRecording;
    static volatile boolean isActive;
    static volatile String lastResult = "";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final Object cameraLock = new Object();

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaRecorder mediaRecorder;
    private volatile File temporaryRecording;
    private boolean recorderStarted;
    private volatile boolean stopRequested;
    private volatile boolean finalizing;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        isActive = true;
        isRecording = false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            if (isRecording || finalizing) {
                return START_NOT_STICKY;
            }
            try {
                startCameraForeground();
                beginRecording(intent.getStringExtra(EXTRA_CAMERA_ID));
            } catch (SecurityException exception) {
                abortStart("Camera permission or foreground-service permission was not granted");
            } catch (Exception exception) {
                abortStart("Unable to start the camera");
            }
        } else if (ACTION_SAVE_STOP.equals(action)) {
            finalizeRecording(true);
        } else if (ACTION_DISCARD_STOP.equals(action)) {
            finalizeRecording(false);
        }
        return START_NOT_STICKY;
    }

    private void startCameraForeground() {
        Notification notification = buildNotification("Starting camera session");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void beginRecording(String cameraId) throws Exception {
        if (cameraId == null || cameraId.isEmpty()) {
            throw new IllegalArgumentException("No camera selected");
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Camera permission was not granted");
        }

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("Camera service is unavailable");
        }

        cameraThread = new HandlerThread("HiddenCamCameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        temporaryRecording = new File(getCacheDir(), "recording-" + System.currentTimeMillis() + ".mp4");
        Size recordingSize = chooseRecordingSize(manager, cameraId);

        mediaRecorder = new MediaRecorder();
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setVideoEncodingBitRate(4_000_000);
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoSize(recordingSize.getWidth(), recordingSize.getHeight());
        mediaRecorder.setOutputFile(temporaryRecording.getAbsolutePath());
        mediaRecorder.prepare();

        mediaRecorder.setOnErrorListener((recorder, what, extra) -> mainHandler.post(() -> finalizeRecording(false)));

        manager.openCamera(cameraId, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice camera) {
                synchronized (cameraLock) {
                    if (stopRequested || finalizing) {
                        camera.close();
                        return;
                    }
                    cameraDevice = camera;
                }
                createRecordingSession();
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
                camera.close();
                synchronized (cameraLock) {
                    if (cameraDevice == camera) {
                        cameraDevice = null;
                    }
                }
                if (!finalizing) {
                    abortStart("The camera disconnected");
                }
            }

            @Override
            public void onError(CameraDevice camera, int error) {
                camera.close();
                synchronized (cameraLock) {
                    if (cameraDevice == camera) {
                        cameraDevice = null;
                    }
                }
                if (!finalizing) {
                    abortStart("The camera could not be opened");
                }
            }
        }, cameraHandler);
    }

    private Size chooseRecordingSize(CameraManager manager, String cameraId) throws CameraAccessException {
        CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return new Size(1280, 720);
        }
        Size[] sizes = map.getOutputSizes(MediaRecorder.class);
        if (sizes == null || sizes.length == 0) {
            return new Size(1280, 720);
        }

        Size selected = sizes[0];
        long selectedArea = 0;
        for (Size size : sizes) {
            if (size.getWidth() <= 1920 && size.getHeight() <= 1080) {
                long area = (long) size.getWidth() * size.getHeight();
                if (area > selectedArea) {
                    selected = size;
                    selectedArea = area;
                }
            }
        }
        return selected;
    }

    private void createRecordingSession() {
        try {
            synchronized (cameraLock) {
                if (stopRequested || finalizing || cameraDevice == null || mediaRecorder == null) {
                    return;
                }
                Surface recorderSurface = mediaRecorder.getSurface();
                CaptureRequest.Builder request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                request.addTarget(recorderSurface);
                request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

                cameraDevice.createCaptureSession(
                        Collections.singletonList(recorderSurface),
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                boolean failed = false;
                                synchronized (cameraLock) {
                                    if (stopRequested || finalizing || cameraDevice == null || mediaRecorder == null) {
                                        session.close();
                                        return;
                                    }
                                    captureSession = session;
                                    try {
                                        session.setRepeatingRequest(request.build(), null, cameraHandler);
                                        mediaRecorder.start();
                                        recorderStarted = true;
                                        isRecording = true;
                                        lastResult = "Recording in progress";
                                        updateNotification("Recording is active. Android camera indicator is enabled.");
                                    } catch (Exception exception) {
                                        failed = true;
                                    }
                                }
                                if (failed) {
                                    abortStart("Unable to begin video capture");
                                }
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                                session.close();
                                abortStart("The camera could not configure a recording session");
                            }
                        },
                        cameraHandler
                );
            }
        } catch (Exception exception) {
            abortStart("Unable to configure the camera session");
        }
    }

    private void abortStart(String message) {
        if (finalizing) {
            return;
        }
        finalizing = true;
        stopRequested = true;
        isRecording = false;
        closeCameraResources(false);
        if (temporaryRecording != null) {
            temporaryRecording.delete();
        }
        lastResult = message;
        isActive = false;
        stopForeground(true);
        stopSelf();
    }

    private void finalizeRecording(boolean save) {
        if (finalizing) {
            return;
        }
        finalizing = true;
        stopRequested = true;
        isRecording = false;
        updateNotification(save ? "Securing recording in the private vault" : "Discarding recording");

        boolean validRecording = closeCameraResources(true);
        File source = temporaryRecording;
        temporaryRecording = null;

        fileExecutor.execute(() -> {
            String result;
            try {
                if (save && validRecording && source != null && source.isFile() && source.length() > 0) {
                    VaultManager.encryptRecording(this, source);
                    result = "Recording saved in the encrypted private vault";
                } else if (save) {
                    result = "No complete recording was available to save";
                } else {
                    result = "Recording discarded";
                }
            } catch (Exception exception) {
                result = "Recording could not be encrypted and was discarded";
            } finally {
                if (source != null) {
                    source.delete();
                }
                VaultManager.clearCache(this);
            }

            lastResult = result;
            isActive = false;
            mainHandler.post(() -> {
                stopForeground(true);
                stopSelf();
            });
        });
    }

    private boolean closeCameraResources(boolean stopRecorder) {
        synchronized (cameraLock) {
            boolean valid = recorderStarted;
            if (captureSession != null) {
                try {
                    captureSession.stopRepeating();
                    captureSession.abortCaptures();
                } catch (Exception ignored) {
                }
            }

            if (mediaRecorder != null && stopRecorder && recorderStarted) {
                try {
                    mediaRecorder.stop();
                } catch (RuntimeException exception) {
                    valid = false;
                }
            }

            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }

            if (mediaRecorder != null) {
                try {
                    mediaRecorder.reset();
                } catch (Exception ignored) {
                }
                mediaRecorder.release();
                mediaRecorder = null;
            }

            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            recorderStarted = false;

            if (cameraThread != null) {
                cameraThread.quitSafely();
                cameraThread = null;
                cameraHandler = null;
            }
            return valid;
        }
    }

    private Notification buildNotification(String message) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                4105,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent saveIntent = servicePendingIntent(ACTION_SAVE_STOP, REQUEST_SAVE);
        PendingIntent discardIntent = servicePendingIntent(ACTION_DISCARD_STOP, REQUEST_DISCARD);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_camera)
                .setContentTitle("HiddenCam camera recording")
                .setContentText(message)
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Secure & stop", saveIntent).build())
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Discard", discardIntent).build());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private PendingIntent servicePendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, RecordingService.class).setAction(action);
        return PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private void updateNotification(String message) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(message));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Camera recording",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows when HiddenCam is actively using the camera");
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        if (!finalizing) {
            isRecording = false;
            closeCameraResources(false);
            if (temporaryRecording != null) {
                temporaryRecording.delete();
            }
        }
        isActive = false;
        fileExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
