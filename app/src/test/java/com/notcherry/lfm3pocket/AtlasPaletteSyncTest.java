package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Keeps View {@link StillwaterUi} hexes aligned with DESIGN / Compose {@code AtlasColors}
 * (Compose Color is not available on the pure JVM unit classpath).
 */
public final class AtlasPaletteSyncTest {
    @Test public void softChalkMatchesDesignInkSoft() {
        assertEquals(0xFFC9C2B4, StillwaterUi.SOFT);
    }

    @Test public void corePaletteMatchesDesignTable() {
        assertEquals(0xFF0E0E0C, StillwaterUi.SURFACE);
        assertEquals(0xFF161614, StillwaterUi.RAISED);
        assertEquals(0xFFF3EFE6, StillwaterUi.INK);
        assertEquals(0xFF8F897C, StillwaterUi.MUTED);
        assertEquals(0xFFA39E92, StillwaterUi.DISABLED);
        assertEquals(0xFFE6C27A, StillwaterUi.CANDLE);
        assertEquals(0xFF1C1508, StillwaterUi.CANDLE_INK);
        assertEquals(0xFFE08A78, StillwaterUi.DANGER);
        assertEquals(0xFF9DBEA4, StillwaterUi.OK);
        assertEquals(0xFF2C2B28, StillwaterUi.LINE);
    }
}
