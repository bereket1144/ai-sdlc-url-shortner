# Functional coverage report

Date: 2026-10-04 (refreshed after the SDLC flow review, step 61). Target: 100% of acceptance criteria proved by a passing test.
Sources: the four files in `docs/platform/requirements/` and the tests in `agent-controller/src/test/` as run by `./mvnw clean verify` (140 tests, 0 failures; the build passes).

## Totals

| Measure | Count |
| --- | --- |
| Acceptance criteria in all requirements files | 63 |
| Proved by a passing test | 61 (96.8%) |
| No test (open, deferred bug story) | 1 (AC-BUG-1.1) |
| Superseded, no test of its own | 1 (AC-EXEC-1.1) |
| Criteria of stories that are done and not superseded | 61, of which 61 pass (100%) |

## Where the target is not met

| Criterion | Status | Reason |
| --- | --- | --- |
| AC-BUG-1.1 | No test | BUG-1 is open and deferred by decision: an oversized request body is read in full before the length limit applies. To be picked up much later. |
| AC-EXEC-1.1 | Superseded | The single-node topology it describes was replaced by the SDLC graph of FLOW-1 (user decision, recorded in `docs/steps.md` step 59 and in `sdlc-flow.md`). EXEC-1 stays as a historical milestone and its criterion is unchanged. The same fact, that the active graph receives the run ID and requirement, is proved by AC-FLOW-1.3 (`WorkflowRunnerTest.activeGraphReceivesRunInput`). |

## Unit coverage gaps (JaCoCo, with reasons)

Figures from `./mvnw clean verify`: line 236/237, branch 40/40, method 62/64. The build gate is 95% (line and branch); the project target is 100%, so the gate is looser than the target and is left for the final packaging step.

| Where | Reason it is not covered |
| --- | --- |
| `workflow/WorkflowRunner.java`, the lambda `() -> new IllegalStateException("Workflow returned no state")` | The graph always returns its final state, so the branch cannot be reached through the public API. Reaching it would need a way to supply the graph from a test, which is a production design change. Revisit when the graph grows. See `docs/design-notes.md` and `reports/review/index.md`. |
| `run/ProblemBody.java`, the record constructor | The record only describes the error body in the API document. It is never created at run time. |

## Weak spots in criteria that pass

| Criterion | Weakness |
| --- | --- |
| AC-RUN-6.7 | "The existing migration is not edited" is checked only indirectly, by migrating an old row forward in `RunMigrationTest`. Nothing detects an edit to an existing migration file. |
| AC-SWAG-3.3 | `SwaggerUiTest` proves only the unknown-path 404. The "same statuses and bodies as before" half rests on the RUN tests still passing. |
| AC-RUN-1.1, 2.1 to 2.4 | Proved by tests whose names carry other IDs (see the table), so traceability depends on this report. |
| AC-FLOW-1.4 | Overlap is shown with latches and a 300 ms negative wait for the join. The wait can miss a broken join but cannot fail on a slow machine. |

## Behaviour with no story

- Recovery of a run stranded in `RUNNING` by a process crash is deferred (see "Deferred" in `run-execution.md`, review finding R-3). It has no criterion yet.
- `run-api.md` still says run status has only one value (`CREATED`); the statuses are now `CREATED`, `RUNNING`, `SUCCEEDED` and `FAILED` (documentation drift, not a defect).

## Criterion to test

Tests are in `agent-controller/src/test/java/io/github/bereket1144/agentcontroller/` (`run/` and `workflow/`). Result is Pass unless stated. AC-RUN-7.3 does not exist (the numbering skips it). Criteria are listed with every test class tagged with them.

### run-api.md

