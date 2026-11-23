# AGENTS.md — testframe-plugin-spring (execution)

Spring Boot 4.2 + Java 25 + Maven wrapper, artifact `testframe-plugin-spring`. The **execution
plugin**: it runs tests and produces evidence. All code, comments and docs in English.

## Golden rules

- **It never edits a project.** No write to any project folder, ever: no encoder, no write store, no
  `.testframebuild/`, no card result persisted. Authoring belongs to the IDE
  (`modules/testframe-ide`). If a feature seems to need a write, it belongs there.
- **Every run is self-contained.** The caller (IDE, tour, CLI) resolves the sources for an
  environment and sends them in the request; the plugin does not guess a connection. An
  `environment` is mandatory.
- **Nothing leaves unredacted.** One redactor sits at the boundary and covers responses, NDJSON
  events, error messages, log lines and CLI reports.
- Read-only access to a project is validated like a write would be: see `format/ProjectDir`.

Specs — they win over this file: `docs/runner-protocol.md` (the `/api/v1` API, errors, timeouts,
redaction) and `docs/test-tour.md` (tour API, events, CLI). Format:
`modules/testframe-ide/docs/project-format.md`.

## API

`/api/v1` (runner protocol):

| Route | Purpose |
|---|---|
| `GET /health` | Liveness + capabilities + the fixed `engine/Limits` |
| `POST /run` | Run a frame or a whole project; `Accept: application/x-ndjson` streams events, otherwise one JSON body |
| `POST /run/card` | Run a single card (the editor's Run button) |
| `POST /assertions` | Evaluate jsonassert code without a run |

`/api/v1/tour` (stateless — the tour's state lives in the browser):

| Route | Purpose |
|---|---|
| `GET /projects` | Projects under `testframe.projects-dir` |
| `GET /projects/{project}/environments` | The project's declared environments |
| `GET /projects/{project}/frames` | Frames with their source references and missing-source problems |
| `GET /projects/{project}/frames/{frame}/inputs` | The frame's input (`rawvalue`) cards and their saved values |
| `POST /projects/{project}/frames/{frame}/run` | Run one frame as NDJSON (`frame-started`, `card-finished`, …) |
| `POST /runs/{runId}/cancel` | Cancel a run in progress |

`/tour` serves the static build of `modules/testframe-tour-ui` (the same screens the IDE opens).

Errors are one model (`api/ApiExceptionHandler`); `api/ApiResponses` builds every body.

## Structure

- `api/` — controllers (`HealthController`, `RunController`, `TourController`), `RunRegistry` (runs
  in flight, cancel by `runId`), `RunSlots` (at most `testframe.max-concurrent-runs`, else 429),
  `Ndjson` (one JSON object per line, flushed and **redacted per event**; a client that goes away
  cancels the run), `ApiFilter` (503 while shutting down, 401 without the configured bearer token,
  413 over `Limits.MAX_REQUEST_BYTES`; **no CORS headers**), `ApiExceptionHandler`, `ApiResponses`.
- `engine/` — the only place cards execute: `RunEngine` (steps in order, cards in order, first error
  stops, a false jsonassert `conditional` marks the rest of its step `skipped`), `Bindings`
  (`{{varName.path}}` against the variables published so far), `HttpCaller` (`java.net.http`),
  `MongoRunner` (the sync driver directly — **no Spring Data Mongo**, every connection comes from
  the resolved sources), `OpenApiSpec` (an OpenAPI/Swagger 2 document distilled to its API base and
  operations; fetched in memory per run, never cached to disk), `Redactor`, `RunControl` (cancel +
  deadlines), `RunListener`, `Limits`.
- `format/` — **read-only decoders** of the project format: `MdEnvelope`, `Names`, `CardDecoder`,
  `FrameDecoder` (+ `flow.md` order), `SourcesDecoder`, `SafeYaml`, `MongoCall`, `Catalog`,
  `CodeRules`, `JsonassertCode`, `ProjectDir`. They decode and validate; they never render or write.
  The IDE's `lib/format` is the format's owner — keep the two in sync (`bun run format:check` at the
  root is the parity gate).
- `model/` — wire records: `Card`, `CardResult` (+ `detail`), `Step`, `Operation`, `SourceConnection`.
- `project/` — `ProjectsRoot` (which folders are projects, name validation), `ProjectRuns`,
  `ProblemException`.
- `cli/Cli` — the headless entry point; runs **without a Spring context and without a web server**.
- `config/` — `PluginConfig`, `BindGuard` (refuses to start on a non-loopback address with no token).
- `Json` (the shared Jackson mapper), `PluginApplication` (dispatches `run …` to the CLI, otherwise
  starts HTTP), `PluginInfo`.

## Security

- `engine/Redactor` is the single redactor. Each secret is replaced by `***` in its raw,
  JSON-escaped and URL-encoded forms, longest first; secrets shorter than 4 characters are not
  substring-matched. Structural rules apply regardless of the secret list: URI user-info becomes
  `***@`, and `authorization`, `proxy-authorization`, `cookie` and `set-cookie` values (any object
  key with that name, case-insensitively) become `***`. Secrets are the values whose winning layer
  was a `.private.yaml` file (`SourcesDecoder.resolve`).
- `format/ProjectDir` validates every path before reading: character whitelist, `..` rejected, the
  resolved path must stay under the project's `testframe/`, symbolic links are never followed, only
  `.md` under `frames/` and the `sources*.yaml` layers at the `testframe/` root, up to
  `MAX_FILE_BYTES`. Cards' `.private.md` notes and `.testframebuild/` are never read.
- `format/SafeYaml` is a restricted loader: maps, lists and scalars only, YAML 1.2 core schema;
  anchors, aliases, explicit tags, duplicate keys, complex keys and multiple documents are errors.
- Bind to `127.0.0.1` unless `testframe.token` is set (`BindGuard`); with a token, every
  `/api/v1/**` request needs `Authorization: Bearer {token}`.

Any change to a path rule, the redactor or a runner is security-sensitive: use the `guardian`
thread, and add a test that would fail if the protection were removed.

## Configuration

| Key | Env | Meaning |
|---|---|---|
| `server.address` | — | `127.0.0.1` by default; a non-loopback value requires `testframe.token` |
| `server.port` | — | **Never in properties** — the caller passes `--server.port` (resolved from the root `package.json` `config.runnerPort`) |
| `testframe.projects-dir` | `TESTFRAME_PROJECTS_DIR` | Projects the tour API serves; empty = no `tour` capability |
| `testframe.token` | `TESTFRAME_TOKEN` | Bearer token; empty = loopback only |
| `testframe.max-concurrent-runs` | — | Concurrent runs before 429 (default 4) |

Fixed limits (`engine/Limits`, served by `GET /health`): card timeout 100 ms – 120 s, run deadline
600 s, request body 10 MB, response body 5 MB, 1000 mongo documents.

## CLI

```bash
java -jar target/testframe-plugin-spring.jar run <projectDir> --env <env> [--frames a,b] \
     [--out report.json] [--out junit.xml]
```

`--out` format follows the extension: `.json` = a redacted JSON report, `.xml` = JUnit (one
`testsuite` per frame, one `testcase` per card). Exit codes: 0 passed, 1 failed, 2 bad invocation or
missing sources, 3 interrupted, 4 internal error.

## Commands

```bash
./mvnw -q compile     # fast build
./mvnw -q test        # suite
./mvnw -q spring-boot:run --spring-boot.run.arguments=--server.port=18080
```

Needs `io.github.jsonassert:jsonassert-java` in the local Maven repo first:
`cd modules/jsonassert && bun run install:java`.

Manual check: start it on a scratch port, hit `/api/v1/health` and the tour routes, then **stop the
process and free the port**. From the root, `bun run smoke` and `bun run tour <project> --env <env>`
exercise it with the stack up.

## Gotchas

- Boot 4 ships **Jackson 3**: package `tools.jackson.*`; `com.fasterxml.jackson.databind` does not
  exist. `@JsonProperty` still comes from `com.fasterxml.jackson.annotation`.
- Starters were renamed: `spring-boot-starter-webmvc` / `spring-boot-starter-webmvc-test`.
- No Lombok here: plain records and constructors.
- A mongo card body is **one JSON array of the driver function's positional arguments** (per subtype
  — see `format/MongoCall`'s javadoc), not labeled sections and not a JS script. Writes
  (`deleteOne`/`deleteMany`/`updateOne`/`updateMany`/`insertOne`/`insertMany`) are allowed by the
  format; the tour warns before running a frame that has one.
