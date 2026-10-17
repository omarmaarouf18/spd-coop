# docs/worklog/ — task worklogs

One worklog per task: `docs/worklog/<TASK-ID>.md`. See `docs/coop/WORKFLOW.md` §4.

On an agent branch (`agent/<TASK-ID>-<slug>`), you do NOT edit `AI_CONTEXT.md`, `docs/coop/decisions.md` or `docs/changelog/**`. You create or append this worklog, and the owner folds it into the shared docs after the merge.

Rules:
- **No full 40-character commit SHAs** anywhere on agent branches. Short SHAs belong in the chat report only.
- Record what was verified **and what was NOT verified**, plus every open question. Never guess: write the question down and stop.
- One file per task ID, even across several sessions. Append, don't rewrite.

## Template

````markdown
# Worklog: <TASK-ID> — <short title>

## Task and scope
- Task ID: <TASK-ID>
- Lane (allowed paths): <paths>
- Hot paths granted: <paths or "none">
- Branch: agent/<TASK-ID>-<slug>, from dev (short SHA ok)

## Decisions taken
- <one per line, or "none">

## Changes per commit (subject only — NO SHAs)
1. `<TASK-ID>: <subject>`

## Verification
- `make check` — <PASS/FAIL> (scope printed: <...>)
- <each extra command actually run + result>
- Single-player impact: <none / what changed and why it's gated>
- Determinism impact: <none / goldens unchanged / goldens intentionally changed because ...>

## Unverified
- <claims not verified + reason, or "none">

## Follow-ups / out-of-lane findings
- <or "none">

## Open questions
- <for the owner, or "none">
````
