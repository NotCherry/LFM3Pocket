package com.notcherry.lfm3pocket;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
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
    private float attachedInterfaceScale = InterfaceSize.DEFAULT;

    @Override protected void attachBaseContext(Context newBase) {
        attachedInterfaceScale = InterfaceSize.read(newBase);
        super.attachBaseContext(InterfaceSize.wrap(newBase));
    }

    @Override protected void onResume() {
        super.onResume();
        InterfaceSize.recreateIfChanged(this, attachedInterfaceScale);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status.setText(R.string.camera_permission_needed);
            status.setTextColor(StillwaterUi.DANGER);
            captureButton.setEnabled(false);
            return;
        }
        bindCamera();
    }

    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }

    private void buildUi() {
        int minTouch = InterfaceSize.minTouchPx(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(StillwaterUi.SURFACE);
        StillwaterUi.applySystemAndImeInsets(this, root, 0, 0, 0, 0);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(6), dp(12), dp(6));

        Button cancelButton = new Button(this);
        cancelButton.setId(R.id.camera_cancel);
        cancelButton.setText(R.string.cancel);
        StillwaterUi.outlined(cancelButton);
        cancelButton.setMinimumWidth(minTouch);
        cancelButton.setMinimumHeight(minTouch);
        StillwaterReadable.prepareWrappingControl(cancelButton, minTouch);
        cancelButton.setOnClickListener(view -> finish());

        TextView title = new TextView(this);
        title.setText(R.string.camera);
        title.setTextSize(20);
        title.setTextColor(StillwaterUi.INK);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(StillwaterUi.serif(this));

        top.addView(cancelButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(new View(this), new LinearLayout.LayoutParams(dp(72), 1));
        root.addView(top);

        View topDivider = new View(this);
        topDivider.setBackgroundColor(StillwaterUi.LINE);
        root.addView(topDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));

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
        status.setLetterSpacing(0.06f);
        status.setTypeface(StillwaterUi.sansMedium(this));
        status.setText(R.string.camera_starting);
        GradientDrawable statusChip = new GradientDrawable();
        statusChip.setColor(StillwaterUi.OVERLAY);
        statusChip.setStroke(Math.max(1, dp(1)), StillwaterUi.LINE);
        statusChip.setCornerRadius(dp(2));
        status.setBackground(statusChip);
        status.setPadding(dp(14), dp(8), dp(14), dp(8));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        statusParams.setMargins(dp(16), dp(12), dp(16), dp(16));
        stage.addView(status, statusParams);
        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View bottomDivider = new View(this);
        bottomDivider.setBackgroundColor(StillwaterUi.LINE);
        root.addView(bottomDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));

        LinearLayout shutterRow = new LinearLayout(this);
        shutterRow.setGravity(Gravity.CENTER);
        shutterRow.setPadding(0, dp(16), 0, dp(16));
        captureButton = new Button(this);
        captureButton.setId(R.id.camera_capture);
        captureButton.setText("");
        captureButton.setContentDescription(getString(R.string.camera_capture_cd));
        captureButton.setAllCaps(false);
        captureButton.setBackgroundTintList(null);
        captureButton.setBackground(buildShutterDrawable());
        captureButton.setEnabled(false);
        captureButton.setOnClickListener(view -> captureStill());
        shutterRow.addView(captureButton, new LinearLayout.LayoutParams(dp(72), dp(72)));
        root.addView(shutterRow);
        setContentView(root);
    }

    private Drawable buildShutterDrawable() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},
                dualRing(StillwaterUi.LINE, StillwaterUi.RAISED));
        states.addState(new int[]{android.R.attr.state_pressed},
                dualRing(StillwaterUi.CANDLE, StillwaterUi.CANDLE_PRESSED));
        states.addState(new int[]{},
                dualRing(StillwaterUi.CANDLE, StillwaterUi.INK));
        return states;
    }

    private Drawable dualRing(int outerStrokeColor, int innerFillColor) {
        GradientDrawable outer = new GradientDrawable();
        outer.setShape(GradientDrawable.OVAL);
        outer.setColor(Color.TRANSPARENT);
        outer.setStroke(dp(2), outerStrokeColor);

        GradientDrawable inner = new GradientDrawable();
        inner.setShape(GradientDrawable.OVAL);
        inner.setColor(innerFillColor);

        LayerDrawable layered = new LayerDrawable(new Drawable[]{outer, inner});
        int inset = dp(6);
        layered.setLayerInset(1, inset, inset, inset, inset);
        return layered;
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
                status.setText(R.string.camera_ready);
                status.setTextColor(StillwaterUi.INK);
                captureButton.setEnabled(true);
            } catch (Exception failure) {
                status.setText("Could not start camera: " + failure.getMessage());
                status.setTextColor(StillwaterUi.DANGER);
                captureButton.setEnabled(false);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void captureStill() {
        if (imageCapture == null || captureStarted || isFinishing()) return;
        captureStarted = true;
        captureButton.setEnabled(false);
        status.setText(R.string.camera_capturing);
        status.setTextColor(StillwaterUi.INK);
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
        status.setTextColor(StillwaterUi.DANGER);
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
