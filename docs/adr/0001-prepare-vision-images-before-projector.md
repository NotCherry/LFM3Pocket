# Prepare vision images before the projector

## Status

Accepted

## Date

2026-11-16

## Context

Hanami runs the LFM3 vision projector on the phone. Gallery and camera photos were reaching that encoder at the size the camera captured. A multi-megapixel still is far larger than the projector's nominal vision size (512 on the longest side). Encoder time is expected to dominate time-to-first-token on those photos. That has not been measured; the ARM64 follow-up in Consequences is what would confirm it. The only published vision timing is an emulator run on a tiny fixture.

The LFM3 projector is a native-resolution encoder with a dynamic pixel budget, not a fixed tile grid. Cropping every photo to a square is not required for the encoder to accept it.

Screen placement of the attachment and the Settings group is in [`DESIGN.md`](../DESIGN.md) (§12). This record is the decision behind that stage, as shipped in [PR #169](https://github.com/NotCherry/LFM3Pocket/pull/169).

## Decision

Prepare a still on the device before it reaches the projector. Preparation is on by default and applies to gallery picks, camera captures, and images received from the system share sheet. The app is a share target for images.

- Cap the longest side. The default is 512, matching the projector's nominal vision size. The user can choose 384, 512, 768, 1024, or Original (no downscale).
- Keep the aspect ratio. An optional center crop to a square exists and is off by default.
- When a prepared copy is written, bake EXIF orientation into its pixels and re-encode it as JPEG. Quality is configurable from 40 to 95; the default is 85.
- Never modify the user's source file — the gallery item, the camera roll, or the file another app shared. The prepared copy lives in private app storage.
- Original, with square crop off and an upright photo, passes the private working copy through without a second encode. If preparation is on and the photo is not upright, still rewrite a private copy so the projector sees upright pixels. Turning preparation off leaves the private working copy uncapped and does not rewrite it.
- Settings → Images holds the on/off switch, the cap, the square-crop option, and JPEG quality. Those values persist in the app's private preferences, the same kind of on-device preferences storage used for the other settings.
- Prepared copies are temporary media. The existing history media cleanup that already deletes temporary photo and voice attachments deletes them too.

A shared image opens chat as an attachment the user can ask about. It does not approve tools.

## Consequences

- Time-to-first-token on large phone photos is expected to drop, because the encoder no longer ingests multi-megapixel input by default. The before/after was not measured when this shipped. Follow-up: on an ARM64 device with a real Omni projector, compare Original against the default cap on the same still and record first-token time. Published emulator vision timing used a tiny fixture and is not that comparison.
- Pass-through skips orientation and keeps the source container. With preparation off, a phone JPEG can reach the projector sideways, because neither this stage nor the vision decoder applies EXIF orientation. The same is true of an upright Original that is not cropped: the private working copy is handed over unchanged. The Android vision decoder reads the stills in its built-in loader (JPEG, PNG, GIF, BMP, and a few legacy formats). It does not read HEIC. WebP is decoded only by the optional video path, and that path is compiled out on Android. The in-app preview can still accept HEIC and WebP, because Android decodes them before this stage. Those files reach the projector only when a prepared JPEG is written. Original with no crop, and preparation off, are the paths that leave HEIC, WebP, and unapplied orientation in place.
- Fine detail can be lost, especially small text in a photograph. The user can choose Original, raise the cap, or turn preparation off.
- Prepared copies use extra private cache until cleanup runs.
- Reading orientation adds a dependency on AndroidX ExifInterface. Orientation is applied in the prepared copy; the user's file is not rewritten.
- The app appears in the system share sheet for images. Shared-image bytes stay on the device, take the same preparation path as a gallery photo, and do not authorize a tool.
- Privacy is unchanged in kind. Preparation, the prepared copy, and inference stay in private app storage. Nothing on this path is uploaded.
