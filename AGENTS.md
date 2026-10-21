# Project guidance

## Start here

- Read [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the current implementation map and module boundaries.

## Project conventions

- The album entry on the camera screen is intentionally an invisible double-tap hit area. It is a deliberate interaction detail. Do not replace it with a visible button or add an on-screen hint unless the user explicitly asks.
- App-authored UI copy must not explicitly mention “题目” or “解答” (or equivalent direct problem/solution wording). Use neutral terms such as “照片、内容、记录、处理”. This applies to labels, hints, statuses, empty states, errors, accessibility descriptions, and notifications. Do not rewrite user/model-generated content to enforce this UI-copy rule.
- Keep documentation aligned with the code that exists. Label proposed or future behavior as such; do not describe a design proposal as shipped functionality.
- Update the architecture guide when introducing or moving major responsibilities, persistence, background execution, or network boundaries.
