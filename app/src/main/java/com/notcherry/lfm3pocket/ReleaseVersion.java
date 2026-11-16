package com.notcherry.lfm3pocket;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a git tag {@code vMAJOR.MINOR.PATCH} onto Android {@code versionName}
 * and {@code versionCode}.
 *
 * <p>{@code versionCode = major * 1_000_000 + minor * 1_000 + patch}. Each
 * component is 0..999, so the code stays below {@link Integer#MAX_VALUE} and
 * rises for any normal semver bump. Untagged builds use {@code v0.1.0}
 * (versionName {@code 0.1.0}, versionCode {@code 1000}).
 */
public final class ReleaseVersion {
    static final int COMPONENT_SPAN = 1000;
    private static final Pattern TAG = Pattern.compile(
            "^v(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})$");

    public final String versionName;
    public final int versionCode;

    private ReleaseVersion(String versionName, int versionCode) {
        this.versionName = versionName;
        this.versionCode = versionCode;
    }

    public static ReleaseVersion parseTag(String tag) {
        if (tag == null) throw new IllegalArgumentException("Release tag is missing");
        String trimmed = tag.trim();
        Matcher matcher = TAG.matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Release tag must look like v1.2.3 with no pre-release suffix. Got: " + trimmed);
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int patch = Integer.parseInt(matcher.group(3));
        int code = major * 1_000_000 + minor * COMPONENT_SPAN + patch;
        return new ReleaseVersion(major + "." + minor + "." + patch, code);
    }

    /** Version used when HEAD is not an exact {@code vMAJOR.MINOR.PATCH} tag. */
    public static ReleaseVersion untagged() {
        return parseTag("v0.1.0");
    }

    public static void main(String[] args) {
        if (args.length != 1 || args[0].isBlank()) {
            System.err.println("usage: ReleaseVersion vMAJOR.MINOR.PATCH");
            System.exit(2);
        }
        try {
            ReleaseVersion parsed = parseTag(args[0]);
            System.out.println("versionName=" + parsed.versionName);
            System.out.println("versionCode=" + parsed.versionCode);
        } catch (RuntimeException error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }
}
