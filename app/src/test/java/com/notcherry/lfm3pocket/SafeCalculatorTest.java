package com.notcherry.lfm3pocket;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SafeCalculatorTest {
    @Test public void evaluatesArithmeticWithPrecedenceAndParentheses() {
        assertEquals(14.0, SafeCalculator.evaluate("2+3*4"), 0.0);
        assertEquals(20.0, SafeCalculator.evaluate("(2+3)*4"), 0.0);
        assertEquals(-1.5, SafeCalculator.evaluate("-(3/2)"), 0.0);
        assertEquals("20", SafeCalculator.format(SafeCalculator.evaluate("(2+3)*4")));
    }

    @Test public void rejectsUnsafeOrMalformedInput() {
        String[] bad = {"", "2+ ", "alert(1)", "2^3", "1e999", "1/0", "(((1))", "2 = 2"};
        for (String expression : bad) {
            try {
                SafeCalculator.evaluate(expression);
                fail(expression);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }
}
