# ADR 0005 — Prepare vision images before the projector

- Status: Accepted (reconstructed retrospectively)
- Date: 2026-11-16
- Related: [#169](https://github.com/NotCherry/LFM3Pocket/pull/169), [ADR 0003](0003-turn-write-ahead.md)

## Context

Hanami runs the LFM vision projector on the phone. Gallery and camera photos were reaching that encoder at the size the camera captured. A multi-megapixel still is far larger than the projector’s nominal vision size (512 on the longest side). The projector is a native-resolution encoder with a dynamic pixel budget, not a fixed tile grid, so cropping every photo to a square is not required for the encoder to accept it.

## Decision

Prepare a still on the device before it reaches the projector. Preparation is on by default and applies to gallery picks, camera captures, and images received from the system share sheet. The app is a share target for images.

- Cap the longest side. The default is 512, matching the projector’s nominal vision size. The user can choose 384, 512, 768, 1024, or Original (no downscale).
- Keep the aspect ratio. An optional center crop to a square exists and is off by default.
- When a prepared copy is written, bake orientation into its pixels and re-encode it as JPEG. Quality is configurable from 40 to 95; the default is 85.
- Never modify the user’s source file. The prepared copy lives in private app storage.
- Original, with square crop off and an upright photo, passes the private working copy through without a second encode. If preparation is on and the photo is not upright, still rewrite a private copy so the projector sees upright pixels. Turning preparation off leaves the private working copy uncapped and does not rewrite it.
- Settings holds the on/off switch, the cap, the square-crop option, and JPEG quality.
- Prepared copies are temporary media and follow the same cleanup as other temporary photo attachments. The durable history copy, when a turn is kept, is [ADR 0003](0003-turn-write-ahead.md).

A shared image opens chat as an attachment the user can ask about. It does not approve tools.

## Consequences

- The encoder no longer ingests multi-megapixel input by default. The before/after on a physical phone was not measured when this shipped.
- With preparation off, or with an upright Original that is not cropped, the private working copy is handed over unchanged. Orientation is applied only on a prepared copy.
- Fine detail can be lost, especially small text in a photograph. The user can choose Original, raise the cap, or turn preparation off.
- Privacy is unchanged in kind. Preparation and inference stay in private app storage. Nothing on this path is uploaded.

## Alternatives considered

- **Send camera resolution straight to the projector.** Rejected for the default. Encoder time on a multi-megapixel still dominates, and the user can still choose Original.
- **Always square-crop.** Rejected. The projector does not require a fixed tile, and a square crop throws away the frame.
