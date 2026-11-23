package com.notcherry.lfm3pocket;

import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runners.model.MultipleFailureException;
import org.junit.runners.model.Statement;
import org.junit.runners.model.TestTimedOutException;

/** Proves {@link TestTimeouts#SELF_CHECK_RULE} interrupts a hanging {@link Statement}. */
public final class TestTimeoutsSelfTest {
    @Test
    public void timeoutRuleInterruptsHangingStatement() throws Throwable {
        Statement hanging = new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Thread.sleep(60_000L);
            }
        };
        try {
            TestTimeouts.SELF_CHECK_RULE.apply(hanging, Description.EMPTY).evaluate();
            fail("Timeout rule did not fire");
        } catch (TestTimedOutException expected) {
            // ok
        } catch (MultipleFailureException multi) {
            for (Throwable failure : multi.getFailures()) {
                if (failure instanceof TestTimedOutException) {
                    return;
                }
            }
            throw multi;
        }
    }
}
