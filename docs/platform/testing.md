# Testing approach

There are two kinds of evidence, and neither replaces the other. **Controller tests** prove the platform's own behaviour: orchestration, persistence, guardrails and the Git and Docker handling. **Product tests** are the tests the agents generate for a particular run. They run in the sandbox for that run and decide whether the run goes on. This document is about the controller tests, and about how the platform's evidence is produced and what is missing from it.

## Principles

- **Plain code decides pass or fail.** A model never does. The controller reads the test report of the generated code. The build decides whether a controller change passes.
- **The normal build never calls a model.** Tests use scripted models, in-process stub servers, a fake Docker port and temporary Git repositories. This makes the suite repeatable and free. It does not show the quality of a current live model.
- **Light tests, with a coverage gate** ([ADR 0008](../adr/0008-keep-tests-light-and-gate-coverage-at-85-percent-for-now.md)). One test per acceptance criterion on the basic behaviour, plus the obvious failure the criterion names. No guard and no test that only exists to cover a branch. The target stays 100%. The build gate is 85% for lines and for branches until a coverage sweep restores 95%. Every gap is named with its reason.
- **A test names the criteria it proves.** The `@DisplayName` starts with the criterion ids (for example `AC-AGT-65.4`). The functional coverage report joins criteria to tests by that text.

## Test layers

| Layer | How it is built | Examples | What it proves |
| --- | --- | --- | --- |
| Unit tests | JUnit 5 and AssertJ, no Spring | `ChangeSetTest`, `ChangeSetCheckTest`, `OutputValidatorTest`, `PromptBuilderTest`, `JunitReportParserTest`, `RoleCatalogTest`, `RunResumeTest`, `MarkdownRendererTest` | Rules of single classes: path and deletion checks, answer validation and repair, prompt sizing, report parsing, start-point choice |
| Model client tests | An in-process HTTP stub server | `OllamaModelClientTest`, `HostedModelClientTest`, `OpenAiCompatibleModelClientTest`, `LiveModelClientTest` | Request and response mapping per API style, typed failures, call slots, and that no key, prompt or answer is logged. Retry, fallback and repair are tested with a scripted model (`StructuredAnswerRetryTest`, `StructuredAnswerServiceTest`) |
| Integration tests on PostgreSQL | Testcontainers with `postgres:17`, the version in Compose. They do not need the developer's Compose database | `RunPersistenceTest`, `RunMigrationTest`, `ArtifactStoreTest`, `ModelCallRecordsTest`, `ModelCallBudgetConcurrencyTest`, `RequirementsClarificationTest`, the scheduler tests | Migrations, versioned artifacts, call records, the budget under concurrent reservations, claiming under a row lock, and a fresh application instance reading the saved clarification |
| Full-stack tests | `@SpringBootTest` with MockMvc and a real database | `RunFullStackTest`, `ApprovalFullStackTest`, `RunRetryFullStackTest`, `RunStopFullStackTest`, `RunBaseFullStackTest`, `RunLineageFullStackTest`, `MetricsFullStackTest`, `ApiDocumentTest`, `SwaggerUiTest` | HTTP operations and their status codes, approval decisions, retry, stop and resume, change-run validation, lineage, metrics, and the published API document |
| Scripted end-to-end tests | `ScriptedEndToEndTest`: HTTP in, scripted model, real PostgreSQL, real Git worktrees of a temporary repository, a fake container port | Success, a build that keeps failing, retry through the API (resume, then restart), a change run on a finished run, a missing base branch, a clarification pause | A posted requirement goes through every stage to artifacts, commits, a stored verification and the documents |
| Real-Git workspace tests | Temporary repositories | `RunWorkspaceTest`, `WorkspaceMergeTest`, `RunDeliveryCompletionTest`, `RunDocumentsTest`, `ExistingFilesTest`, `DevelopmentFixAttemptTest` | Worktrees and branches, writing and deleting files, an unchanged checkout, merge history, idempotence, local edits, conflicts, path limits, and the row lock during delivery |
| Library proof tests | LangGraph4j with PostgreSQL | `ParallelBranchesJoinTest`, `ApprovalPauseCheckpointTest`, `ProcessRestartTest`, `RuntimeFanOutTest`, `CheckpointTablesTest` | Parallel branches and their join, a pause that survives a killed JVM, and the limits of run-time fan-out. They guard the pinned library version. The live graph does not use the saver |
| Sandbox tests | A fake `DockerCommand` | `DockerVerificationRunnerTest` | The container flags, the lifecycle, cancellation, timeouts and the result mapping. No real container starts in the build |
| Page test | MockMvc | `ReviewerPageTest` | The page and its script and style files are served. The page's behaviour in a browser is checked by hand |
| Opt-in live checks | Real model, enabled by a system property | `OllamaLiveCheckTest`, `HostedLiveCheckTest`, `OpenAiLiveCheckTest`, `RequirementsLiveCheckTest` | A model answers and its answer has the expected structure. Skipped by default, so the build shows 4 skipped |

