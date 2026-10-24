# plan-marshall-mcp

<img align="right" width="300" src="https://raw.githubusercontent.com/plan-marshall/plan-marshall-documentation/main/doc/resources/planmarshall.png" alt="Plan Marshall">

## Status

[![Java CI with Maven](https://github.com/plan-marshall/plan-marshall-mcp/actions/workflows/maven.yml/badge.svg)](https://github.com/plan-marshall/plan-marshall-mcp/actions/workflows/maven.yml)
[![License: proprietary](https://img.shields.io/badge/license-proprietary-lightgrey.svg)](LICENSE.md)

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=plan-marshall_plan-marshall-mcp&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=plan-marshall_plan-marshall-mcp)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=plan-marshall_plan-marshall-mcp&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=plan-marshall_plan-marshall-mcp)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=plan-marshall_plan-marshall-mcp&metric=coverage)](https://sonarcloud.io/summary/new_code?id=plan-marshall_plan-marshall-mcp)


## What is it?

**plan-marshall-mcp** (PM-MCP) is a local MCP server that takes over the process logic
[plan-marshall](https://github.com/cuioss/plan-marshall) implements today as a corpus of filesystem
skills plus locally executed Python scripts. The client asks the server for the state of a plan;
the answer is a representation of that state together with the valid next transitions as links
(hypermedia-driven workflow). The model follows links and contributes judgment only; the server
owns the state machine.

The normative requirements are defined in [Requirements](https://github.com/plan-marshall/plan-marshall-documentation/blob/main/doc/Requirements.adoc), the technical
implementation blueprints in [Specification](https://github.com/plan-marshall/plan-marshall-documentation/blob/main/doc/Specification.adoc), and the delivery order in the
[Roadmap](https://github.com/plan-marshall/plan-marshall-documentation/blob/main/doc/roadmap.adoc). All documents live in the repository
[plan-marshall-documentation](https://github.com/plan-marshall/plan-marshall-documentation). How the implemented
system is built is described in the [Developer Guide](https://github.com/plan-marshall/plan-marshall-documentation/blob/main/doc/DeveloperGuide.adoc).

> [!NOTE]
> The project is at its beginning: roadmap Milestone 0 (verification first) is complete. Each verified technique
> exists in its target module in a first, minimal form; the workflow engine and the job runtime are not built
> yet. The measurements are recorded in the [Evaluation Reference](https://github.com/plan-marshall/plan-marshall-documentation/blob/main/doc/specification/evaluation.adoc).

## Modules

`pm-mcp-server` (the Quarkus daemon `pm-mcpd`) and the end-to-end tests `pm-e2e`. The plain-Java engine
(`pm-core`, `pm-runtime`, the provider modules) lives in
[pm-mcp-core](https://github.com/plan-marshall/pm-mcp-core). The client contract `pm-api`, the job launcher
`pm-exec`, the STDIO relay `pm-mcp` and the operator CLI `pm-operator` live in
[pm-mcp-clients](https://github.com/plan-marshall/pm-mcp-clients), and the parent POM in
[pm-mcp-parent](https://github.com/plan-marshall/pm-mcp-parent). The listing of the modules, their dependencies, and
the specification each implements is the
[Module Structure Specification](https://github.com/plan-marshall/plan-marshall-documentation/blob/main/doc/specification/module-structure.adoc).

## Technology

Java 25, Quarkus (via `de.cuioss:cui-quarkus-parent`),
[Quarkus MCP Server](https://github.com/quarkiverse/quarkus-mcp-server),
[cui-http](https://github.com/cuioss/cui-http), picocli, and GraalVM native image for the four binaries.
See [Technology Requirements](https://github.com/plan-marshall/plan-marshall-documentation/blob/main/doc/requirements/09-technology.adoc).

## Build

```bash
# Build and unit tests
./mvnw clean verify

# Integration tests against the packaged daemon and the client JARs
./mvnw clean verify -Pintegration-tests

# Native binaries and the integration tests against them (GRAALVM_HOME and JAVA_HOME point to GraalVM 25)
./mvnw clean verify -Pnative,integration-tests

# Pre-commit: license headers and OpenRewrite recipes - review and commit the resulting diff
./mvnw -Ppre-commit clean verify -DskipTests
```

The daemon listens only on a Unix domain socket below `PM_MCP_BASE` (default `~/.plan-marshall-mcp`); the clients
start it on demand:

```bash
pm-operator runtime start
pm-operator status
```

## Web app

The operator web app is built in the [`pm-mcp-clients`](https://github.com/plan-marshall/pm-mcp-clients)
repository (`pm-web-app`) and packaged as a resource JAR: the SvelteKit static build lands below
`META-INF/resources`. `pm-mcp-server` depends on that artifact and serves the tree on the web listener
(`PUT /api/v1/web`), same-origin beside `/api/v1/`:

| Request | Answer |
|---|---|
| `GET /`, an extensionless client route, or an `Accept: text/html` navigation | `index.html`, `Cache-Control: no-cache` |
| `GET /_app/immutable/...` | the content-hashed asset, immutable caching |
| another existing file | the file with the content type of its extension |
| a missing asset (`*.js`, `*.css`, ...) | `404`, never the app shell |
| `/api/v1/**`, `/mcp`, other `/api/**` | unchanged: the runtime API, `404`, `404` |
| a build without the resource JAR | the placeholder page, so the daemon still starts |

The dependency runs from the clients to the server: publish/install `pm-web-app` in `pm-mcp-clients` before
building this repository, because the server resolves its SNAPSHOT from the organisation registry. The native
binary carries the tree through `quarkus.native.resources.includes=META-INF/resources/**`; `WebAppIT`
(`-Pintegration-tests`) proves the packaged daemon serves the real app, its assets and a client route.

> [!NOTE]
> The web listener sends its strict CSP (`default-src 'self'; script-src 'self'; ...`) on every response. The
> current SvelteKit `index.html` boots through an inline `<script>` and carries an inline `style` attribute,
> which that policy blocks in a browser. Serving the shell is proven by `WebAppIT`; browser rendering is not.
> A follow-up addresses the CSP for the app shell (hash/nonce or a shell without inline script/style); this
> change does not weaken the security headers.

## Credentials

All secrets (GPG, Sonatype, Sonar, Pages, Release App) are managed at the
[cuioss organization level](https://github.com/cuioss/cuioss-organization) and passed explicitly to the
reusable workflows. No repo-level secrets need to be configured.

## License

plan-marshall-mcp is proprietary software; all rights reserved. Viewing this repository grants no right to use,
copy, modify or distribute the code. Versions up to the tag `fsl-final` (among them release 0.1.0) were published
under FSL-1.1-ALv2. See [LICENSE.md](LICENSE.md).

This repository does not accept external contributions ([CONTRIBUTING.md](CONTRIBUTING.md)).
