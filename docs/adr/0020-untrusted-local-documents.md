# ADR 0020 — Attached documents are untrusted and cannot propose tools

- Status: Accepted (reconstructed retrospectively)
- Date: 2026-11-19
- Related: [#223](https://github.com/NotCherry/LFM3Pocket/pull/223), [#166](https://github.com/NotCherry/LFM3Pocket/issues/166)

## Context

The user can attach a local document and ask about it. The file is chosen on device. Its text can contain anything, including text shaped like a tool call. Treating that text as the model’s own proposal would let a document approve work the user did not ask for.

## Decision

1. **Local only.** Document bytes are read from the user’s selection into a bounded excerpt for the prompt. They are not uploaded.
2. **Untrusted.** The excerpt is context, with the file name so the reply can cite it. It is not user authority and not a model proposal.
3. **No tools from that turn.** A turn that included document context does not turn assistant text into tool proposals. A later turn without a document can propose tools again, under the normal confirmation rules.
4. **Fail closed on the file.** A file the app cannot read as text, or one past the size cap, fails with an error and is not attached. PDF text extraction is not part of this decision.

## Consequences

- A document that says “approve” or contains tool JSON does not create a proposal.
- The model can still quote or summarize the document in its reply.
- Very large files and unsupported types are refused instead of being attached. The caps stay in the reader.

## Alternatives considered

- **Let the model propose tools while quoting the document, and rely on confirmation.** Rejected. Confirmation still shows a proposal the document planted. This decision removes that proposal.
- **Upload the file for extraction.** Rejected. The product stays on device.
