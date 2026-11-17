package com.notcherry.lfm3pocket;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pinned tiny-voca Hugging Face revision and SHA-256 for each downloaded file. */
public final class TinyVocaPackManifest {
    public static final String HF_REPO = "n4ze3m/tiny-voca";
    /** Hugging Face dataset revision (commit) pinned for reproducible downloads. */
    public static final String HF_REVISION = "d94d2a0338fd1e0eab7dd896ec0ad23d8cdf7c14";
    public static final String HF_BASE = "https://huggingface.co/" + HF_REPO + "/resolve/" + HF_REVISION + "/";

    private static final Map<String, String> SHA256;

    static {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        map.put("config.json", "27bcbf322fc428581f0e959be1c141dd7751c61f4fed5eded326e779b215f69c");
        map.put("onnx/model.onnx", "fe6d918791a15b82e37465981d7f27a070e46b46635e5a3482fc8dff87a1ae46");
        map.put("phonemizer/lexicon.tsv", "3fc930345c6b3cabc533981c4b212fa309eec83913fcdbd42919338270d64b06");
        map.put("phonemizer/g2p.npz", "eb3fd519ff00e72d850928d2fccb968adc36134bca2f3bda09de7e794c2ed01b");
        map.put("phonemizer/g2p.json", "c518fd839ea0109c0b2b4f778c17506bf0d7653557b8b8c97a4c8dd9883d4432");
        map.put("phonemizer/align.json", "864763dbd558a864950266b2c30627f1cad203e75062ee842ee63c653e823b75");
        map.put("phonemizer/homographs.json", "9c3a96fcdf79fa27d1b3686dc6a507c37e4f754966928608ee9ddb617ba39192");
        map.put("phonemizer/lexicon.json", "4d67c1f94b86405a5b8766fb13b8cd92a1371204767dd709582a337e8e0ab334");
        map.put("phonemizer/sources.json", "f4c54924d30a5bc0bc1e3d7b058adb924484b3fab8541bc8d8bcecfb028fdbad");
        SHA256 = Collections.unmodifiableMap(map);
    }

    private TinyVocaPackManifest() {}

    public static Map<String, String> expectedSha256() {
        return SHA256;
    }
}