| Criterion | Proving test (class) | Result |
| --- | --- | --- |
| AC-RUN-1.1 | RunApiVersionTest (201 and a string `id`; not tagged with this ID) | Pass |
| AC-RUN-1.2 | RunFullStackTest | Pass |
| AC-RUN-1.3 | RunPersistenceTest | Pass |
| AC-RUN-1.4 | RunPersistenceTest | Pass |
| AC-RUN-2.1 | RunErrorFormatFullContextTest (`{}`, null, empty, whitespace; tagged 4.1) | Pass |
| AC-RUN-2.2 | RunErrorFormatFullContextTest (malformed JSON; tagged 4.2) | Pass |
| AC-RUN-2.3 | RunErrorFormatFullContextTest (missing body; tagged 4.2) | Pass |
| AC-RUN-2.4 | RunErrorFormatFullContextTest (row count unchanged; tagged 4.1, 4.2) | Pass |
| AC-RUN-2.5 | RunTest | Pass |
| AC-RUN-3.1 | RunApiVersionTest | Pass |
| AC-RUN-3.2 | RunApiVersionTest | Pass |
| AC-RUN-3.3 | RunApiVersionTest | Pass |
| AC-RUN-3.4 | HealthEndpointTest | Pass |
| AC-RUN-4.1 | RunErrorFormatFullContextTest | Pass |
| AC-RUN-4.2 | RunErrorFormatFullContextTest | Pass |
| AC-RUN-4.3 | RunErrorFormatFullContextTest | Pass |
| AC-RUN-4.4 | RunErrorFormatFullContextTest | Pass |
| AC-RUN-4.5 | RunUnexpectedErrorFullContextTest | Pass |
| AC-RUN-4.6 | RunErrorAcceptHeaderFullContextTest, RunErrorFormatFullContextTest, RunUnexpectedErrorFullContextTest | Pass |
| AC-RUN-5.1 | RunRequirementSizeTest | Pass |
| AC-RUN-5.2 | RunRequirementSizeTest | Pass |
| AC-RUN-6.1 | RunPersistenceTest | Pass |
| AC-RUN-6.2 | RunPersistenceTest | Pass |
| AC-RUN-6.3 | RunPersistenceTest | Pass |
| AC-RUN-6.4 | RunPersistenceTest | Pass |
| AC-RUN-6.5 | RunPersistenceTest | Pass |
| AC-RUN-6.6 | RunMigrationTest | Pass |
| AC-RUN-6.7 | RunMigrationTest | Pass |
| AC-RUN-7.1 | RunFullStackTest, RunReadTest | Pass |
| AC-RUN-7.2 | RunFullStackTest, RunPersistenceTest, RunReadTest | Pass |
| AC-RUN-7.4 | RunFullStackTest, RunPersistenceTest, RunReadTest | Pass |
| AC-RUN-7.5 | RunReadTest | Pass |
| AC-BUG-1.1 | None | No test |

### swagger.md

| Criterion | Proving test (class) | Result |
| --- | --- | --- |
| AC-SWAG-1.1 | ApiDocumentTest | Pass |
| AC-SWAG-1.2 | ApiDocumentTest | Pass |
| AC-SWAG-1.3 | ApiDocumentTest | Pass |
| AC-SWAG-1.4 | ApiDocumentTest | Pass |
| AC-SWAG-2.1 | ApiDocumentTest | Pass |
| AC-SWAG-2.2 | ApiDocumentTest | Pass |
| AC-SWAG-2.3 | ApiDocumentTest | Pass |
| AC-SWAG-3.1 | SwaggerUiTest | Pass |
| AC-SWAG-3.2 | SwaggerUiTest | Pass |
| AC-SWAG-3.3 | SwaggerUiTest | Pass |

### run-execution.md

| Criterion | Proving test (class) | Result |
| --- | --- | --- |
| AC-EXEC-1.1 | None of its own: superseded by FLOW-1 (see "Where the target is not met") | Superseded |
| AC-EXEC-2.1 | RunDispatcherTest | Pass |
| AC-EXEC-2.2 | RunDispatcherTest | Pass |
| AC-EXEC-2.3 | RunDispatcherTest | Pass |
| AC-EXEC-3.1 | RunDispatcherCompletionTest, RunSuccessPersistenceTest, RunTest | Pass |
| AC-EXEC-3.2 | RunDispatcherCompletionTest | Pass |
| AC-EXEC-4.1 | RunSchedulerStartupTest | Pass |
| AC-EXEC-4.2 | RunSchedulerDefaultsTest, RunSchedulerTest | Pass |
| AC-EXEC-4.3 | RunSchedulerTest | Pass |
| AC-EXEC-5.1 | RunDispatcherCompletionTest, RunDispatcherFailureTest, RunFailurePersistenceTest, RunSchedulerTest, RunSdlcFlowTest, RunTest, WorkflowRunnerParallelTest, WorkflowRunnerTest | Pass |
| AC-EXEC-5.2 | RunDispatcherCompletionTest, RunDispatcherFailureTest, RunSdlcFlowTest | Pass |
| AC-EXEC-5.3 | RunFailurePersistenceTest, RunTest | Pass |

### sdlc-flow.md

| Criterion | Proving test (class) | Result |
| --- | --- | --- |
| AC-FLOW-1.1 | WorkflowRunnerTest | Pass |
| AC-FLOW-1.2 | WorkflowRunnerTest | Pass |
| AC-FLOW-1.3 | WorkflowRunnerTest | Pass |
| AC-FLOW-1.4 | WorkflowRunnerParallelTest, WorkflowRunnerTest | Pass |
| AC-FLOW-1.5 | RunSdlcFlowTest | Pass |
| AC-FLOW-1.6 | ApiDocumentTest, RunDispatcherTest, RunFullStackTest, RunMigrationTest, RunPersistenceTest, RunSdlcFlowTest, RunTest | Pass |
| AC-FLOW-2.1 | WorkflowNodeLoggingTest | Pass |
| AC-FLOW-2.2 | WorkflowNodeLoggingTest | Pass |
