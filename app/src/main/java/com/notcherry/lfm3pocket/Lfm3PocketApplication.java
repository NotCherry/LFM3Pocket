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
    }

    private InferenceEngine inferenceEngine;
    private AndroidActionExecutor actionExecutor;
    private ToolCoordinator toolCoordinator;

    public final synchronized ToolCoordinator toolCoordinator() {
        if (toolCoordinator == null) toolCoordinator = new ToolCoordinator(
                createToolHandler(), createToolPermissionCheck(), createToolClock());
        return toolCoordinator;
    }

    protected ToolCoordinator.Handler createToolHandler() {
        return request -> new AndroidActionExecutor.ActionResult(false, "NOT_IMPLEMENTED",
                "This Android tool is not enabled yet");
    }

    protected java.util.function.Predicate<ToolRegistry.Request> createToolPermissionCheck() {
        return request -> request.tool != ToolRegistry.Tool.FLASHLIGHT ||
                checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    protected java.util.function.LongSupplier createToolClock() {
        return android.os.SystemClock::elapsedRealtime;
    }

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
