# ADR 0025: Taint-Aware Tool Confirmation (Amending ADR 0020)

- **Status:** Accepted
- **Date:** 2026-11-25
- **Amends:** `docs/adr/0020-document-attachments-v1.md` (blanket tool block on document turns)

## Context
ADR 0020 introduced document attachments and originally disabled all tool calls whenever an attached document was present in the conversation. While this prevented silent prompt-injection side effects, it also blocked core real-world workflows users expect from an on-device assistant:
- Attaching a PDF or text file and asking Hanami to save a summary note into the user's Local Workspace (`note_create`),
- Attaching an agenda/ticket and asking Hanami to schedule a calendar event (`create_event`),
- Cross-referencing an attached document against existing local notes (`fs_search`, `fs_read`).

## Decision
Replace the blanket disable on document turns with **taint-aware tool confirmation**:

1. **Preserve untrusted framing & audit flag:**
   - Document attachments remain wrapped as untrusted reference material in prompts with control-token escaping (`ChatSession.escapePromptContent`).
   - `ChatSession.CompletedTurn.untrustedDocumentContext` continues to be `true` for document-attached turns, and legacy post-turn parsing (`ModelRuntime.saveTurn`) remains gated by `!turn.untrustedDocumentContext`.
2. **Track per-turn taint (`turnTainted`):**
   - A turn is marked tainted whenever local document attachments are present, or when a read-only tool returns `UNTRUSTED_*` external data (`web_search`, `fs_read`, `fs_search`, `list_notifications`).
3. **Block silent data exfiltration:**
   - Outbound network tools (`web_search`, `weather`) are **never** auto-executed on document-tainted turns; if requested, they require explicit user confirmation showing the exact query.
4. **Mandatory user confirmation on all tainted side effects:**
   - When `turnTainted` is true, any write or side-effect tool (including normally `SAFE` actions like `toggle_flashlight` or `set_timer`) routes through `ToolCoordinator.proposeTainted(...)`, which forces `Risk.CONFIRM`, never auto-executes, and displays a visible warning notice (`"Proposed while reading attached/external content. Verify details before approving."`) alongside the exact arguments/diff preview.
5. **Local read-only helpers stay usable:**
   - Pure local read-only helpers (`search_tools`, `current_time`, `calculator`, `unit_convert`, `fs_list`, `fs_search`, `fs_read`, `list_events`) may still auto-execute inside the bounded agent loop so the model can synthesize answers across documents and local notes offline.

## Consequences
- Users can attach PDFs/text files and cleanly create notes or calendar events from them with explicit, reviewable confirmation cards.
- Prompt injection inside a PDF, web page, or note still cannot silently execute device actions, mutate files, or exfiltrate private document text over the network.
