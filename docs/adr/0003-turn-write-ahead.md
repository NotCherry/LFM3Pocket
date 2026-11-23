# ADR 0003 — Write the chat turn before inference

- Status: Accepted (reconstructed retrospectively)
- Date: 2026-11-16
- Related: [#170](https://github.com/NotCherry/LFM3Pocket/pull/170), [#204](https://github.com/NotCherry/LFM3Pocket/pull/204), [#250](https://github.com/NotCherry/LFM3Pocket/pull/250), [`HISTORY_SCHEMA.md`](../HISTORY_SCHEMA.md), [ADR 0004](0004-local-crash-log.md), [ADR 0005](0005-prepare-vision-images.md)

## Context

A vision turn could die with the process before the user message was stored. Reopening the chat then showed an empty thread: the photo and the question were gone, and there was nothing to retry. Saving only after a reply finished made process death look the same as a turn that never happened.

Generation already runs under the foreground service. Leaving the chat does not by itself abandon an in-flight reply. The gap is process death, not backgrounding.

## Decision

### 1. Write the turn before inference

The user message is committed to the private conversation store before speech recognition, web search, or inference starts. The same write creates an assistant row in a generating state. If the write fails, inference does not start.

While tokens arrive, the assistant text already stored is updated so a later recovery can keep a partial answer. A normal finish replaces the generating row with a terminal outcome: completed, cancelled (the user stopped the reply), or failed.

### 2. Keep a finished private copy of the photo

A photo attached to the turn is copied into app-private storage as part of that write. The conversation row stores a reference only after the copy is complete. A partial or truncated copy is discarded and does not replace an existing file. Deleting the conversation, or deleting all history, deletes those copies. A voice recording stays temporary and is not kept for a later retry.

### 3. Recover interrupted turns once, before new work

On the next process start, every assistant row still marked generating is marked interrupted. That pass runs once, on the serialized runtime worker, before a new turn can be queued. Saved partial text is kept. An assistant row with no text yet is given a short placeholder that says the reply was interrupted.

Reopening the chat restores that conversation into the live thread. An interrupted assistant turn is shown as interrupted, with a Retry control. Retry sends the saved question again, and the private photo when that file is still present. It starts a new turn. The interrupted reply stays in the transcript.

Backgrounding the app is not an interrupt. Only a generating row left behind by process death is. If a reply finishes while no screen is visible, a local notification says the reply is ready.

### 4. A bad image fails the turn, not the process

Photos still go through the single preparation step ([ADR 0005](0005-prepare-vision-images.md)) before they are attached. Before native vision runs, the attached file is checked with a bounded decode that discards the bitmap it allocates and does not write a second downscaled copy. An image that fails that check, or a native vision error, becomes a failed reply. The process stays up and the user message already written remains.

## Consequences

- A kill during inference leaves a restorable turn instead of a missing one.
- Retry can duplicate the question in the thread. It does not rewrite the interrupted answer in place.
- Peak storage grows by the private photo copies until the conversation is deleted.
- A failed image check still consumes a stored turn (user message plus a failed assistant outcome).
- Why the process died is a separate local log ([ADR 0004](0004-local-crash-log.md)). This record does not explain the crash.

## Alternatives considered

- **Save only after the reply finishes.** This was the previous behavior. Rejected: process death was indistinguishable from a turn that never started.
- **A second downscale path inside the safety check.** Rejected. Preparation stays the only writer of the inference image, so the two paths cannot drift.
