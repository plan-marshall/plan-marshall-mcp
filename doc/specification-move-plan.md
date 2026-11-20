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
`pm-exec`, `pm-mock-runtime`, `pm-web-app`) has landed: `pm-mcp-clients` holds its specifications, watch and
traceability, and its ADR 001 (`doc/adr/001-specifications-of-the-clients.adoc` there) records which sections it took.
This half starts from that state.

## Precondition

Start only after the open review branch of the documents (`docs/review-2026-11-20`) has landed in every repository it
touches. That review edits the documents this plan moves.

## What moves here

The rule (operator decision): every section of a specification and every watch item lives in the repository whose
code implements it. A section two repositories implement is split at a heading, never inside a paragraph; a section
both implement in one statement, with no heading between the two halves, stays where it is and is named as shared
below and in ADR 001 of `pm-mcp-clients` (`doc/adr/001-specifications-of-the-clients.adoc`). Moved text keeps its
wording, its heading and its anchor; watch identifiers are never renumbered. Implementing modules, by repository:
`pm-mcp-server`, `pm-e2e` and `pm-conformance` are this repository's; `pm-core`, `pm-workflow`, `pm-runtime` and the
provider modules are `pm-mcp-core`'s; `pm-api`, `pm-exec`, `pm-relay`, `pm-operator`, `pm-mock-runtime`, `pm-web-app`
and `pm-web-e2e` are `pm-mcp-clients`'. The run verifies each row against the code before it moves the section.

### The home table

Sections already cut by implementing repository (they stay where the table says; the move takes them as they are):

| Section | Implemented by | Home |
|---|---|---|
| `job-runtime/01#runtime-loss`; `job-runtime/02` `#abi-floor`, `#landlock-scopes`, `#seccomp-filter`, `#job-without-network`, `#macos-confinement`; `PM-WATCH-JOB-41` to `-44` | `pm-exec` | `pm-mcp-clients` (`doc/specification/job-runtime/`) |
| `job-runtime/01` process group and launch report, termination step 3a; `job-runtime/02` Linux filesystem confinement with its sets and socket list, worker read set, egress proxy, probe jobs, confinement self-check | `pm-runtime` and `pm-exec` | `pm-mcp-core`, shared |
| `runtime-model/01#client-text-hidden-characters` (the hidden-character rule of the encoder), `PM-WATCH-RUN-18` | `pm-api` | `pm-mcp-clients` (`runtime-model/06-event-catalogue.adoc`) |
| `runtime-model/03-relay` Harness Protocol Forms, the relay's half | `pm-relay` | `pm-mcp-clients` |
| Harness Protocol Forms at the Runtime (`runtime-model/01#runtime-protocol-forms`), `PM-WATCH-RUN-16` | `pm-mcp-server` | moves here with part 01 |
| `cli-and-security/02-operator-channel#operator-channel-isolation` | `pm-operator`, `pm-web-app` and `pm-mcp-server` (the operator endpoints) | `pm-mcp-clients`, shared |
| `hypermedia-format/01` Operator Representation, Operator Links, Texts of the Runtime | `pm-api` (the records) and the runtime that builds the representation and validates every act | `pm-mcp-core`, shared |
| `harness-profiles/01`: the values of the shipped profiles, stated in the blocks of the fields the profile validator checks | `pm-api` (the profile documents) and `pm-core` (the validator) | `pm-mcp-core`, shared |

Sections of `plan-marshall-documentation` the move cuts, with the repository each goes to:

| Section | Implemented by | Goes to |
|---|---|---|
| `runtime-model.adoc` (the index; `Overview`, `Traceability`, `Status`, the daemon's `Modules` rows) | — | here |
| `runtime-model/01`: terminology, process topology, responsibilities, transport endpoint with the web listeners, job token, harness protocol forms at the runtime, local API with its endpoints and error codes, start modes, environment, the resources of the operator's clients | `pm-mcp-server` | here |
| `runtime-model/01`: singleton and discovery, runtime token | `pm-runtime` (lock, record, token file), `pm-mcp-server` (socket, authentication), `pm-api` (the client's check) | here, shared |
| `runtime-model/04-sessions-and-workers` | `pm-mcp-server` | here |
| `cli-and-security/03-web-server`: listeners, request validation, API access, credentials in the web app, authentication seam, threat notes | `pm-mcp-server` | here |
| `cli-and-security/03-web-server`: pairing protocol, paired devices | `pm-mcp-server` (endpoints), `pm-runtime` (the device store, `WebTls`), `pm-operator` and `pm-web-app` (issue, show, redeem) | here, shared |
| `mcp-tools/01`: surface design and architectural constraints, worker surfaces | `pm-mcp-server` | here |
| `mcp-tools/01`: starting a scope without a creation tool (`#bootstrap-entries`, `#bootstrap-commit-recheck`) | `pm-workflow` | `pm-mcp-core` |
| `mcp-tools/01`: `pm_state`, `pm_do`, `pm_wait`, worker connections (`#job-bound-connections`) | `pm-mcp-server` (tool schemas, binding) and `pm-workflow` (semantics) | here, shared unless the run finds a heading between the two |
| `mcp-tools/02`: `pm_build` with its output contract | `pm-mcp-server` (tool) and `pm-runtime` (the build job) | here, shared |
| `mcp-tools/02`: `pm_lsp`; Warm LSP Pool Lifecycle | `pm-mcp-server` (tool); `pm-runtime` (the pool) | `pm_lsp` here; the pool lifecycle heading to `pm-mcp-core` |
| `mcp-tools/02`: selective upstream MCP proxy, skill architecture, skill tools, skills extension, skill rule checker, skill extensions, no script execution | `pm-mcp-server` (verify the skill rule checker against the code) | here |
| `mcp-tools/03-resource-uris` | `pm-mcp-server` | here |
| `mcp-tools/04`: classification matrix, rejected transition, rejection reasons, error envelope | `pm-mcp-server` | here |
| `mcp-tools/04#relay-transport-codes` (`runtime_unavailable`, answered by the relay) | `pm-relay` | `pm-mcp-clients` |
| `mcp-tools/04`: protocol and STDIO testing | `pm-mcp-server`, `pm-e2e` (daemon and release layers); `pm-relay`, `pm-operator` (their integration layers) | here, shared |
| `harness-integration/02-bundle-manifest#bundle-content` | the model-facing content of this repository | here |
| `evaluation.adoc` | the corpus `test/model/verification/` | here |
| The watch items of `implementation-watch/runtime-model.adoc`, `cli-and-security.adoc` and `mcp-tools.adoc` whose anchors move here | — | here, identifiers kept |

## Tasks

1. Baseline: run `plan-marshall-documentation/.claude/skills/doc-review/scripts/trace.py` and `--with-plans` on
   `main` of all checkouts, and save the output outside the repositories.
2. Verify every row of the home table above against the code and correct a row the code contradicts; a section
   whose two halves have no heading between them stays where it is and is named as shared.
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

- The sections that leave `pm-mcp-clients` or go to `pm-mcp-core` move in a pull request of that repository beside
  the ones here and in `plan-marshall-documentation`; `trace.py` is green on the final commit of each.
- Plans in flight that edit the moved documents either land before the move or are rebased onto it.

## Done when

- The daemon-side specifications, their watch items and the traceability of the requirements they implement are in
  this repository, and no copy is left in `plan-marshall-documentation`.
- `trace.py` is green as stated in task 9, on the final commits of both pull requests.
- This plan file is deleted in the pull request that completes the move.
