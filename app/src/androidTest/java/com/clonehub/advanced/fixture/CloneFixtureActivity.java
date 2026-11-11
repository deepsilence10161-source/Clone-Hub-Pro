package com.clonehub.advanced.fixture;

import android.app.Activity;
import android.app.Application;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Dependency-free third-party fixture. Java is intentional: this APK must launch
 * alone and inside the virtual runtime without relying on the host's Kotlin stdlib.
 */
public final class CloneFixtureActivity extends Activity {
    private TextView captureStatus;
    private boolean captureStarted;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView status = new TextView(this);
        status.setText("CLONE_FIXTURE_RUNNING");
        status.setTextSize(24f);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);

        String processName = Build.VERSION.SDK_INT >= 28
                ? Application.getProcessName() : "unavailable";
        TextView details = new TextView(this);
        details.setText("package=" + getPackageName()
                + "\nprocess=" + processName
                + "\npid=" + android.os.Process.myPid()
                + " uid=" + android.os.Process.myUid()
                + "\nAndroid=" + Build.VERSION.RELEASE + " SDK=" + Build.VERSION.SDK_INT
                + "\ntime=" + timestamp());
        details.setTextSize(16f);
        details.setTextColor(Color.WHITE);
        details.setGravity(Gravity.CENTER);
        details.setPadding(24, 28, 24, 12);

        captureStatus = new TextView(this);
        captureStatus.setText("Automatic screenshot: waiting for visible window…");
        captureStatus.setTextSize(15f);
        captureStatus.setTextColor(Color.rgb(220, 255, 225));
        captureStatus.setGravity(Gravity.CENTER);
        captureStatus.setPadding(24, 20, 24, 12);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setBackgroundColor(Color.rgb(20, 80, 55));
        content.addView(status);
        content.addView(details);
        content.addView(captureStatus);
        setContentView(content);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !captureStarted) {
            captureStarted = true;
            getWindow().getDecorView().postDelayed(this::captureOwnWindow, 1000L);
        }
    }

    private void captureOwnWindow() {
        View view = getWindow().getDecorView();
        if (view.getWidth() <= 0 || view.getHeight() <= 0) {
            view.postDelayed(this::captureOwnWindow, 500L);
            return;
        }
        Bitmap bitmap = Bitmap.createBitmap(
                view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        if (Build.VERSION.SDK_INT >= 26) {
            PixelCopy.request(getWindow(), bitmap, result -> {
                if (result == PixelCopy.SUCCESS) {
                    saveEvidence(bitmap);
                } else {
                    saveEvidence(drawFallback(view));
                }
            }, new Handler(Looper.getMainLooper()));
        } else {
            saveEvidence(drawFallback(view));
        }
    }

    private Bitmap drawFallback(View view) {
        Bitmap bitmap = Bitmap.createBitmap(
                view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    private void saveEvidence(Bitmap bitmap) {
        captureStatus.setText("Automatic screenshot: saving to Pictures/CloneHubEvidence…");
        new Thread(() -> {
            String location;
            try {
                location = saveToGallery(bitmap);
            } catch (Throwable galleryError) {
                try {
                    location = saveToAppPictures(bitmap);
                } catch (Throwable fallbackError) {
                    String message = "SCREENSHOT SAVE FAILED\n"
                            + fallbackError.getClass().getSimpleName() + ": "
                            + fallbackError.getMessage();
                    runOnUiThread(() -> captureStatus.setText(message));
                    return;
                }
            }
            String message = "SCREENSHOT SAVED\n" + location;
            runOnUiThread(() -> {
                captureStatus.setText(message);
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            });
        }, "CloneFixtureScreenshot").start();
    }

    private String saveToGallery(Bitmap bitmap) throws Exception {
        if (Build.VERSION.SDK_INT < 29) {
            throw new IllegalStateException("MediaStore relative path needs API 29");
        }
        String name = "clone-fixture-" + fileTimestamp() + ".png";
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CloneHubEvidence");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = getContentResolver().insert(collection, values);
        if (uri == null) throw new IllegalStateException("MediaStore insert returned null");
        try {
            try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) throw new IllegalStateException("Cannot open screenshot output");
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IllegalStateException("PNG compression failed");
                }
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return "Gallery → Pictures/CloneHubEvidence/" + name;
        } catch (Throwable error) {
            getContentResolver().delete(uri, null, null);
            throw error;
        }
    }

    private String saveToAppPictures(Bitmap bitmap) throws Exception {
        File directory = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "CloneHubEvidence");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create evidence directory");
        }
        File file = new File(directory, "clone-fixture-" + fileTimestamp() + ".png");
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("PNG compression failed");
            }
        }
        return file.getAbsolutePath();
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(new Date());
    }

    private String fileTimestamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
    }
}
