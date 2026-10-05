package com.colonyhelper.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.nio.ByteBuffer;

public class OverlayService extends Service {
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final int NOTIFICATION_ID = 224;
    private static final String CHANNEL_ID = "cf_helper_capture";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable markerTimeout = this::hideMarker;

    private WindowManager windowManager;
    private WindowManager.LayoutParams overlayParams;
    private LinearLayout overlayRoot;
    private LinearLayout panel;
    private TextView resultText;
    private TextView bubble;
    private TextView targetMarker;
    private Button analyzeButton;
    private CheckBox reserveSlot;
    private boolean analyzing;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int captureWidth;
    private int captureHeight;
    private int densityDpi;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        startCaptureForeground();

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }

        if (resultCode == 0 || resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (overlayRoot == null) createOverlay();
        if (mediaProjection == null) setupCapture(resultCode, resultData);
        return START_NOT_STICKY;
    }

    private void startCaptureForeground() {
        Notification notification;
        if (Build.VERSION.SDK_INT >= 26) {
            notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("CF Helper is active")
                    .setContentText("Tap the floating CF bubble to analyze Colony Flow.")
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setOngoing(true)
                    .build();
        } else {
            notification = new Notification.Builder(this)
                    .setContentTitle("CF Helper is active")
                    .setContentText("Tap the floating CF bubble to analyze Colony Flow.")
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setOngoing(true)
                    .build();
        }

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void setupCapture(int resultCode, Intent resultData) {
        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        captureWidth = dm.widthPixels;
        captureHeight = dm.heightPixels;
        densityDpi = dm.densityDpi;

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = manager.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) {
            showResult("Capture unavailable\nRestart CF Helper and allow screen capture again.");
            return;
        }

        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                handler.post(() -> showResult("Screen capture ended\nRestart CF Helper to analyze again."));
                releaseCaptureObjects();
            }
        }, handler);

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 3);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "CFHelperCapture",
                captureWidth,
                captureHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                handler
        );
    }

    private void createOverlay() {
        overlayRoot = new LinearLayout(this);
        overlayRoot.setOrientation(LinearLayout.VERTICAL);
        overlayRoot.setGravity(Gravity.END);

        bubble = new TextView(this);
        bubble.setText("CF");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(17);
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackground(roundRect(Color.rgb(111, 45, 168), 999));
        LinearLayout.LayoutParams bubbleLp = new LinearLayout.LayoutParams(dp(58), dp(58));
        overlayRoot.addView(bubble, bubbleLp);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.setBackground(roundRect(Color.argb(242, 255, 255, 255), 18));
        panel.setVisibility(View.GONE);
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(dp(330), -2);
        panelLp.topMargin = dp(8);
        overlayRoot.addView(panel, panelLp);

        TextView panelTitle = new TextView(this);
        panelTitle.setText("Colony Flow Helper");
        panelTitle.setTextColor(Color.rgb(78, 30, 110));
        panelTitle.setTextSize(18);
        panelTitle.setPadding(0, 0, 0, dp(8));
        panel.addView(panelTitle, new LinearLayout.LayoutParams(-1, -2));

        analyzeButton = new Button(this);
        analyzeButton.setText("Analyze 3 rows + links");
        analyzeButton.setAllCaps(false);
        analyzeButton.setOnClickListener(v -> captureAndAnalyze());
        panel.addView(analyzeButton, new LinearLayout.LayoutParams(-1, dp(48)));

        reserveSlot = new CheckBox(this);
        reserveSlot.setText("Keep 1 slot free");
        reserveSlot.setTextColor(Color.rgb(45, 45, 45));
        reserveSlot.setChecked(true);
        reserveSlot.setOnCheckedChangeListener((button, checked) -> {
            hideMarker();
            if (!analyzing) showResult("Slot preference changed. Analyze again to update the advice.");
        });
        panel.addView(reserveSlot, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, dp(330));
        scrollLp.topMargin = dp(8);
        resultText = new TextView(this);
        resultText.setText("Open a level and wait until the board is still. Analyze reads 3 rows, linked blocks, and short parking routes. Columns are counted left to right.");
        resultText.setTextColor(Color.rgb(35, 35, 35));
        resultText.setTextSize(14);
        resultText.setPadding(dp(4), dp(4), dp(4), dp(4));
        scroll.addView(resultText, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, scrollLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);

        Button hide = new Button(this);
        hide.setText("Hide panel");
        hide.setAllCaps(false);
        hide.setOnClickListener(v -> panel.setVisibility(View.GONE));
        actions.addView(hide, new LinearLayout.LayoutParams(0, dp(44), 1f));

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setAllCaps(false);
        stop.setOnClickListener(v -> stopSelf());
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        stopLp.leftMargin = dp(8);
        actions.addView(stop, stopLp);
        panel.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        bubble.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private int downX;
            private int downY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downX = overlayParams.x;
                        downY = overlayParams.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5)) moved = true;
                        overlayParams.x = Math.max(0, downX - (int) dx);
                        overlayParams.y = Math.max(0, downY + (int) dy);
                        windowManager.updateViewLayout(overlayRoot, overlayParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.RIGHT;
        overlayParams.x = dp(14);
        overlayParams.y = dp(190);
        windowManager.addView(overlayRoot, overlayParams);
    }

    private void captureAndAnalyze() {
        if (analyzing) return;
        if (imageReader == null) {
            showResult("Capture is not ready. Restart the helper and allow screen capture.");
            return;
        }
        analyzing = true;
        analyzeButton.setEnabled(false);
        reserveSlot.setEnabled(false);
        hideMarker();
        showResult("Reading 3 rows and connections...\nKeep the game still for a moment.");
        overlayRoot.setVisibility(View.INVISIBLE);
        try {
            Image stale = imageReader.acquireLatestImage();
            if (stale != null) stale.close();
        } catch (IllegalStateException error) {
            restoreOverlay(); finishAnalysis();
            showResult("Screen capture ended. Restart the helper.");
            return;
        }
        handler.postDelayed(() -> tryCapture(0), 380);
    }

    private void tryCapture(int attempt) {
        if (imageReader == null) {
            restoreOverlay();
            finishAnalysis();
            return;
        }
        Image image = imageReader.acquireLatestImage();
        if (image == null) {
            if (attempt < 5) {
                handler.postDelayed(() -> tryCapture(attempt + 1), 90);
            } else {
                restoreOverlay();
                finishAnalysis();
                showResult("No screen frame received. Tap Analyze again.");
            }
            return;
        }

        Bitmap screenshot = null;
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * captureWidth;
            int paddedWidth = captureWidth + rowPadding / pixelStride;

            Bitmap padded = Bitmap.createBitmap(paddedWidth, captureHeight, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            screenshot = Bitmap.createBitmap(padded, 0, 0, captureWidth, captureHeight);
            if (padded != screenshot) padded.recycle();
        } catch (Throwable t) {
            finishAnalysis();
            showResult("Could not decode the captured frame. Tap Analyze again.");
        } finally {
            image.close();
            restoreOverlay();
        }

        if (screenshot == null) return;
        Bitmap finalScreenshot = screenshot;
        boolean reserveOne = reserveSlot.isChecked();
        BoardAnalyzer.analyze(screenshot, reserveOne, result -> handler.post(() -> {
            try {
                finishAnalysis();
                if (overlayRoot == null || panel == null || imageReader == null) return;
                showResult(result.headline + "\n\n" + result.detail);
                panel.setVisibility(View.VISIBLE);
                if (result.column >= 0) showMarker(result.column, result.temporaryPark);
            } finally {
                finalScreenshot.recycle();
            }
        }));
    }

    private void finishAnalysis() {
        analyzing = false;
        if (analyzeButton != null) analyzeButton.setEnabled(true);
        if (reserveSlot != null) reserveSlot.setEnabled(true);
    }

    private void showMarker(int column, boolean park) {
        hideMarker();
        float[] xs = {.269f, .423f, .577f, .730f};
        targetMarker = new TextView(this);
        targetMarker.setText("C" + (column + 1) + " \u2193");
        targetMarker.setGravity(Gravity.CENTER);
        targetMarker.setTextSize(12);
        targetMarker.setTextColor(Color.WHITE);
        targetMarker.setBackground(roundRect(park ? Color.rgb(167, 99, 12) : Color.rgb(87, 38, 131), 10));
        int markerWidth = Math.min(dp(62), Math.round(captureWidth * .125f));
        int markerHeight = Math.min(dp(28), Math.round(captureHeight * .020f));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(markerWidth, markerHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.alpha = .75f;
        params.x = Math.round(captureWidth * xs[column]) - markerWidth / 2;
        params.y = Math.round(captureHeight * .677f) - markerHeight;
        try { windowManager.addView(targetMarker, params); }
        catch (RuntimeException error) { targetMarker = null; }
        handler.postDelayed(markerTimeout, 8000);
    }

    private void hideMarker() {
        handler.removeCallbacks(markerTimeout);
        if (targetMarker != null) {
            try { windowManager.removeView(targetMarker); } catch (RuntimeException ignored) { }
            targetMarker = null;
        }
    }

    private void restoreOverlay() {
        if (overlayRoot != null) overlayRoot.setVisibility(View.VISIBLE);
    }

    private void showResult(String text) {
        if (resultText != null) resultText.setText(text);
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radiusDp));
        return bg;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Colony Flow Helper",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Required while CF Helper is reading the screen.");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void releaseCaptureObjects() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        hideMarker();
        if (overlayRoot != null && windowManager != null) {
            try {
                windowManager.removeView(overlayRoot);
            } catch (Throwable ignored) {
            }
            overlayRoot = null;
        }
        releaseCaptureObjects();
        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (Throwable ignored) {
            }
            mediaProjection = null;
        }
        stopForeground(true);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
