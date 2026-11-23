package com.notcherry.lfm3pocket;

/** Free-space and metered-network checks before starting a model download. */
public final class ModelDownloadPolicies {
    /** Extra headroom on top of the expected payload so temp files and metadata fit. */
    public static final long FREE_SPACE_MARGIN_BYTES = 8L * 1024L * 1024L;

    public interface UsableSpace {
        long usableBytes(String path);
    }

    public interface MeteredNetwork {
        boolean isActiveNetworkMetered();
    }

    private ModelDownloadPolicies() {}

    public static boolean hasSufficientSpace(UsableSpace space, String filesDirPath, long expectedBytes) {
        long required = expectedBytes + FREE_SPACE_MARGIN_BYTES;
        return space.usableBytes(filesDirPath) >= required;
    }

    public static boolean shouldConfirmMetered(MeteredNetwork network, boolean userConfirmedMetered) {
        return network.isActiveNetworkMetered() && !userConfirmedMetered;
    }
}
