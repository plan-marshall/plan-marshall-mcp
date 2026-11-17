# Plan: move the daemon-side specifications into this repository

Operator decision, 2026-11-17: "The spec should be completely in the implementation module. Here (documentation-repo)
only the requirements should remain and point to the corresponding implementation / documentation."

Every specification, together with its implementation watch document, lives in the repository whose code implements
it. `pm-mcp-core` already works this way, with `doc/Specification.adoc`, `doc/specification/`,
`doc/ImplementationWatch.adoc`, `doc/implementation-watch/` and `doc/Traceability.adoc`, and ADR 007 there.
`plan-marshall-documentation` keeps the requirements, which point into the code repositories. It also keeps the use
cases, the roadmap, `later-improvements`, the open questions and the articles. The specifications of `pm-mcp-parent`
stay there as well: `module-structure.adoc` with its watch document, and `implementation-watch/cross-cutting.adoc`.

An implemented section stays where it is, condensed and pointing to the code (ADR 027 of `pm-mcp-core`). It is not
deleted.

This plan covers the half of the move that belongs here. The client half (`pm-api`, `pm-relay`, `pm-operator`,
`pm-exec`, `pm-mock-runtime`, `pm-web-app`) is a task in the inbox of the `pm-mcp-clients` epic `ui-improvements`
(message `doc-review-2026-11-17-001`). Both halves are cut from the same documents, so the two runs have to
coordinate (see Order).

## Precondition

Start only after the review branch `docs/review-2026-11-17` has landed: `pm-mcp-core` first, then
`plan-marshall-documentation`. That review edits the documents this plan moves.

## What moves here

The rule: the Modules table of `plan-marshall-documentation/doc/specification/module-structure.adoc` decides, through
the specification documents it lists for `pm-mcp-server`, `pm-e2e` and `pm-conformance`. The reading at the time of
the decision, for the run to verify:

- `runtime-model.adoc` (the index) with `runtime-model/01-processes-and-transport` and `04-sessions-and-workers`. The
  client parts (`03-relay`, `05-mock-runtime`, `06-event-catalogue`) go to `pm-mcp-clients`.
- `cli-and-security/03-web-server` and the server side of `02-operator-channel`, meaning what the runtime enforces.
  `01-cli-and-installer` and the client side of `02` go to `pm-mcp-clients`.
- `mcp-tools.adoc` with all four parts.
- `evaluation.adoc`, the reference specification of the measurements. It sits beside the corpus
  `test/model/verification/`.
- The watch items of `implementation-watch/runtime-model.adoc`, `cli-and-security.adoc` and `mcp-tools.adoc` whose
  anchors move here. Watch ids are kept, never renumbered.
- `harness-integration` goes here only if the run finds that the bundle content side belongs with the model-facing
  content of this repository. Otherwise it goes to `pm-mcp-clients`.

A part that both repositories implement is split at section boundaries. Each section ends up with exactly one home,
and no section is left without one.

## Tasks

1. Baseline: run `plan-marshall-documentation/.claude/skills/doc-review/scripts/trace.py` and `--with-plans` on
   `main` of all checkouts, and save the output outside the repositories.
2. Agree the section-level cut with the `pm-mcp-clients` run, as one table of sections and their target repository,
   before either side moves anything.
3. Create the documentation tree here: `doc/Specification.adoc`, `doc/specification/`, `doc/ImplementationWatch.adoc`,
   `doc/implementation-watch/` and `doc/Traceability.adoc`, shaped like those of `pm-mcp-core`. Add an ADR (the first
   one of this repository, `doc/adr/`) that records the home of the specifications and the condense-and-point rule.
4. Move the content mechanically. Keep every anchor, and change no wording. Rewrite the links:
   - inside this repository: relative links;
   - into `plan-marshall-documentation`: relative sibling paths (`../../plan-marshall-documentation/doc/...`, with the
     levels counted from the file);
   - into `pm-mcp-core` and `pm-mcp-clients`: relative sibling paths as well;
   - from `plan-marshall-documentation` and the other repositories into this one: absolute
     `https://github.com/plan-marshall/plan-marshall-mcp/blob/main/doc/...`.
5. Move into `doc/Traceability.adoc` here the `Implementation:` / `Verified by:` / `Documentation:` lines of the
   requirements this repository implements (requirement modules of `plan-marshall-documentation`), where they exist.
6. In `plan-marshall-documentation`, in a pull request beside this one:
   - Remove the moved documents and their rows in `Specification.adoc` and `ImplementationWatch.adoc`, and point to
     this repository's index.
   - Retarget every link to a moved anchor.
   - Update `CLAUDE.md`, `doc/developer/project-setup.adoc`, and the project skills `traced-implementation`,
     `doc-review` and `ingest-ideas` for the new home.
   - Extend `.claude/skills/doc-review/scripts/trace.py` so that links, anchors, watch backlinks, traceability and
     index counts resolve against the `plan-marshall-mcp` checkout beside it, as they do for `pm-mcp-core`.
7. Update `CLAUDE.md` of this repository: the specifications of the daemon now live here.
8. Content check: every line of every moved document is found again in its new home. Link lines are compared after
   the link is rewritten.
9. Run `trace.py`, plain and with `--with-plans`. It must report `BROKEN LINKS 0`, no undefined requirement or watch
   ids, and watch counts that match. Compared with the baseline there must be nothing new except the moved paths.

## Order

- The `pm-mcp-clients` half and this half either run one after the other, or share the docs pull request. If they run
  one after the other, the second run starts from the first one's merged state, and `trace.py` is green after each.
- Plans in flight that edit the moved documents either land before the move or are rebased onto it.

## Done when

- The daemon-side specifications, their watch items and the traceability of the requirements they implement are in
  this repository, and no copy is left in `plan-marshall-documentation`.
- `trace.py` is green as stated in task 9, on the final commits of both pull requests.
- This plan file is deleted in the pull request that completes the move.
