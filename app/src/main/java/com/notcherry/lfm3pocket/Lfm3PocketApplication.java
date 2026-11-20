package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.SystemClock;

import java.io.File;
import java.util.List;

/**
 * Per-process dependency factory. Instrumentation can install a test subclass
 * of Application to supply fake engines; release builds always use native JNI.
 */
public class Lfm3PocketApplication extends Application {
    private final LocalDiagnostics diagnostics = new LocalDiagnostics(SystemClock::elapsedRealtime);
    private ModelRuntime modelRuntime;
    private ExternalModelStore externalModels;
    private int startedActivities;

    @Override public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        CrashLog.captureExitInfo(this);
        HanamiNotifications.ensureChannels(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) { startedActivities++; }
            @Override public void onActivityStopped(Activity activity) {
                startedActivities = Math.max(0, startedActivities - 1);
                if (startedActivities == 0 && !activity.isChangingConfigurations() && modelRuntime != null)
                    modelRuntime.onAppBackgrounded();
            }
            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
        // No activity/import worker exists yet: discard remnants of a killed import.
        java.io.File[] partials = new java.io.File(getFilesDir(), "models")
                .listFiles((directory, name) -> name.endsWith(".partial"));
        if (partials != null) for (java.io.File partial : partials) partial.delete();
        HistoryMediaCleanup.deleteTemporaryAttachments(this);
        // ModelRuntime schedules Room history recovery on its serialized worker
        // before accepting turns; do not race it from an independent startup executor.
    }

    /** Activities currently started; used to decide when a reply-ready notification is needed. */
    public final int startedActivityCount() {
        return startedActivities;
    }

    public final LocalDiagnostics diagnostics() {
        return diagnostics;
    }

    public final synchronized ExternalModelStore externalModels() {
        if (externalModels == null) externalModels = new ExternalModelStore(this);
        return externalModels;
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Runtime runtime = Runtime.getRuntime();
        long usedMb = Math.max(0L, (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L));
        diagnostics.record(LocalDiagnostics.Kind.MEMORY, 0L, "trim=" + level + ",usedMb=" + usedMb);
        if (modelRuntime != null) modelRuntime.onTrimMemory(level);
        try {
            diagnostics.writeTo(new File(getFilesDir(), "diagnostics.ndjson"));
        } catch (Exception ignored) {
            // Diagnostics must never break the process under memory pressure.
        }
    }

    private InferenceEngine inferenceEngine;
    private AndroidActionExecutor actionExecutor;
    private ToolCoordinator toolCoordinator;
    private MessageDraftStore messageDraftStore;
    public final synchronized MessageDraftStore messageDraftStore() {
        if (messageDraftStore == null) messageDraftStore = new MessageDraftStore();
        return messageDraftStore;
    }
    public final synchronized ToolCoordinator toolCoordinator() {
        if (toolCoordinator == null) toolCoordinator = new ToolCoordinator(
                createToolHandler(), createToolPermissionPolicy(), createToolClock());
        return toolCoordinator;
    }

    protected ToolCoordinator.Handler createToolHandler() {
        return request -> {
            if (!(request instanceof ToolRegistry.FlashlightRequest) &&
                    !(request instanceof ToolRegistry.VolumeRequest) &&
                    !(request instanceof ToolRegistry.TimerRequest) &&
                    !(request instanceof ToolRegistry.AlarmRequest) &&
                    !(request instanceof ToolRegistry.ReminderRequest) &&
                    !(request instanceof ToolRegistry.ShowAlarmsRequest) &&
                    !(request instanceof ToolRegistry.ApplicationRequest) &&
                    !(request instanceof ToolRegistry.SettingsRequest) &&
                    !(request instanceof ToolRegistry.TextRequest) &&
                    !(request instanceof ToolRegistry.CurrentTimeRequest) &&
                    !(request instanceof ToolRegistry.CalculatorRequest) &&
                    !(request instanceof ToolRegistry.UnitConvertRequest) &&
                    !(request instanceof ToolRegistry.WeatherRequest) &&
                    !(request instanceof ToolRegistry.ListNotificationsRequest) &&
                    !(request instanceof ToolRegistry.DraftMessageRequest) &&
                    !(request instanceof ToolRegistry.SendMessageRequest) &&
                    !(request instanceof ToolRegistry.ListEventsRequest) &&
                    !(request instanceof ToolRegistry.CreateEventRequest) &&
                    !(request instanceof ToolRegistry.UpdateEventRequest) &&
                    !(request instanceof ToolRegistry.DeleteEventRequest)) {
                return new AndroidActionExecutor.ActionResult(false, "NOT_IMPLEMENTED",
                        "This Android tool is not enabled yet");
            }
            java.util.Map<String, String> arguments = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, Object> entry : request.arguments.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof List) {
                    arguments.put(entry.getKey(), joinDays((List<?>) value));
                } else {
                    arguments.put(entry.getKey(), String.valueOf(value));
                }
            }
            return actionExecutor().execute(request.tool.wireName, arguments);
        };
    }

    private static String joinDays(List<?> values) {
        StringBuilder out = new StringBuilder();
        for (Object value : values) {
            if (out.length() > 0) out.append(',');
            out.append(value);
        }
        return out.toString();
    }

    protected java.util.function.Predicate<ToolRegistry.Request> createToolPermissionCheck() {
        return request -> createToolPermissionPolicy().allowed(request);
    }

    protected ToolCoordinator.PermissionPolicy createToolPermissionPolicy() {
        return new ToolCoordinator.PermissionPolicy() {
            @Override public boolean allowed(ToolRegistry.Request request) {
                if (request == null) return false;
                if (request.tool == ToolRegistry.Tool.FLASHLIGHT) {
                    return checkSelfPermission(android.Manifest.permission.CAMERA)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED;
                }
                if (request.tool == ToolRegistry.Tool.WEATHER) {
                    return new RuntimePreferences(Lfm3PocketApplication.this).networkToolsEnabled();
                }
                if (request.tool == ToolRegistry.Tool.LIST_NOTIFICATIONS) {
                    // Policy is enforced in the executor so callers get specific, testable error states.
                    return true;
                }
                if (request.tool == ToolRegistry.Tool.REMINDER) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                                == android.content.pm.PackageManager.PERMISSION_GRANTED;
                    }
                    return true;
                }
                if (request.tool == ToolRegistry.Tool.LIST_EVENTS) {
                    return checkSelfPermission(android.Manifest.permission.READ_CALENDAR)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED;
                }
                if (request.tool == ToolRegistry.Tool.CREATE_EVENT
                        || request.tool == ToolRegistry.Tool.UPDATE_EVENT
                        || request.tool == ToolRegistry.Tool.DELETE_EVENT) {
                    if (checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
                    String picked = new RuntimePreferences(Lfm3PocketApplication.this).calendarWriteId();
                    if (picked == null || picked.trim().isEmpty()) return false;
                    if (request instanceof ToolRegistry.CreateEventRequest) {
                        String target = ((ToolRegistry.CreateEventRequest) request).calendarId;
                        return picked.trim().equals(target == null ? "" : target.trim());
                    }
                    return true;
                }
                return true;
            }

            @Override public String denialMessage(ToolRegistry.Request request) {
                if (request == null) return "Required permission or policy is unavailable.";
                if (request.tool == ToolRegistry.Tool.REMINDER) {
                    if (android.os.Build.VERSION.SDK_INT >= 33
                            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        return "Grant notification permission in Android app settings to schedule reminders.";
                    }
                }
                if (request.tool == ToolRegistry.Tool.LIST_EVENTS) {
                    if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        return "Grant Calendar permission in Android app settings to read events.";
                    }
                }
                if (request.tool == ToolRegistry.Tool.CREATE_EVENT
                        || request.tool == ToolRegistry.Tool.UPDATE_EVENT
                        || request.tool == ToolRegistry.Tool.DELETE_EVENT) {
                    if (checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        return "Grant Calendar permission in Android app settings to change events.";
                    }
                    String picked = new RuntimePreferences(Lfm3PocketApplication.this).calendarWriteId();
                    if (picked == null || picked.trim().isEmpty()) {
                        return "Pick a calendar in Settings before changing events.";
                    }
                    if (request instanceof ToolRegistry.CreateEventRequest) {
                        String target = ((ToolRegistry.CreateEventRequest) request).calendarId;
                        if (!picked.trim().equals(target == null ? "" : target.trim())) {
                            return "Pick the same calendar in Settings before creating events.";
                        }
                    }
                }
                return null;
            }
        };
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

    public final synchronized ModelRuntime modelRuntime() {
        if (modelRuntime == null) modelRuntime = new ModelRuntime(this);
        return modelRuntime;
    }

    /** Already-built runtime, or null when chat has not created one. Used by instrumentation resets. */
    public final ModelRuntime existingRuntime() {
        return modelRuntime;
    }

    public final RecordingController.Source newRecordingSource() throws Exception {
        return createRecordingSource();
    }

    public final PlaybackController.Factory newPlaybackTrackFactory() {
        return createPlaybackTrackFactory();
    }

    public boolean hasMicrophonePermission() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    protected RecordingController.Source createRecordingSource() throws Exception {
        return new AndroidMicrophone();
    }

    protected PlaybackController.Factory createPlaybackTrackFactory() {
        return AndroidSpeechTrack::new;
    }

    protected InferenceEngine createInferenceEngine() {
        return new NativeInferenceEngine();
    }

    protected CalendarGateway createCalendarGateway() {
        return new AndroidCalendarGateway(this, () -> new RuntimePreferences(Lfm3PocketApplication.this).calendarWriteId());
    }

    protected AndroidActionExecutor createActionExecutor() {
        FlashlightController controller = new FlashlightController(new AndroidFlashlightCamera(this),
                () -> checkSelfPermission(android.Manifest.permission.CAMERA)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED);
        AndroidActionExecutor flashlight = new FlashlightActionExecutor(controller);
        AndroidActionExecutor volume = new VolumeActionExecutor(new VolumeController(new AndroidAudioVolume(this)));
        AndroidActionExecutor timer = new TimerActionExecutor(new TimerController(new AndroidClockTimer(this)));
        AndroidActionExecutor alarm = new AlarmActionExecutor(new AlarmController(new AndroidClockAlarm(this)));
        AndroidActionExecutor reminder = new ReminderActionExecutor(new WorkManagerReminderScheduler(this));
        AndroidActionExecutor application = new ApplicationActionExecutor(
                new InstalledAppController(new AndroidInstalledAppLauncher(this)));
        AndroidActionExecutor settings = new SettingsActionExecutor(new SettingsPageController(new AndroidSettingsLauncher(this)));
        AndroidTextActions textActions = new AndroidTextActions(this);
        AndroidActionExecutor text = new ClipboardShareActionExecutor(
                new TextActionController(textActions, textActions));
        AndroidActionExecutor builtins = new BuiltinToolsExecutor(
                () -> new RuntimePreferences(this).networkToolsEnabled(),
                OpenMeteoWeather.httpTransport());
        AndroidActionExecutor notifications = new NotificationListActionExecutor(
                () -> new RuntimePreferences(this),
                HanamiNotificationListenerService::snapshotOrNull,
                () -> HanamiNotificationListenerService.isSystemEnabled(this));
        MessageActionController mc = new MessageActionController(messageDraftStore(), new AndroidMessageActions(this));
        MessageActionExecutor message = new MessageActionExecutor(mc);
        AndroidActionExecutor calendar = new CalendarActionExecutor(createCalendarGateway());
        return new AndroidToolsExecutor(flashlight, volume, timer, alarm, application, settings, text, builtins, notifications, reminder, message, calendar);
    }
}
