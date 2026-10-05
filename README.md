# Agentic SDLC

Describe what you want to build, answer the agents' questions, and review their design and implementation plan. The controller then generates code and tests, verifies them in a Docker sandbox, and produces QA, review and documentation artifacts.

The controller is a Java 21 Spring Boot application. The current generated product uses Python, the standard library, SQLite and pytest. Each run has its own Git branch and workspace. In the live profile, completed work is automatically merged into the clean controller checkout, putting the generated product under `apps/url-shortener/`.

## 1. Prepare your machine

You need:

- Java 21.
- Git 2.38 or newer.
- Docker with Compose, with the Docker daemon running.
- An OpenRouter API key for real agent runs.
- Internet access to download Maven dependencies, build the sandbox image and call the model.

Maven is supplied through the wrapper. Python and pytest are supplied by the sandbox image; you do not need host Python to run the controller's generated-code verification.

From the repository root, check the tools:

```sh
java -version
git --version
docker info
docker compose version
```

## 2. Start PostgreSQL and build the sandbox

From the repository root:

```sh
docker compose up -d
docker compose ps
docker build -t agent-sandbox-python docker/sandbox-python
```

Wait until PostgreSQL reports `healthy`. It listens on host port `5433` and stores its data in a named Docker volume. Compose starts the database only; the controller runs locally.

Build the sandbox image once, and rebuild it when its Dockerfile changes. The image contains the Python test tools. Generated tests run in a fresh container with no network access, as a non-root user, with resource and time limits. Product files are copied into the container; no host directory is mounted.

## 3. Start the controller

In the terminal where you will start the controller, set your model key:

```sh
export OPENROUTER_API_KEY='your-api-key'
cd agent-controller
./mvnw spring-boot:run -Dspring-boot.run.profiles=live
```

Keep this terminal running. The `live` profile enables the main agent stages and requires human approval of the design and implementation plan. Database migrations run automatically at startup.

The key is read from the environment. Keep it out of source files and Git. Model and workspace settings are in [application-live.properties](agent-controller/src/main/resources/application-live.properties). You can override the model with `OPENROUTER_MODEL`; review its context window, answer limit and reasoning settings when changing it.

## 4. Open the UI

