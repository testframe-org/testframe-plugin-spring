# testframe-plugin-spring

The **execution** half of TestFrame: a Spring Boot service that runs test frames and produces the
evidence of a run — HTTP calls, mongo calls, assertions, timings, and the redacted report of it all.

It is deliberately narrow. The plugin **never edits a project**: it has no encoder, no write store
and no generated files. Authoring — frames, cards, sources, environments — belongs to the IDE
(`modules/testframe-ide`), which owns the project format. The plugin only reads a project (for
tours and headless runs) and only ever runs what a caller asks it to.

Every run is also **self-contained**: the caller resolves the sources for an environment and sends
them in the request, so the plugin never has to guess a connection. And nothing leaves it
unredacted — one redactor sits at the boundary and covers responses, streamed events, error
messages, log lines and CLI reports.

## Build and run

Java 25 and the bundled Maven wrapper. The build resolves `io.github.jsonassert:jsonassert-java`
from your local Maven repository, so install it once first:

```bash
cd ../jsonassert && bun run install:java
cd ../testframe-plugin-spring

./mvnw -q compile     # fast build
./mvnw -q test        # the suite
./mvnw package        # target/testframe-plugin-spring.jar
```

Run it directly (the port is always an argument — see [Configuration](#configuration)):

```bash
./mvnw spring-boot:run --spring-boot.run.arguments=--server.port=<port>
java -jar target/testframe-plugin-spring.jar --server.port=<port>
```

From the repository root, `bun run dev <project>` starts the plugin next to the IDE and
`bun run tour <project> --env <env>` drives the headless CLI; both resolve the port from the root
`package.json` > `config.runnerPort` for you.

## The API at a glance

`/api/v1` — the runner protocol, called by the IDE. Requests are self-contained.

| Route | Purpose |
|---|---|
| `GET /health` | Liveness, protocol version, runnable and simulated card types, fixed limits |
| `POST /run` | Run a frame; `Accept: application/x-ndjson` streams events, otherwise one JSON body |
| `POST /run/card` | Run a single card (the editor's Run button) |
| `POST /assertions` | Evaluate one jsonassert assertion without a run |

`/api/v1/tour` — the stateless tour API. It reads a project from `testframe.projects-dir` and
resolves the sources itself; the tour's own state lives in the browser, never on disk.

| Route | Purpose |
|---|---|
| `GET /projects` | Projects under `testframe.projects-dir` |
| `GET /projects/{project}/environments` | Declared environments and their configured sources |
| `GET /projects/{project}/frames` | Frames, their source references and load problems |
| `GET /projects/{project}/frames/{frame}/inputs` | The frame's input cards and saved values |
| `POST /projects/{project}/frames/{frame}/run` | Run one frame as an NDJSON event stream |
| `POST /runs/{runId}/cancel` | Cancel a run in progress |

`/tour` serves the static build of `modules/testframe-tour-ui` — the same screens the IDE opens.

These tables are an orientation, not the contract. Request and response shapes, error statuses,
timeouts and the redaction rules are specified in
[`docs/runner-protocol.md`](../../docs/runner-protocol.md) and
[`docs/test-tour.md`](../../docs/test-tour.md).

## Configuration

| Key | Meaning |
|---|---|
| `server.port` | **Never in a properties file** — the caller passes `--server.port`, resolved from the root `package.json` > `config.runnerPort` |
| `server.address` | `127.0.0.1` by default; a non-loopback address requires `testframe.token` |
| `testframe.projects-dir` | Projects the tour API serves. Empty means no `tour` capability |
| `testframe.token` | Bearer token for `/api/v1/**`. Empty means loopback-only |
| `testframe.max-concurrent-runs` | Concurrent runs before the API answers `429` (default 4) |

`TESTFRAME_PROJECTS_DIR` and `TESTFRAME_TOKEN` are the environment-variable forms of the two
`testframe.*` values that have them. Card timeouts, the run deadline and the body-size limits are
fixed in `engine/Limits` and published by `GET /health`. See [`AGENTS.md`](AGENTS.md) for the full
table and the security rules around binding and redaction.

## Headless CLI

The same engine, without a web server and without a Spring context:

```bash
java -jar target/testframe-plugin-spring.jar run <projectDir> --env <env> \
     [--frames a,b] [--out report.json] [--out junit.xml]
```

`--out` is repeatable and the format follows the extension: `.json` writes a redacted report,
`.xml` writes JUnit (one `testsuite` per frame, one `testcase` per card). Nothing is written inside
the project. Exit codes: `0` passed, `1` failed, `2` invalid invocation or project,
`3` interrupted, `4` internal error — details in
[`docs/test-tour.md`](../../docs/test-tour.md), "Headless CLI".

## Where to read more

| Document | Read it for |
|---|---|
| [`AGENTS.md`](AGENTS.md) | This module's structure, golden rules, security and gotchas (Boot 4 / Jackson 3) |
| [`docs/runner-protocol.md`](../../docs/runner-protocol.md) | The `/api/v1` contract: run shape, `CardResult.detail`, errors, timeouts, redaction |
| [`docs/test-tour.md`](../../docs/test-tour.md) | Tour screens, the tour API, NDJSON events, the PDF report and the CLI |
| [`modules/testframe-ide/docs/project-format.md`](../testframe-ide/docs/project-format.md) | The project format the read-only decoders implement |