## How to run

From `agent-controller/`, with Java 21, Docker and Git available:

```sh
./mvnw verify
```

Maven compiles, runs the suite and enforces the JaCoCo gate. CI runs the same command on every push and pull request (`.github/workflows/verify.yml`) and keeps the coverage report. The workflow itself was not run in this local environment, so the two criteria about it (AC-AGT-61.1 and 61.2) have no automated proof.

| Report | Location relative to `agent-controller/` |
| --- | --- |
| Test results | `target/surefire-reports/` |
| Unit coverage (HTML, CSV and XML) | `target/site/jacoco/index.html`, `jacoco.csv`, `jacoco.xml` |

An opt-in live check runs with its property, for example `./mvnw test -Dagent.model.openai-live-check=true -Dtest=OpenAiLiveCheckTest`, and needs the model's settings and key in the environment. The reviewer workflow for a real run is in the [README](../../README.md). A real run with automatic delivery needs a completely clean checkout of this repository, with no uncommitted, staged or untracked files. Otherwise the run ends `FAILED` with `DELIVERY_BLOCKED` and the work stays on its branch.

## Current numbers

From the latest full build:

| Measure | Value |
| --- | --- |
| Tests run | 565 (0 failed, 4 skipped) |
| Unit coverage, lines | 2904/3071 (94.6%) |
| Unit coverage, branches | 1120/1259 (89.0%) |
| Unit coverage, methods | 655/676 (96.9%) |
| Acceptance criteria in scope | 240 |
| With a passing proving test | 215 (89.6%) |
| With a failing proving test | 0 |
| Without a proving test | 21 |

The four skipped tests are the opt-in live checks of AC-AGT-3.8, 12.6, 62.1 and 63.7. They are counted apart from the 215. The unit figures are above the 85% gate and below the 100% target. These numbers are a snapshot. Regenerate them after any change.

## Functional coverage

Functional coverage is a table of acceptance criteria against the tests that prove them. It is generated by plain code, not by a model, and not written by hand:

```sh
cd agent-controller && ./mvnw verify
cd .. && python3 scripts/functional_coverage.py
```

The script reads the criteria in `docs/platform/requirements/` and the surefire reports and the JaCoCo CSV of the build, and writes [reports/coverage/functional-coverage.md](../../reports/coverage/functional-coverage.md). It lists every criterion with its proving tests and result, every criterion without a proving test with the reason, and the classes with missed lines or branches.

How to read it: a test proves the criteria named in its `@DisplayName`. A criterion without such a test is "not proved", even when another test exercises the behaviour. Stories marked `stale` or `deferred` are left out, so the 240 exclude the deferred stop and resume story (AGT-43) and the stories in [agent-pipeline-backlog.md](requirements/agent-pipeline-backlog.md). The stop and resume tests exist and pass in the build, but their criteria are not in the table.

## Named gaps

**Criteria without a proving test (21).** Each is named with its reason in the generated report.

| Reason | Count | Criteria |
| --- | ---: | --- |
| The page's own behaviour, checked by hand in a browser (there is no browser test) | 8 | AC-AGT-46.4, 47.3, 50.6 and AC-UI-2.1 to 2.5 |
| The behaviour is exercised, but no display name carries the criterion id | 6 | AC-RUN-1.1, 2.1 to 2.4, and AC-RUN-3.4 (the health check test names it in a comment) |
| A documentation criterion, checked by reading the file | 2 | AC-AGT-49.5, 67.7 |
| A GitHub Actions workflow, which only a run on GitHub proves | 2 | AC-AGT-61.1, 61.2 |
| Superseded by a later graph | 1 | AC-EXEC-1.1 |
| No test names it | 2 | AC-AGT-12.4, AC-AGT-62.11 |

