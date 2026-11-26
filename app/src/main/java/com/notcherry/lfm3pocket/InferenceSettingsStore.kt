package com.notcherry.lfm3pocket

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.function.Consumer

private val Context.inferencePreferences by preferencesDataStore(
    name="inference_settings",corruptionHandler=ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** One application-scoped DataStore instance; UI operations are asynchronous and atomic. */
class InferenceSettingsStore(context: Context) {
    private val store=context.applicationContext.inferencePreferences
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val contextKey=intPreferencesKey("context")
    private val threadsKey=intPreferencesKey("threads")
    private val maxKey=intPreferencesKey("max_tokens")
    private val promptLookupKey=booleanPreferencesKey("prompt_lookup")
    private val reasoningLevelKey=stringPreferencesKey("reasoning_level")
    private val threadsInitializedKey=booleanPreferencesKey("threads_initialized")
    fun load(success: Consumer<InferenceSettings>,failure: Consumer<String>) {
        scope.launch {
            try {
                val p=store.data.first()
                val threads = if (p[threadsInitializedKey] == true) {
                    p[threadsKey] ?: InferenceSettings.DEFAULT.threads
                } else {
                    // First launch: detect big/mid cores (or PocketPal 80% fallback).
                    CpuTopology.recommendedDecodeThreads()
                }
                val settings=try {
                    InferenceSettings(
                        p[contextKey]?:InferenceSettings.DEFAULT.contextTokens,
                        threads,
                        p[maxKey]?:InferenceSettings.DEFAULT.maxTokens,
                        p[promptLookupKey]?:InferenceSettings.DEFAULT.promptLookup,
                        ReasoningLevel.fromWire(p[reasoningLevelKey]),
                    )
                } catch(e: IllegalArgumentException) { InferenceSettings.DEFAULT }
                if (p[threadsInitializedKey] != true) {
                    store.edit { prefs ->
                        prefs[threadsKey] = settings.threads
                        prefs[threadsInitializedKey] = true
                        if (prefs[contextKey] == null) prefs[contextKey] = settings.contextTokens
                        if (prefs[maxKey] == null) prefs[maxKey] = settings.maxTokens
                    }
                }
                success.accept(settings)
            } catch(e: Exception) {
                if(e is CancellationException)throw e
                failure.accept(e.message?:"Cannot read local settings")
            }
        }
    }
    fun save(settings: InferenceSettings,success: Runnable,failure: Consumer<String>) {
        scope.launch {
            try {
                store.edit { p->
                    p[contextKey]=settings.contextTokens
                    p[threadsKey]=settings.threads
                    p[maxKey]=settings.maxTokens
                    p[promptLookupKey]=settings.promptLookup
                    p[reasoningLevelKey]=settings.reasoningLevel.wireName()
                    p[threadsInitializedKey]=true
                }
                success.run()
            } catch(e: Exception) {
                if(e is CancellationException)throw e
                failure.accept(e.message?:"Cannot save local settings")
            }
        }
    }
    fun close(){scope.cancel()}
}
