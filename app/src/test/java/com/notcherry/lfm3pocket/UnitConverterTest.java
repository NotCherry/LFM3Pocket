package com.notcherry.lfm3pocket;

import org.junit.Test;
import static org.junit.Assert.*;

public final class UnitConverterTest {
    @Test public void convertsCommonFamilies() {
        assertEquals(1000.0, UnitConverter.convert(1, "km", "m"), 1e-9);
        assertEquals(2.54, UnitConverter.convert(1, "in", "cm"), 1e-9);
        assertEquals(0.45359237, UnitConverter.convert(1, "lb", "kg"), 1e-9);
        assertEquals(3.785411784, UnitConverter.convert(1, "gal", "l"), 1e-9);
        assertEquals(3.6, UnitConverter.convert(1, "m/s", "km/h"), 1e-9);
        assertEquals(37.0, UnitConverter.convert(98.6, "f", "c"), 1e-6);
        assertEquals(273.15, UnitConverter.convert(0, "c", "k"), 1e-9);
    }

    @Test public void rejectsCrossFamilyAndUnknownUnits() {
        try {
            UnitConverter.convert(1, "km", "kg");
            fail();
        } catch (IllegalArgumentException expected) { }
        try {
            UnitConverter.convert(1, "parsec", "m");
            fail();
        } catch (IllegalArgumentException expected) { }
        try {
            UnitConverter.convert(-1, "k", "c");
            fail();
        } catch (IllegalArgumentException expected) { }
    }
}