**Unit coverage gaps.** Lines 167 of 3071 and branches 139 of 1259 are not covered. The largest gaps are in `ProcessDockerCommand`, `HostedModelClient`, `WorkspaceMerge`, `OpenAiCompatibleModelClient`, `GitCommands`, `ModelCallBudget`, `DockerVerificationRunner` and `RequirementsAgentNode`. They are mostly the error paths of the process, Git and Docker adapters and of the HTTP clients, and defensive checks that no criterion asks for. The light-test rule leaves them to a later sweep. The full list is in the generated report and in `target/site/jacoco/index.html`.

**Other gaps.**

- The sweep is a promise and not a mechanism. Without it the gate stays at 85%.
- Generated products have no coverage measure. The sandbox image runs pytest and returns a JUnit report only.
- QA and review artifacts are model-written assessments, not evidence that all criteria or security checks passed. Criterion-to-test mapping for a generated product, a coverage threshold and a release gate are not built.

## What live runs on a real model proved

| Proved | Evidence |
| --- | --- |
| The whole pipeline runs on a hosted model through OpenRouter (`deepseek/deepseek-v4.1-flash`, reasoning off): every stage, structured answers, files committed, tests run in a real container | The first live run on 2026-10-05: seven model calls, about 100 seconds, no repairs, 17 generated tests passed ([ADR 0004](../adr/0004-build-a-thin-end-to-end-skeleton-first.md)) |
| Clarification, design and plan, development with a second answer, QA, review, documentation, document export and automatic delivery work together | Run `5dc02e50-1782-4e0a-9483-3cd310282af5`: four questions, four stories with 15 criteria, a design with three diagrams, a seven-task plan, two development answers, a `PASSED` verification, and a merge commit by `controller`. Artifacts and lineage are in `runs/5dc02e50-1782-4e0a-9483-3cd310282af5/` |
| Live runs find defects that scripted tests do not | A cut-off answer (step 94), hidden reasoning using the answer limit (step 95), empty fields failing a check (step 101), a generated module named `http.py` that hid a standard library package (steps 124 and 125), and a new run starting on a checkout that held an earlier product (step 127). Each led to a code or configuration change and a test |
| A local model answers through the Ollama client | `OllamaLiveCheckTest` was run once against `qwen2.5-coder:7b` and passed (step 68) |

## What was not proved

- **Repeatability.** A real model gives different results each time. No claim is made that a given requirement will pass.
- **Complete scenario evidence.** The brownfield and ambiguous scenarios in `scenarios/` are written, but a full evidence set for each is not in the repository. There is no failure drill (AGT-57).
- **A live end-to-end test.** The live runs were manual. No test class drives a real model through the whole pipeline.
- **A real fallback model, a replay of a recorded run, and stop and resume against a live model.**
- **The sandbox flags on a real daemon.** The flags are checked against a fake Docker port. A real container was exercised only in the live runs.
- **The generated product's running behaviour.** The controller runs its tests. It never starts the product.
- **Browser behaviour of the reviewer page**, apart from hand checks that are named in the requirements.
- **The other opt-in live checks** (hosted messages API, OpenAI-style, requirements stage) are written and skipped by default. No claim is made here that they were run.

## QA and code review evidence

QA and code review run together, once per feature, when the user asks. The `qa-agent` runs the build, sums the JaCoCo totals and maps criteria to tests. The `code-review-agent` reviews every file of the feature and records one row in [reports/review/index.md](../../reports/review/index.md), with each finding, its severity and how it was resolved. Neither fixes anything. The user chooses which findings the development agent fixes.

| Review row | What was found | Resolution |
| --- | --- | --- |
| Run API, Swagger, run execution (two passes), SDLC flow (two passes): 8 rows | No blocking finding. Several should-fix items, for example sibling branches left running after a failure, a poll that started only one waiting run, and error responses that did not match the API document | Fixed, accepted or deferred with a reason in each row |
| Agent pipeline stories AGT-6 to AGT-64 and the page (step 109): 6 rows, 42 findings | No blocking finding, 10 should-fix and 32 notes. QA also found criteria with no proving test and one bug | 9 are fixed: RM-1, RM-3, RM-4, RA-1, RW-1, RX-2, RX-3, RU-1, and RX-1 in part. Some rows still show the three folded into stage retry (RM-1, RX-2, RU-1) as open. The rest are open or deferred |

What is missing: the work coded after step 109 has had no QA and review pass. That is the stage and run retry, brownfield, the worktree arrangement, approvals, stop and resume, delivery, and deletions in change sets. Before submission every file should be covered by a review. This is not yet true.
