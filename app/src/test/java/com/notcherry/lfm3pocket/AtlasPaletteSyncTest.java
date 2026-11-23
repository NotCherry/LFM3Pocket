package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * JVM assert that View {@link StillwaterUi} hexes match the DESIGN table literals.
 * Compose {@code AtlasColors} ↔ View sync is enforced by
 * {@code .github/scripts/check-ui-tokens.py} (Compose Color is not on the pure JVM classpath).
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
