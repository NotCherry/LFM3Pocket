# Keep source file and line numbers for readable on-device LocalCrashLog traces.
-keepattributes SourceFile,LineNumberTable

# --- JNI native bridges and callbacks invoked from C++ / Rust ---
-keep class com.notcherry.lfm3pocket.NativeBridge {
    native <methods>;
}
-keep interface com.notcherry.lfm3pocket.NativeBridge$TokenSink {
    void onToken(java.lang.String);
    void onProgress(java.lang.String, int, int);
}
-keep class * implements com.notcherry.lfm3pocket.NativeBridge$TokenSink {
    void onToken(java.lang.String);
    void onProgress(java.lang.String, int, int);
}
-keep interface com.notcherry.lfm3pocket.NativeBridge$AudioSink {
    void onAudioFormat(int, int, int);
    void onAudioPcm(byte[]);
    void onAudioComplete();
}
-keep class * implements com.notcherry.lfm3pocket.NativeBridge$AudioSink {
    void onAudioFormat(int, int, int);
    void onAudioPcm(byte[]);
    void onAudioComplete();
}
-keep class com.notcherry.lfm3pocket.CactusWhistleBridge {
    native <methods>;
}
-keep class com.notcherry.lfm3pocket.TinyVocaPhonemizerBridge {
    native <methods>;
}

# --- Room database, generated implementation, DAOs, and entities ---
-keep class com.notcherry.lfm3pocket.HistoryDatabase { *; }
-keep class com.notcherry.lfm3pocket.HistoryDatabase_Impl { *; }
-keep class com.notcherry.lfm3pocket.HistoryDao { *; }
-keep class com.notcherry.lfm3pocket.HistoryConversation { *; }
-keep class com.notcherry.lfm3pocket.HistoryMessage { *; }
-keep class com.notcherry.lfm3pocket.HistoryToolOutcome { *; }

# --- WorkManager workers instantiated via reflection ---
-keep class com.notcherry.lfm3pocket.ReminderWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.notcherry.lfm3pocket.ModelDownloadWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# --- ONNX Runtime JNI bindings (Tiny Voca TTS) ---
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
