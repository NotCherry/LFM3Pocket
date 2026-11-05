package com.notcherry.lfm3pocket;

import java.util.Map;

/**
 * Boundary for future validated Android actions.
 * Model-generated strings must be validated and authorized before execution.
 */
public interface AndroidActionExecutor {
    ActionResult execute(String approvedToolName, Map<String, String> validatedArguments);

    final class ActionResult {
        public final boolean succeeded;
        public final String status;
        public final String message;

        public ActionResult(boolean succeeded, String status, String message) {
            this.succeeded = succeeded;
            this.status = status;
            this.message = message;
        }
    }
}
