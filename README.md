# Agentic SDLC

A Java controller takes a written requirement through requirements, design, planning, development, QA, code review and documentation. Agents return structured answers; the controller validates them, stores artifacts, writes generated files into a separate Git workspace and runs their tests in a disposable Docker container.

The current deliverable is a thin end-to-end prototype, not a completed governed release pipeline. One real-model run generated a small Python service and passed its sandbox tests. Human approvals, repair gates and the brownfield scenario remain unfinished. See [limitations and trade-offs](docs/platform/limitations.md).

## Prerequisites

- Java 21, Git, Docker with Compose, and a running Docker daemon.
- Internet access for the initial Maven dependencies and sandbox image build.
- For a real agent run, access to the configured model API. The supplied `live` profile uses OpenRouter and reads its key from `OPENROUTER_API_KEY`.

The controller is a Spring Boot application using PostgreSQL, JPA, Flyway and LangGraph4j 1.8.27. The generated product currently uses Python, the standard library, SQLite and pytest. Python is supplied by the sandbox image; a host Python installation is not needed to verify generated code.

## Start the database and prepare the sandbox

From the repository root:

```sh
docker compose up -d
docker compose ps
docker build -t agent-sandbox-python docker/sandbox-python
```

Wait for PostgreSQL to report healthy. Compose starts PostgreSQL only, on host port `5433`, and keeps its data in a named volume. The Compose credentials are local development defaults, not a production configuration. The sandbox image installs pytest during image creation; generated tests run without network access.

## Start the controller

From `agent-controller/`, with `OPENROUTER_API_KEY` already set in the process environment:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=live
```

Do not store the key in properties files, scenario files or Git. The settings are in [application-live.properties](agent-controller/src/main/resources/application-live.properties). `OPENROUTER_MODEL` optionally overrides the configured model identifier; when changing models, also review the context window, answer limit and reasoning setting for compatibility.

Flyway applies database migrations at startup, and Hibernate validates the resulting schema. The controller listens on port `8080` by default:

- Health: `http://localhost:8080/actuator/health`
- Swagger: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI document for Postman: `http://localhost:8080/v3/api-docs`

Without the `live` profile, all stages default to dummy nodes. `./mvnw spring-boot:run` can demonstrate routing without a model, but does not generate or verify product code. A dummy run's success has this narrower meaning.

## Submit and inspect a real run

Keep the initial requirement small: development currently produces all files in one answer. In Postman, send `POST http://localhost:8080/v1/runs`, with `Content-Type: application/json` and this body:

```json
{
  "requirement": "Build a small Python link-shortening service using only the standard library, SQLite and pytest. Use at most 3 stories, no open questions, and two endpoints: POST /links accepts a JSON url and returns 201 with a code; GET /{code} redirects with 302 or returns 404 for an unknown code. Reject URLs outside http and https with 400. Put complete code and tests directly under apps/url-shortener so python -m pytest runs them offline."
}
```

This reduced input follows the successful manual trial described in the [engineering summary](docs/platform/engineering-summary.md). The broader scenario input, including analytics, is in [walking-skeleton.md](scenarios/walking-skeleton.md); the reduced trial does not prove those extra requirements.

The API returns `201`, an `id`, and a `Location` header. Background polling claims and executes the run automatically. Read `GET /v1/runs/{id}` until it reaches `SUCCEEDED` or `FAILED`. It shows stage progress, artifact summaries and, when available, the generated commit and verification counts. Failures include a fixed reason code and message.

Read a specific artifact with:

```text
GET /v1/runs/{id}/artifacts/{kind}/{version}
```

### Resolve requirements clarification

The requirements agent first returns at most four questions, with no assumptions to confirm, before the stories. Python, the standard library, SQLite and pytest are fixed execution context. When there are questions the run pauses at `WAITING_FOR_INPUT`; a run with no questions goes straight to the stories.

Read `GET /v1/runs/{id}/questions`. Items have unique `Q-*` IDs, the artifact version, `OPEN` or `ANSWERED` status and the saved reply. Submit an answer for every current question together to `POST /v1/runs/{id}/replies`:

