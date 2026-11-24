package com.notcherry.lfm3pocket;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/** ONNX Runtime synthesis for tiny-voca (Apache-2.0 weights). */
public final class TinyVocaOnnxRuntime implements AutoCloseable {
    private static volatile OrtEnvironment env;

    private final OrtSession session;
    private final int maxTokens;
    private final int sampleRate;

    private TinyVocaOnnxRuntime(OrtSession session, int maxTokens, int sampleRate) {
        this.session = session;
        this.maxTokens = maxTokens;
        this.sampleRate = sampleRate;
    }

    public static boolean isNativePhonemizerAvailable() {
        return TinyVocaPhonemizerBridge.isAvailable();
    }

    public static TinyVocaOnnxRuntime open(Context context) throws Exception {
        TinyVocaPackStore store = new TinyVocaPackStore(context);
        if (!store.isReady()) throw new IllegalStateException("tiny-voca pack is not installed");
        if (!TinyVocaPhonemizerBridge.ensureLoaded(store.phonemizerDir())) {
            throw new IllegalStateException("English phonemizer is unavailable on this build");
        }
        JSONObject config = store.readConfig();
        JSONObject modelConfig = config.optJSONObject("model");
        int maxTokens = modelConfig == null ? 512 : modelConfig.optInt("max_tokens", 512);
        int sampleRate = config.optInt("sample_rate", 24000);
        OrtEnvironment environment = environment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(CpuTopology.recommendedDecodeThreads());
            OrtSession session = environment.createSession(store.onnxModel().getAbsolutePath(), options);
            return new TinyVocaOnnxRuntime(session, maxTokens, sampleRate);
        }
    }

    public int sampleRateHz() { return sampleRate; }

    static void checkNotCancelled(SpeechCancellation cancellation) throws SpeechSynthesisCancelled {
        if (cancellation != null && cancellation.isCancelled()) {
            throw new SpeechSynthesisCancelled();
        }
    }

    public interface SpeechCancellation {
        boolean isCancelled();
    }

    public byte[] synthesizePcm16(String text, String voice) throws Exception {
        return synthesizePcm16(text, voice, null);
    }

    public byte[] synthesizePcm16(String text, String voice, SpeechCancellation cancellation) throws Exception {
        checkNotCancelled(cancellation);
        List<String> sentences = TinyVocaSentenceSplit.sentences(TinyVocaSentenceSplit.finished(text));
        List<float[]> chunks = new ArrayList<>();
        int budget = Math.max(32, maxTokens - 12);
        int voiceIndex = "M2".equalsIgnoreCase(voice) ? 1 : 0;
        for (String sentence : sentences) {
            checkNotCancelled(cancellation);
            List<String> pieces = TinyVocaSentenceSplit.shrinkForTokens(sentence, budget);
            for (String piece : pieces) {
                checkNotCancelled(cancellation);
                TinyVocaPhonemes phones = TinyVocaPhonemizerBridge.phonemize(piece);
                if (phones == null || phones.ids.length == 0) continue;
                float[] audio = runSession(phones, voiceIndex);
                if (audio.length > 0) chunks.add(limitPeak(audio));
            }
        }
        if (chunks.isEmpty()) throw new IllegalStateException("Nothing speakable in English for tiny-voca");
        int length = 0;
        for (float[] chunk : chunks) length += chunk.length;
        float[] merged = new float[length];
        int offset = 0;
        for (float[] chunk : chunks) {
            System.arraycopy(chunk, 0, merged, offset, chunk.length);
            offset += chunk.length;
        }
        limitPeak(merged);
        return floatToPcm16(merged);
    }

    private float[] runSession(TinyVocaPhonemes phones, int voiceIndex) throws Exception {
        long count = phones.ids.length;
        try (OnnxTensor tokens = OnnxTensor.createTensor(environment(),
                     java.nio.LongBuffer.wrap(phones.ids), new long[] {1, count});
             OnnxTensor stress = OnnxTensor.createTensor(environment(),
                     java.nio.LongBuffer.wrap(phones.stress), new long[] {1, count});
             OnnxTensor voice = OnnxTensor.createTensor(environment(),
                     java.nio.LongBuffer.wrap(new long[] {voiceIndex}), new long[] {1});
             OnnxTensor speed = OnnxTensor.createTensor(environment(),
                     FloatBuffer.wrap(new float[] {1f}), new long[] {1});
             OnnxTensor pitchShift = OnnxTensor.createTensor(environment(),
                     FloatBuffer.wrap(new float[] {0f}), new long[] {1});
             OnnxTensor pitchRange = OnnxTensor.createTensor(environment(),
                     FloatBuffer.wrap(new float[] {1f}), new long[] {1});
             OrtSession.Result result = session.run(java.util.Map.of(
                     "tokens", tokens,
                     "stress", stress,
                     "voice", voice,
                     "speed", speed,
                     "pitch_shift", pitchShift,
                     "pitch_range", pitchRange))) {
            Object value = result.get(0).getValue();
            if (value instanceof float[]) return (float[]) value;
            if (value instanceof float[][]) return ((float[][]) value)[0];
            throw new IllegalStateException("Unexpected ONNX audio output");
        }
    }

    private static float[] limitPeak(float[] samples) {
        float loudest = 0f;
        for (float sample : samples) loudest = Math.max(loudest, Math.abs(sample));
        if (loudest > 0.99f) {
            float scale = 0.99f / loudest;
            for (int i = 0; i < samples.length; i++) samples[i] *= scale;
        }
        return samples;
    }

    private static byte[] floatToPcm16(float[] samples) {
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            int value = (int) Math.max(-32768, Math.min(32767, Math.round(samples[i] * 32767f)));
            pcm[i * 2] = (byte) value;
            pcm[i * 2 + 1] = (byte) (value >> 8);
        }
        return pcm;
    }

    private static OrtEnvironment environment() {
        if (env == null) {
            synchronized (TinyVocaOnnxRuntime.class) {
                if (env == null) env = OrtEnvironment.getEnvironment();
            }
        }
        return env;
    }

    @Override
    public void close() {
        try { session.close(); } catch (Exception ignored) { }
    }
}