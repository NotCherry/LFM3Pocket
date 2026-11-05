package com.notcherry.lfm3pocket;

import android.app.Application;
import java.util.Map;

/**
 * Per-process dependency factory. Instrumentation can install a test subclass
 * of Application to supply fake engines; release builds always use native JNI.
 */
public class Lfm3PocketApplication extends Application {
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
