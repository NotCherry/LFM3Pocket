package com.notcherry.lfm3pocket;

import android.app.Application;
import java.util.Map;

/**
 * Per-process dependency factory. Instrumentation can install a test subclass
 * of Application to supply fake engines; release builds always use native JNI.
 */
public class Lfm3PocketApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        // No activity/import worker exists yet: discard remnants of a killed import.
        java.io.File[] partials = new java.io.File(getFilesDir(), "models")
                .listFiles((directory, name) -> name.endsWith(".partial"));
        if (partials != null) for (java.io.File partial : partials) partial.delete();
        java.io.File[] recordings = new java.io.File(getCacheDir(), "recordings")
                .listFiles((directory, name) -> name.endsWith(".partial"));
        if (recordings != null) for (java.io.File partial : recordings) partial.delete();
    }

    private InferenceEngine inferenceEngine;
    private AndroidActionExecutor actionExecutor;

    public final synchronized InferenceEngine inferenceEngine() {
        if (inferenceEngine == null) inferenceEngine = createInferenceEngine();
        return inferenceEngine;
    }

    public final synchronized AndroidActionExecutor actionExecutor() {
        if (actionExecutor == null) actionExecutor = createActionExecutor();
        return actionExecutor;
    }

    public final InferenceEngine newInferenceSession() {
        return createInferenceEngine();
    }

    public final RecordingController.Source newRecordingSource() throws Exception {
        return createRecordingSource();
    }

    public boolean hasMicrophonePermission() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    protected RecordingController.Source createRecordingSource() throws Exception {
        return new AndroidMicrophone();
    }

    protected InferenceEngine createInferenceEngine() {
        return new NativeInferenceEngine();
    }

    protected AndroidActionExecutor createActionExecutor() {
        // Default deny until the validated, allowlisted tool registry is implemented.
        return (approvedToolName, validatedArguments) ->
                new AndroidActionExecutor.ActionResult(false, "NOT_IMPLEMENTED",
                        "No Android actions are enabled in this build");
    }
}
