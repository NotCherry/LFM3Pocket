# ADR 0003 — Write-ahead chat turns and recovery after process death

## Status

Accepted. [PR #170](https://github.com/NotCherry/LFM3Pocket/pull/170), 2026-11-16.

## Context

A vision turn could die with the process before the user message was stored. Reopening the chat then showed an empty thread: the photo and the question were gone, and there was nothing to retry. Saving only after a reply finished made process death (crash, low memory, or a kill) look the same as a turn that never happened.

Generation already runs under the existing foreground service. Leaving the chat does not by itself abandon an in-flight reply. The gap is process death, not backgrounding.

Oversized photos were one way to kill the process inside native vision. The app already downscales images for that path. A second downscale implementation would drift from that behavior.

## Decision

### 1. Write the turn before inference

The user message is committed to the private conversation store before speech recognition, web search, or vision/inference starts. The same write creates an assistant row in a generating state. If the write fails, inference does not start.

While tokens arrive, the assistant text already stored is updated so a later recovery can keep a partial answer. A normal finish replaces the generating row with a terminal outcome: completed, cancelled (the user stopped the reply), or failed (the model or a safety check returned an error).

A photo attached to the turn is copied into app-private storage as part of the write, and the conversation row keeps a reference to that copy. Deleting the conversation, or deleting all history, deletes those copies. A voice recording stays temporary and is not kept for a later retry.

### 2. Recover interrupted turns

On the next process start, every assistant row still marked generating is marked interrupted. Saved partial text is kept. An assistant row with no text yet is given a short placeholder that says the reply was interrupted.

Reopening the chat restores that conversation into the live thread. An interrupted assistant turn is shown as interrupted, with a Retry control. Retry sends the saved question again, and the private photo copy when that file is still present. It starts a new turn. The interrupted reply stays in the transcript.

Retry always runs offline. A web search that was on for the original turn is not repeated. Retry does not reattach a voice recording. A voice-only turn stores the placeholder “Listen to this recording and answer.”, so Retry sends that sentence with no audio. A speech transcript, if one was saved on the original turn, is not what Retry resends.

The history reader shows that a saved assistant turn was not completed. Retry is only on the live chat.

Generation stays on the existing foreground service, in this process. If a reply finishes while no screen is visible, a local notification says the reply is ready.

### 3. Oversized images fail the turn instead of the process

Photos still go through the existing prepare/downscale step before they are attached. That step remains the only writer of the inference image.

Before native vision runs, the attached file is checked with a bounded, subsampled decode. The check discards the bitmap it allocates. It does not write a second downscaled copy. An image that fails the existing size checks or this decode becomes a failed reply. Native vision errors, including running out of memory, are caught and become a failed reply as well, so the process stays up and the already-written user message remains.

## Consequences

- A kill during inference leaves a restorable turn instead of a missing one.
- Retry can duplicate the question in the thread; it does not rewrite the interrupted answer in place.
- Retry of a voice-only turn asks the model to “Listen to this recording and answer.” with no recording attached. A typed question that accompanied a recording is resent as text only.
- Retry of a turn that used web search runs fully offline.
- The interrupt pass marks every assistant row that is still generating. It has no cutoff by start time, and it runs asynchronously after process start (and again when the open chat is restored). A turn written before that pass finishes can be marked interrupted as well.
- Peak storage grows by the private photo copies until the conversation is deleted.
- A failed image check still consumes a stored turn (user message plus a failed assistant outcome).
- Backgrounding the app does not mark a turn interrupted. The interrupt flag is applied by the startup pass above.
