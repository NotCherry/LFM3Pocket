# Cactus runtime license notice

Phone ARM64 GitHub release builds of Hanami link the pinned Cactus Android
`android-arm64` static library (`libcactus.a`) into `liblfm3pocket.so` for the
optional Cactus Whistle STT backend.

| Artifact | Upstream | License |
| --- | --- | --- |
| `libcactus.a` / `cactus.h` (pinned release under `vendor/CACTUS_RELEASE`) | [Cactus-Compute/cactus](https://huggingface.co/Cactus-Compute/cactus) | Apache-2.0 (`Apache-2.0.txt` in this directory) |

The Whistle model weights (`whistle.cact`) are **not** shipped in the APK; users
download or select them separately (also Apache-2.0). The application source
license remains MIT (`LICENSE`). This notice does not change that license.