Open **[http://localhost:8080/](http://localhost:8080/)**.

| Page | Address | Purpose |
| --- | --- | --- |
| Reviewer UI | [Open UI](http://localhost:8080/) | Start a run, answer questions, approve results and inspect artifacts |
| Metrics | [Open metrics](http://localhost:8080/#metrics) | View aggregate run, model-call and verification statistics |
| Swagger UI | [Open Swagger](http://localhost:8080/swagger-ui/index.html) | Explore and call the HTTP API |
| OpenAPI JSON | [Open API document](http://localhost:8080/v3/api-docs) | Import the API into Postman |
| Health | [Check health](http://localhost:8080/actuator/health) | Confirm the controller and database are available |

The reviewer UI is served by the controller. There is no separate frontend server or frontend build command.

## 5. Start your first run

On the start page, enter a small request in **Requirement**, then click **Start run**. For example:

> Build a small Python link-shortening service using the standard library, SQLite and pytest. POST /links accepts a JSON url and returns 201 with a code. GET /{code} redirects with 302, or returns 404 for an unknown code. Reject URLs outside http and https with 400. Include code and tests. Keep the scope to at most three stories.

Keep the first request small. Development currently generates the complete change in one answer rather than executing each planned task separately.

The controller creates the run and starts it through background polling. The UI follows its progress automatically. Its address contains the run ID, so you can bookmark it, reload it or share the address with someone who can access the same controller:

```text
http://localhost:8080/#run=<run-id>
```

**Recent runs** remembers runs in that browser. Save the run URL or ID if you want to reopen it from another browser.

### Answer clarification questions

If the agent needs decisions before writing stories, the run becomes `WAITING_FOR_INPUT`. The UI shows up to four questions together. Answer every question and submit the batch.

Processing then continues automatically with your answers. There is one requirements clarification batch; the resulting stories also list the agent's assumptions. Questions and answers are persisted in PostgreSQL.

### Review and approve the design and implementation plan

The live run pauses after the design and again after planning. Open the corresponding artifact, inspect it, and use the approval card:

- **Approve** continues to the next stage.
- **Reject** requires a comment and asks the same stage to revise its output with your feedback.

Enter your name so the decision is recorded. Rejections are bounded; repeatedly rejecting a stage eventually ends the run as failed. Answering clarification questions is separate from approving these artifacts.

### Follow development and verification

After planning approval, development returns code and tests. The controller validates the file paths, writes the files into the run's workspace, commits them and runs the tests in the sandbox.

A failing verification can trigger another bounded development attempt with the failing-test evidence. After development passes, QA, review and documentation run in parallel. The controller then exports the generated documents and evidence into the run's branch.

The UI shows stage progress, generated files, the tested commit and verification counts. Impact analysis is skipped for a new greenfield run, so its `NOT_STARTED` state is expected.

| Status | What to do |
| --- | --- |
| `CREATED` | Wait for the scheduler to pick up the run |
| `RUNNING` | Follow stage progress and results |
| `WAITING_FOR_INPUT` | Answer the questions or decide the pending approval |
| `SUCCEEDED` | Inspect the code, verification, review and documents |
| `FAILED` | Read the failure details; use Retry if another attempt is available |
| `STOPPED` | Resume if you want to continue; see the verification caveat below |

## 6. Read the artifacts and generated documents

### In the UI

Use the artifact links beside the stage timeline, or the **View** buttons in the conversation. On a narrow screen, open **Stages & artifacts**.

You can inspect the stories and criteria, design and diagrams, task plan, generated file contents, QA results, review findings and documentation. Generated code has copy controls. **View lineage** shows which artifact versions each result consumed and the recorded file-to-criterion links.

### Through the API

Read the run first to get its artifact kinds and versions:

```text
GET /v1/runs/<run-id>
GET /v1/runs/<run-id>/artifacts/<kind>/<version>
GET /v1/runs/<run-id>/lineage
```

Kinds include `requirements`, `design`, `plan`, `development`, `qa`, `review` and `documentation`. A change run may also have `impactAnalysis`. Use the returned version rather than assuming it is `1`; clarification, rejection and retries can produce additional versions.

For example, save one design artifact locally:

```sh
curl --fail http://localhost:8080/v1/runs/<run-id>/artifacts/design/<version> \
  -o design-artifact.json
```

Replace the angle-bracket placeholders before running commands.

### On disk

With the supplied live configuration, the workspace is next to this repository:

```text
<parent-directory>/
├── agentic-sdlc/
└── agentic-sdlc-workspaces/
    └── <run-id>/
        ├── apps/url-shortener/
        │   ├── generated Python modules and tests
        │   ├── README.md
        │   └── docs/
        └── runs/<run-id>/
            ├── artifacts/<kind>-v<version>.json
            └── lineage.json
```

The generated product README contains setup and usage instructions. Its `docs/` directory contains the readable requirements, design, task plan, QA and review documents, plus diagram sources under `docs/diagrams/`.

Documents and JSON evidence are exported after the final stages join. A failed run may have database artifacts without a completed document export. The export contains the latest version of each artifact kind; PostgreSQL retains the stored version history.

**The controller verifies the generated product but does not start its application server.** To try the product itself, inspect its generated README and follow its setup and usage instructions.

## 7. Inspect or take the generated work

Each run is a Git worktree on branch `run/<run-id>`. The live profile enables `workspace.auto-merge=true`: after verification, QA, review, documentation and document export finish, the controller merges this branch into the currently checked-out branch. A merge commit by `controller` preserves the generated commits and their authors. The product appears at `agentic-sdlc/apps/url-shortener/` and its evidence at `runs/<run-id>/`; the run then becomes SUCCEEDED. Existing design and plan approvals still happen earlier in the flow.

Keep the checkout clean while a run finishes. Tracked, staged or untracked local edits, an active Git operation, or a merge conflict block delivery. The generated branch and workspace are retained, and the run becomes FAILED with `DELIVERY_BLOCKED`. Commit your local work or resolve the conflicting Git changes, then use Retry. The existing first retry reruns the QA, review and documentation group before attempting delivery again. The controller never stashes, resets, pushes, switches branches or deletes worktrees. Git 2.38 or newer is required for the conflict preflight.

From the repository root, inspect a run's history and files:

```sh
git log --graph --oneline run/<run-id>
git diff HEAD...run/<run-id> -- apps/url-shortener/
git ls-tree -r --name-only run/<run-id> -- apps/url-shortener/ runs/<run-id>/
git show run/<run-id>:apps/url-shortener/README.md
```

The `development` author identifies generated-code commits; `controller` identifies the document and evidence export. The run response's tested commit can precede the later document-export commit.

Set `workspace.auto-merge=false` if you prefer manual delivery. For those runs, merge from a clean working tree after reviewing the result:

```sh
git merge --no-ff run/<run-id>
```

Pushing is a separate manual action. Automatic delivery applies to runs completing with the setting enabled; it does not backfill previously completed runs.

### Request a change to an existing result

After a successful run with a generated commit, enter your change in the bottom chat bar and send it. Alternatively, use **New run**, select **Change an existing run**, and choose a remembered run or paste its ID.

The controller creates a new `BROWNFIELD` run whose branch starts from the successful base run's branch. The existing files are supplied to the change agents and the generated product's whole test suite is run again. The base branch is retained.

The supplied live profile does not enable the impact-analysis agent. To include real impact analysis in a change run, start the controller with:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=live \
  -Dspring-boot.run.arguments="--agent.stages.impactAnalysis=agent"
```

## Retry, stop and resume

A failed run's **Retry** button queues it again within its attempt limit. The first retry resumes from the earliest unfinished stage; the next retry restarts from the beginning. Stored evidence remains available.

**Stop** and **Resume** controls have a backend implementation in the current working tree, but their acceptance tests and browser checks have not been executed and further work is deferred. Stop is cooperative: an in-flight model call may finish, and the run stops at a safe point. An active sandbox build is cancelled. The branch, workspace and stored evidence are retained. Resume returns to unfinished work or the same question/approval wait.

These controls do not provide automatic recovery after a controller process crash.

## Use the API instead of the UI

Swagger and Postman use the same operations as the reviewer page:

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/v1/runs` | Create a run from `{ "requirement": "..." }` |
| `GET` | `/v1/runs/{id}` | Read status, stages, artifacts and verification |
| `GET` | `/v1/runs/{id}/questions` | Read the clarification batch |
| `POST` | `/v1/runs/{id}/replies` | Submit `{ "answers": [{ "questionId": "Q-1", "answer": "..." }] }` for every question |
| `POST` | `/v1/runs/{id}/decisions` | Submit `{ "decision": "APPROVE", "approver": "Your name", "comment": "" }`; rejection uses `REJECT` and requires a comment |
| `GET` | `/v1/runs/{id}/artifacts/{kind}/{version}` | Read a versioned artifact |
| `GET` | `/v1/runs/{id}/lineage` | Read artifact and code lineage |
| `POST` | `/v1/runs/{id}/retry` | Retry a failed run, with no body |
| `POST` | `/v1/runs/{id}/stop` | Request a cooperative stop, with no body |
| `POST` | `/v1/runs/{id}/resume` | Resume a stopped run, with no body |
| `GET` | `/v1/metrics` | Read aggregate metrics |

Creating a run returns `201`, its ID and a `Location` header. Accepted replies, decisions and retry requests return `202`; continuation happens through background polling. Errors use `application/problem+json`. See Swagger for the full payloads and response codes.

## Run without a model

For a UI and routing demonstration, start from `agent-controller/` without the live profile:

```sh
./mvnw spring-boot:run
```

Stages default to dummy nodes. This needs PostgreSQL but no model key or generated-code sandbox run. A successful dummy run demonstrates routing, not generated code or passing product tests.

A replay model client also exists, but a ready-to-run recording package is not supplied. Replay is not the default startup mode.

## Verify the controller

From `agent-controller/`, with Java 21 and Docker available:

```sh
./mvnw verify
```

The normal suite uses scripted model responses and stub servers, not live model calls. Database integration tests use Testcontainers.

| Report | Location relative to `agent-controller/` |
| --- | --- |
| Test results | `target/surefire-reports/` |
| Controller unit coverage | `target/site/jacoco/index.html` |

Controller coverage is separate from coverage of the generated product. See [testing approach](docs/platform/testing.md) for the recorded evidence and gaps.

## Shutdown and troubleshooting

Stop the controller with **Ctrl+C**. From the repository root, stop PostgreSQL with:

```sh
docker compose stop
```

Database data, artifacts and run workspaces remain available. Finish or stop active work before shutting down; automatic recovery of a run interrupted by a process crash is not implemented.

| Problem | Check |
| --- | --- |
| Controller cannot connect to PostgreSQL | Run `docker compose ps`; confirm health and host port `5433` |
| UI is unavailable | Confirm the controller started and open `http://localhost:8080/` |
| Model calls fail | Check the key in the controller's environment, provider access and configured model |
| Verification fails before tests run | Check Docker is running and `agent-sandbox-python:latest` exists |
| Run waits indefinitely | Open its questions or approval card; a human wait requires your response |
| Run is marked failed | Read the failure reason and controller logs before retrying |
| Run remains RUNNING after a crash | There is no automatic startup reconciliation; inspect its stored state and workspace |

## Current limits and project documents

This is an end-to-end prototype. A live `SUCCEEDED` run has completed the selected stages and passed generated-code verification, with design and plan approvals enabled by the live profile. It does not certify that review findings were resolved, every acceptance criterion was tested, coverage met a release threshold or a person approved the final commit. QA and review artifacts currently report evidence without enforcing the complete release gate.

The UI and API are intended for a trusted local environment. Generated work remains subject to human review.

- [Architecture and control flow](docs/platform/architecture.md)
- [Testing approach and evidence](docs/platform/testing.md)
- [Limitations and trade-offs](docs/platform/limitations.md)
- [Engineering summary](docs/platform/engineering-summary.md)
- [Platform stories and statuses](docs/platform/requirements/agent-pipeline.md)
- [Work history](docs/steps.md)
- [Architecture decisions](docs/adr/)
