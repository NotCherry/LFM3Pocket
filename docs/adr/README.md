# Architecture decision records

ADRs record durable product and architecture decisions. They state why a choice was made and what follows from it. Screen recipes stay in [`DESIGN.md`](../DESIGN.md). Class names, parameters, line numbers, and source paths stay in the code.

## Numbering

One file per decision: `NNNN-kebab-title.md`, starting at `0001`, in the order the decision is accepted. Do not renumber an accepted record. A decision that is replaced keeps its number; the replacement is a new record that points at it.

## Template

```markdown
# Title

## Status

Proposed | Accepted | Superseded by ADR-NNNN

## Date

YYYY-MM-DD

## Context

## Decision

## Consequences
```

## Index

- [ADR-0001: arm64 DOTPROD kernels and text KV residency](0001-arm64-dotprod-and-kv-residency.md) — Accepted (2026-11-16)
- [ADR-0002: Prepare vision images before the projector](0002-prepare-vision-images-before-projector.md) — Accepted (2026-11-16)
