package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class ReleaseVersionTest {
    @Test public void patchBumpIncreasesVersionCodeByOne() {
        ReleaseVersion version = ReleaseVersion.parseTag("v1.2.3");
        assertEquals("1.2.3", version.versionName);
        assertEquals(1_002_003, version.versionCode);
        assertEquals(version.versionCode + 1, ReleaseVersion.parseTag("v1.2.4").versionCode);
    }

    @Test public void minorAndMajorBumpsStayAboveEveryPreviousPatch() {
        assertEquals(2_000, ReleaseVersion.parseTag("v0.2.0").versionCode);
        assertEquals(1_000_000, ReleaseVersion.parseTag("v1.0.0").versionCode);
        assertEquals(999_999_999, ReleaseVersion.parseTag("v999.999.999").versionCode);
    }

    @Test public void untaggedBuildsMatchTheFirstHanamiVersion() {
        ReleaseVersion version = ReleaseVersion.untagged();
        assertEquals("0.1.0", version.versionName);
        assertEquals(1_000, version.versionCode);
        assertEquals(version.versionCode, ReleaseVersion.parseTag("v0.1.0").versionCode);
    }

    @Test public void preReleaseAndPartialTagsAreRejected() {
        String[] rejected = {"1.2.3", "v1.2", "v1.2.3-rc.1", "v1.2.3+1002003", "v01.2.3", "vv1.2.3", ""};
        for (String tag : rejected) {
            try {
                ReleaseVersion.parseTag(tag);
                fail("accepted " + tag);
            } catch (IllegalArgumentException expected) {
                assertEquals(true, expected.getMessage().contains("v1.2.3"));
            }
        }
    }
}
