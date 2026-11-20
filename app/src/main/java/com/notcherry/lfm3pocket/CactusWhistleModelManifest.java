package com.notcherry.lfm3pocket;

/** Pinned Cactus Whistle Hugging Face revision and SHA-256 for the downloaded model. */
public final class CactusWhistleModelManifest {
    public static final String HF_REPO = "Cactus-Compute/whistle";
    /** Hugging Face model revision (commit) pinned for reproducible downloads. */
    public static final String HF_REVISION = "88cb00eeb8928ddbcd410b491584f938ba030b07";
    public static final String FILE_NAME = "whistle.cact";
    public static final String HF_BASE = "https://huggingface.co/" + HF_REPO + "/resolve/" + HF_REVISION + "/";
    public static final String SHA256 = "b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb";

    private CactusWhistleModelManifest() {}
}
