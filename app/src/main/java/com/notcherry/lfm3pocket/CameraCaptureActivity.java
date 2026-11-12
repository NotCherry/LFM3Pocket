package com.notcherry.lfm3pocket;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
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

/** In-app CameraX preview and full-size still capture. */
public final class CameraCaptureActivity extends ComponentActivity {
    public static final String EXTRA_PHOTO_PATH = "camera_photo_path";
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private PreviewView previewView;
    private TextView status;
    private Button captureButton;
    private ImageCapture imageCapture;
    private ProcessCameraProvider cameraProvider;
    private boolean captureStarted;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status.setText("Camera access is needed to take a photo.");
            captureButton.setEnabled(false);
            return;
        }
        bindCamera();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);

        status = new TextView(this);
        status.setId(R.id.camera_status);
        status.setText("Starting camera preview…");
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        previewView = new PreviewView(this);
        previewView.setId(R.id.camera_preview);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button cancelButton = new Button(this);
        cancelButton.setId(R.id.camera_cancel);
        cancelButton.setText("Cancel");
        cancelButton.setOnClickListener(view -> finish());
        captureButton = new Button(this);
        captureButton.setId(R.id.camera_capture);
        captureButton.setText("Take photo");
        captureButton.setEnabled(false);
        captureButton.setOnClickListener(view -> captureStill());
        actions.addView(cancelButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actions.addView(captureButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(actions);
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
                status.setText("Camera ready. Frame and focus your photo, then capture.");
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
                BitmapFactory.Options dimensions = new BitmapFactory.Options();
                dimensions.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(photo.getAbsolutePath(), dimensions);
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
