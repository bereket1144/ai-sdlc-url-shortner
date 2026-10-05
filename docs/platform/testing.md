# Testing approach

The controller and generated product have separate evidence. Controller tests prove orchestration policy and persistence. Product tests run against files generated for a particular run and commit. Neither substitutes for the other.

## Controller verification

From `agent-controller/`, run `./mvnw verify` with Java 21 and Docker available. Maven compiles the application, executes the JUnit suite and enforces the JaCoCo line and branch gate. Git must be installed for workspace tests. CI runs the same command with Docker available.

| Boundary | Approach and representative evidence |
| --- | --- |
| API and run lifecycle | HTTP/service tests for request validation, reads, claiming, dispatch and failure reasons |
| Model calls | Scripted models and stub HTTP servers test provider mapping, structured output, retries, repair, fallback, replay and budgets without paid calls |
| Artifacts | `ArtifactStoreTest` proves versions, fingerprints, reads and lineage against persistence |
| Graph | `WorkflowRunnerTest`, `WorkflowRunnerParallelTest` and `ParallelBranchesJoinTest` exercise routing and joins |
| Checkpoint feasibility | `ApprovalPauseCheckpointTest` and checkpoint tests prove persistence mechanisms separately from the live graph |
| Files and Git | `ChangeSetCheckTest` and `RunWorkspaceTest` exercise permitted changes, fresh per-run repositories and commits |
| Sandbox commands | `DockerVerificationRunnerTest` uses a fake Docker port to check execution flags, lifecycle and result handling |
| API-to-artifact-to-commit flow | `ScriptedEndToEndTest` uses a scripted model, real PostgreSQL and Git, and a fake container port; successful and failed verification outcomes are covered |

Integration tests use Testcontainers PostgreSQL. They do not require the developer's Compose database. The ordinary build never calls a live model. The scripted end-to-end test does not prove that a real sandbox image starts or that a current model produces usable code.

## Reports and targets

- Test results: `agent-controller/target/surefire-reports/`.
- JaCoCo report: `agent-controller/target/site/jacoco/index.html`, with machine-readable XML alongside it.
- CI retains the JaCoCo report as a build artifact.
- Functional coverage: [current table](../../reports/coverage/functional-coverage.md).
- Review findings: [review index](../../reports/review/index.md).

The coverage target remains 100%. The enforced line and branch minimum is temporarily 85%, approved in step 89. A later coverage sweep is intended to restore 95% and work toward the target. The functional coverage table and review index predate recent pipeline work; they are not evidence of complete current feature coverage or review. Exact current percentages must come from the generated report, not the minimum gate.

The handoff records `./mvnw verify` passing at baseline `91be0a3` with 417 tests and 4 skipped opt-in live checks. This is the last recorded full build, not a result for subsequent changes. AGT-64 adds nine focused tests in `RequirementsClarificationTest`, covering its batch flow and a fresh application instance on the same database; test sources compile, but this new suite has not been executed.

## Live and generated-product evidence

The opt-in model checks assert response structure, not wording. They are disabled in the normal build. The dedicated opt-in live end-to-end check required by AC-AGT-62.16 is still missing, so AGT-62 remains open even though the skeleton has been manually exercised.

The recorded manual run on 2026-10-05 used the configured OpenRouter model, traversed seven agent stages and made seven model calls in about 100 seconds with no repairs. The generated `app.py` and `test_app.py` were committed in a run workspace. A real disposable container reported 17 pytest tests passed, and the run ended `SUCCEEDED`. This is a recorded trial, not a repeatability guarantee or a substitute for the remaining scenarios.

The current image runs pytest and returns JUnit XML; it does not measure generated-product line or branch coverage. QA and review artifacts are model-produced assessments, not independent evidence that all acceptance criteria or security checks passed. Criterion-to-test mapping, product coverage, enforced review gates, and fresh greenfield, brownfield, ambiguous and failure-drill evidence remain outstanding.
