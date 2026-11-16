package com.notcherry.lfm3pocket;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** In-app CameraX preview and full-size still capture. Stillwater chrome only. */
public final class CameraCaptureActivity extends ComponentActivity {
    public static final String EXTRA_PHOTO_PATH = "camera_photo_path";
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private PreviewView previewView;
    private TextView status;
    private Button captureButton;
    private ImageCapture imageCapture;
    private ProcessCameraProvider cameraProvider;
    private boolean captureStarted;

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(InterfaceSize.wrap(newBase));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(StillwaterUi.SURFACE);
        getWindow().setNavigationBarColor(StillwaterUi.SURFACE);
        buildUi();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status.setText("Camera access is needed to take a photo.");
            captureButton.setEnabled(false);
            return;
        }
        bindCamera();
    }

    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(StillwaterUi.SURFACE);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(8), dp(8), dp(4));
        Button cancelButton = new Button(this);
        cancelButton.setId(R.id.camera_cancel);
        cancelButton.setText("Cancel");
        StillwaterUi.quiet(cancelButton);
        cancelButton.setOnClickListener(view -> finish());
        TextView title = new TextView(this);
        title.setText("Camera");
        title.setTextSize(18);
        title.setTextColor(StillwaterUi.INK);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(StillwaterUi.serif(this));
        top.addView(cancelButton, new LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT));
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(new android.view.View(this), new LinearLayout.LayoutParams(dp(88), 1));
        root.addView(top);

        FrameLayout stage = new FrameLayout(this);
        previewView = new PreviewView(this);
        previewView.setId(R.id.camera_preview);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setBackgroundColor(StillwaterUi.RAISED);
        stage.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        status = new TextView(this);
        status.setId(R.id.camera_status);
        status.setTextColor(StillwaterUi.INK);
        status.setTextSize(12);
        status.setLetterSpacing(0.08f);
        status.setTypeface(StillwaterUi.sansMedium(this));
        status.setText("Starting camera preview…");
        status.setPadding(dp(16), dp(12), dp(16), dp(12));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        stage.addView(status, statusParams);
        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout shutterRow = new LinearLayout(this);
        shutterRow.setGravity(Gravity.CENTER);
        shutterRow.setPadding(0, dp(16), 0, dp(20));
        captureButton = new Button(this);
        captureButton.setId(R.id.camera_capture);
        captureButton.setText("");
        captureButton.setContentDescription("Capture");
        captureButton.setAllCaps(false);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(StillwaterUi.INK);
        ring.setStroke(dp(2), StillwaterUi.INK);
        captureButton.setBackgroundTintList(null);
        captureButton.setBackground(ring);
        captureButton.setEnabled(false);
        captureButton.setOnClickListener(view -> captureStill());
        shutterRow.addView(captureButton, new LinearLayout.LayoutParams(dp(68), dp(68)));
        root.addView(shutterRow);
        setContentView(root);
    }

    private void bindCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageCapture);
                status.setText("Camera ready");
                captureButton.setEnabled(true);
            } catch (Exception failure) {
                status.setText("Could not start camera: " + failure.getMessage());
                captureButton.setEnabled(false);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void captureStill() {
        if (imageCapture == null || captureStarted || isFinishing()) return;
        captureStarted = true;
        captureButton.setEnabled(false);
        status.setText("Capturing full-size photo…");
        File photo = new File(getCacheDir(), "camera-" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions output = new ImageCapture.OutputFileOptions.Builder(photo).build();
        imageCapture.takePicture(output, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(ImageCapture.OutputFileResults result) {
                android.graphics.BitmapFactory.Options dimensions = new android.graphics.BitmapFactory.Options();
                dimensions.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(photo.getAbsolutePath(), dimensions);
                if (photo.length() < 1024 || dimensions.outWidth < 320 || dimensions.outHeight < 240) {
                    photo.delete();
                    runOnUiThread(() -> showFailure("Camera returned an invalid or thumbnail-sized image."));
                    return;
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        photo.delete();
                        return;
                    }
                    setResult(RESULT_OK, new Intent().putExtra(EXTRA_PHOTO_PATH, photo.getAbsolutePath()));
                    finish();
                });
            }

            @Override public void onError(ImageCaptureException failure) {
                photo.delete();
                runOnUiThread(() -> showFailure("Photo capture failed: " + failure.getMessage()));
            }
        });
    }

    private void showFailure(String message) {
        captureStarted = false;
        status.setText(message);
        captureButton.setEnabled(imageCapture != null);
    }

    @Override protected void onStop() {
        if (cameraProvider != null) cameraProvider.unbindAll();
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (cameraProvider != null) cameraProvider.unbindAll();
        cameraExecutor.shutdown();
        super.onDestroy();
    }
}
