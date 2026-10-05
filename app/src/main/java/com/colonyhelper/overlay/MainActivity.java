package com.colonyhelper.overlay;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_OVERLAY = 1002;
    private static final int REQ_NOTIFY = 1003;

    private TextView status;
    private boolean pendingStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestNotificationPermissionIfNeeded();
    }

    private void buildUi() {
        int pad = dp(22);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(248, 244, 255));

        TextView title = new TextView(this);
        title.setText("CF Helper");
        title.setTextSize(30);
        title.setTextColor(Color.rgb(82, 33, 117));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(20), 0, dp(8));
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("On-screen helper for Colony Flow hard levels. It reads the current board, checks exposed colors, and recommends one move at a time. It does not modify or auto-tap the game.");
        subtitle.setTextSize(16);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(dp(8), 0, dp(8), dp(24));
        root.addView(subtitle, matchWrap());

        Button start = new Button(this);
        start.setText("START FLOATING HELPER");
        start.setAllCaps(false);
        start.setTextSize(17);
        start.setOnClickListener(v -> beginSetup());
        root.addView(start, new LinearLayout.LayoutParams(-1, dp(56)));

        Button stop = new Button(this);
        stop.setText("Stop helper");
        stop.setAllCaps(false);
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(-1, dp(50));
        stopParams.topMargin = dp(12);
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, OverlayService.class));
            status.setText("Helper stopped.");
        });
        root.addView(stop, stopParams);

        status = new TextView(this);
        status.setText("Ready. Start the helper, grant overlay + screen capture once, then open Colony Flow and tap Analyze on the floating bubble.");
        status.setTextSize(15);
        status.setTextColor(Color.rgb(50, 50, 50));
        status.setPadding(0, dp(22), 0, 0);
        root.addView(status, matchWrap());

        TextView note = new TextView(this);
        note.setText("V0.1 is tuned for the portrait layout shown in your Level 224 screenshot. It re-analyzes after every move, so it does not need to predict the whole level in advance.");
        note.setTextSize(13);
        note.setTextColor(Color.GRAY);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note, matchWrap());

        setContentView(root);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private void beginSetup() {
        pendingStart = true;
        if (!Settings.canDrawOverlays(this)) {
            status.setText("Grant 'Display over other apps', then return here.");
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            return;
        }
        pendingStart = false;
        requestScreenCapture();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingStart && Settings.canDrawOverlays(this)) {
            pendingStart = false;
            requestScreenCapture();
        }
    }

    private void requestScreenCapture() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        status.setText("Allow screen capture. Android shows this permission for your privacy.");
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent service = new Intent(this, OverlayService.class);
                service.putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode);
                service.putExtra(OverlayService.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(service);
                } else {
                    startService(service);
                }
                status.setText("Helper running. Open Colony Flow and tap the CF bubble, then Analyze.");
            } else {
                status.setText("Screen capture was not allowed, so the helper cannot read the board.");
            }
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
