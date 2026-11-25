package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.os.Handler;

import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Renders Android tool action cards, handles user approval/rejection via {@link ToolCoordinator},
 * syncs runtime consent state, and persists completed tool outcomes to history (#322).
 */
final class ToolOutcomePresenter {
    static final class Deps {
        final Activity activity;
        final ModelRuntime runtime;
        final HistoryRepository historyRepository;
        final ExecutorService worker;
        final Handler mainHandler;
        final Supplier<ChatPanel> chatPanel;
        final Supplier<String> historyConversationId;
        final Supplier<String> historyTitle;
        final BooleanSupplier isDestroyed;

        Deps(Activity activity, ModelRuntime runtime, HistoryRepository historyRepository,
                ExecutorService worker, Handler mainHandler, Supplier<ChatPanel> chatPanel,
                Supplier<String> historyConversationId, Supplier<String> historyTitle,
                BooleanSupplier isDestroyed) {
            this.activity = activity;
            this.runtime = runtime;
            this.historyRepository = historyRepository;
            this.worker = worker;
            this.mainHandler = mainHandler;
            this.chatPanel = chatPanel;
            this.historyConversationId = historyConversationId;
            this.historyTitle = historyTitle;
            this.isDestroyed = isDestroyed;
        }
    }

    private final Deps deps;
    private ToolCoordinator toolCoordinator;

    ToolOutcomePresenter(Deps deps) {
        this.deps = deps;
    }

    void present(ToolCoordinator.Outcome outcome) {
        if (outcome == null) return;
        ToolRegistry.Request request = outcome.proposal == null ? null : outcome.proposal.request;
        present(request, outcome);
    }

    void present(ToolRegistry.Request request, ToolCoordinator.Outcome outcome) {
        if (outcome == null || deps.chatPanel.get() == null) return;
        toolCoordinator = ((Lfm3PocketApplication) deps.activity.getApplication()).toolCoordinator();
        render(outcome.proposal, request, outcome);
    }

    private void render(ToolCoordinator.Proposal proposal, ToolRegistry.Request request,
            ToolCoordinator.Outcome outcome) {
        ChatPanel panel = deps.chatPanel.get();
        if (panel == null) return;
        boolean pending = proposal != null && "PENDING_CONFIRMATION".equals(outcome.status);
        if (proposal != null) request = proposal.request;
        String details = request == null ? "Android action" : ToolCoordinator.describe(request);
        ToolCoordinator.Risk actionRisk = proposal == null ? ToolCoordinator.risk(request) : proposal.risk;
        String risk = request == null ? "Risk unavailable"
                : actionRisk == ToolCoordinator.Risk.CONFIRM ? "Risk: confirmation required" : "Risk: safe action";
        String result = outcome.result == null ? "" : outcome.result.message;
        ToolRegistry.Request actionRequest = request;
        Runnable approve = pending ? () -> resolve(proposal, actionRequest, true) : null;
        Runnable reject = pending ? () -> resolve(proposal, actionRequest, false) : null;
        panel.renderToolCard(details, risk, labelFor(outcome), result, pending, approve, reject);
        if (pending && proposal != null) {
            deps.runtime.showConsent(proposal.id, ToolCoordinator.describe(proposal.request),
                    proposal.risk == ToolCoordinator.Risk.SAFE);
        } else {
            deps.runtime.clearConsent();
        }
        if (!pending && request != null && outcome.result != null) {
            persist(proposal, request, outcome);
        }
    }

    private void resolve(ToolCoordinator.Proposal proposal, ToolRegistry.Request request, boolean approve) {
        if (deps.isDestroyed.getAsBoolean()) return;
        ChatPanel panel = deps.chatPanel.get();
        if (panel != null) {
            panel.renderToolCard(ToolCoordinator.describe(request),
                    proposal.risk == ToolCoordinator.Risk.CONFIRM
                            ? "Risk: confirmation required" : "Risk: safe action",
                    approve ? "Executing Android action…" : "Declining Android action…",
                    "", false, null, null);
        }
        deps.worker.execute(() -> {
            ToolCoordinator.Outcome outcome = approve
                    ? toolCoordinator.approve(proposal.id) : toolCoordinator.reject(proposal.id);
            deps.mainHandler.post(() -> {
                if (!deps.isDestroyed.getAsBoolean()) render(proposal, request, outcome);
            });
        });
    }

    private void persist(ToolCoordinator.Proposal proposal, ToolRegistry.Request request,
            ToolCoordinator.Outcome outcome) {
        String requestId = proposal == null ? UUID.randomUUID().toString() : proposal.id;
        String arguments = new JSONObject(request.arguments).toString();
        HistoryRepository.ToolExecution execution = new HistoryRepository.ToolExecution(requestId,
                request.tool.wireName, arguments, outcome.result.succeeded, outcome.result.status,
                outcome.result.message, System.currentTimeMillis());
        String conversationId = deps.historyConversationId.get();
        String title = deps.historyTitle.get();
        try {
            deps.worker.execute(() -> deps.historyRepository.appendToolOutcome(conversationId, title, execution));
        } catch (RejectedExecutionException ignored) {
            // Activity teardown owns no UI work; the in-flight generation path remains unaffected.
        }
    }

    static String labelFor(ToolCoordinator.Outcome outcome) {
        return labelFor(outcome.status, outcome.result);
    }

    static String labelFor(String status, AndroidActionExecutor.ActionResult result) {
        switch (status) {
            case "PENDING_CONFIRMATION": return "Needs your approval";
            case "DECLINED": return "Declined";
            case "EXPIRED": return "Approval expired";
            case "PERMISSION_DENIED": return "Permission denied";
            case "UNTRUSTED_SOURCE": return "Request blocked";
            case "ALREADY_RESOLVED": return "Action already resolved";
            case "UNKNOWN_PROPOSAL": return "Action no longer available";
            default:
                if (result == null) return "Action unavailable";
                return result.succeeded ? "Action complete" : "Action failed";
        }
    }
}