```json
{
  "answers": [
    {"questionId": "Q-1", "answer": "Reuse the existing code for duplicate URLs."},
    {"questionId": "Q-2", "answer": "Yes."}
  ]
}
```

Use the actual IDs returned for your run. Every question needs one answer; answers must be nonblank and at most 5,000 characters. An accepted reply returns `202` with `{"runId":"...","message":"Answers submitted."}` and queues processing for the next scheduler poll. An invalid batch returns `400` without changing anything; a reply to an already answered batch returns `409`. After the reply the run goes straight to the stories, which carry the agent's own assumptions as plain text.

Waiting batches and answers survive a controller restart. This continuation happens before the downstream graph starts; it does not provide recovery of interrupted development or builds. Answering clarification also does not approve the resulting stories; story approval remains unfinished.

Use the kind and version returned by the run response. Artifact kinds include `requirements`, `design`, `plan`, `development`, `qa`, `review` and `documentation`. The planning stage produces kind `plan`. Unknown artifacts return `404`.

In the live profile, workspaces are created under `../../agentic-sdlc-workspaces/<run-id>`, relative to `agent-controller/`. Each workspace is its own fresh git repository on branch `main`, with the generated product at its root and one commit per change set; nothing is pushed anywhere. A change run (`kind` BROWNFIELD with a `baseRunId`) starts as a git clone of the base run's own workspace, so its history extends the base's; the base is never changed. Inspect the generated commit there using Git. Agents' other outputs are stored in PostgreSQL and read through the artifact API, not automatically exported as files.

`SUCCEEDED` in a live skeleton run means that the selected stages returned, the generated commit was tested successfully and the graph completed. It does not mean a person approved the work or that review findings were resolved. `impactAnalysis` remains `NOT_STARTED` on the greenfield route.

## Deliver a finished run into this repository

A run lives in its own repository under `agentic-sdlc-workspaces/<run-id>/`. To put its result into this repository for submission, from the repository root with a clean working tree:

```bash
scripts/deliver.sh <run-id>
```

The script:
1. fetches the run's whole commit history into this repository as the branch `run/<run-id>` (original authors, messages and order);
2. replaces `apps/url-shortener/` with the files of the run's last commit (pass a second argument to use another folder name);
3. saves the evidence under `runs/<run-id>/`: the run, its lineage, the latest version of every artifact and the metrics (these need the controller running at `http://localhost:8080`, or set `API`), and the commit list and history;
4. stages `apps/url-shortener/` and `runs/<run-id>/` and commits nothing, so you review and commit yourself.

A change run (`kind` BROWNFIELD) started from an earlier run is a clone of it, so its branch holds both runs' history. Deliver the latest run to get the final product. Publishing the branches is up to you.

## Verify the controller

With Java 21 and Docker available, from `agent-controller/`:

```sh
./mvnw verify
```

The normal suite uses scripted model answers and stub servers, never a live model. PostgreSQL integration tests use Testcontainers. Reports are written to `target/surefire-reports/` and `target/site/jacoco/index.html`. See [testing approach](docs/platform/testing.md) for evidence and current gaps.

Stop the controller with Ctrl+C and stop the database with `docker compose stop`. Existing run workspaces and database data remain available for inspection.

## Documentation

- [Architecture and control flow](docs/platform/architecture.md)
- [Testing approach and evidence](docs/platform/testing.md)
- [Limitations and trade-offs](docs/platform/limitations.md)
- [Engineering summary](docs/platform/engineering-summary.md)
- [Platform requirements](docs/platform/requirements/agent-pipeline.md)
- [Work history](docs/steps.md)
- [Architecture decisions](docs/adr/)

If startup fails, check database health and host port `5433`. If verification fails, check that Docker is running and the sandbox image exists. Inspect the run's failure reason and controller logs before retrying. A new API submission creates a new run; automatic recovery of interrupted running work is not implemented in this live skeleton.
