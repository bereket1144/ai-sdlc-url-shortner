# Functional coverage report

Date: 2026-10-04 (refreshed after step 45). Target: 100% of acceptance criteria proved by a passing test.
Sources: the three files in `docs/platform/requirements/`, and the tests in `agent-controller/src/test/` as run by `./mvnw clean verify` (77 tests, 0 failures; the build passes).

## Totals

| Measure | Count |
| --- | --- |
| Acceptance criteria in all requirements files | 44 |
| Proved by a passing test | 43 (97.7%) |
| Test exists but fails | 0 |
| No test | 1 |
| Criteria of stories that are not open (excluding BUG-1) | 43, of which 43 pass (100%) |

## Where the target is not met

| Criterion | Status | Reason |
| --- | --- | --- |
| AC-BUG-1.1 | No test | BUG-1 is open and deferred by decision. |

AC-EXEC-1.1 now has a passing test (`WorkflowRunnerTest`), but story EXEC-1 is still marked open in `run-execution.md` and its code is not committed, so it is counted as proved but not as done.

## Weak spots in criteria that pass

| Criterion | Weakness |
| --- | --- |
| AC-RUN-6.7 | "The existing migration is not edited" is checked only indirectly, by migrating an old row forward in `RunMigrationTest`. Nothing detects an edit to an existing migration file. |
| AC-SWAG-3.3 | `SwaggerUiTest` proves only the unknown-path 404. The "same statuses and bodies as before" half rests on the RUN tests still passing. |
| AC-RUN-1.1, 2.1 to 2.4, 4.1 to 4.4 | Proved by tests whose names carry other IDs (for example 1.1 by the 3.1 test). They are not tagged with their own ID, so traceability depends on this table. |

## Behaviour with no story

Claiming the next run (`RunService.claimNextRun`, status `RUNNING`) and `Run.start()` are coded and tested (`RunPersistenceTest.claimNextRunPersistsStatus`, two tests in `RunTest`), but no requirements file has a story or criterion for them. They cannot be counted above. The assumption in `run-api.md` that run status has only one value (`CREATED`) is also out of date.

## Criterion to test

Tests are in `agent-controller/src/test/java/io/github/bereket1144/agentcontroller/run/`. Result: pass unless stated. AC-RUN-7.3 does not exist (the numbering skips it).

### run-api.md

| Criterion | Proving test (class) | Result |
| --- | --- | --- |
| AC-RUN-1.1 | RunApiVersionTest: 201 and a string `id` | Pass |
| AC-RUN-1.2 | RunFullStackTest: a client-supplied id is ignored | Pass |
| AC-RUN-1.3 | RunPersistenceTest: reads back the same text | Pass |
| AC-RUN-1.4 | RunPersistenceTest: more than 255 characters | Pass |
| AC-RUN-2.1 | RunErrorFormatFullContextTest: `{}`, null, empty, whitespace | Pass |
| AC-RUN-2.2 | RunErrorFormatFullContextTest: malformed JSON | Pass |
| AC-RUN-2.3 | RunErrorFormatFullContextTest: missing body | Pass |
| AC-RUN-2.4 | RunErrorFormatFullContextTest: row count unchanged | Pass |
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
| AC-RUN-4.6 | RunErrorAcceptHeaderFullContextTest, RunErrorFormatFullContextTest | Pass |
| AC-RUN-5.1 | RunRequirementSizeTest (including emoji code points) | Pass |
| AC-RUN-5.2 | RunRequirementSizeTest | Pass |
| AC-RUN-6.1 | RunPersistenceTest | Pass |
| AC-RUN-6.2 | RunPersistenceTest | Pass |
| AC-RUN-6.3 | RunPersistenceTest | Pass |
| AC-RUN-6.4 | RunPersistenceTest | Pass |
| AC-RUN-6.5 | RunPersistenceTest | Pass |
| AC-RUN-6.6 | RunMigrationTest | Pass |
| AC-RUN-6.7 | RunMigrationTest (indirect, see above) | Pass |
| AC-RUN-7.1 | RunReadTest, RunFullStackTest | Pass |
| AC-RUN-7.2 | RunReadTest, RunFullStackTest, RunPersistenceTest | Pass |
| AC-RUN-7.4 | RunReadTest, RunFullStackTest, RunPersistenceTest | Pass |
| AC-RUN-7.5 | RunReadTest | Pass |
| AC-BUG-1.1 | None | No test |

### swagger.md

| Criterion | Proving test (class) | Result |
| --- | --- | --- |
| AC-SWAG-1.1 | ApiDocumentTest | Pass |
| AC-SWAG-1.2 | ApiDocumentTest | Pass |
| AC-SWAG-1.3 | ApiDocumentTest | Pass |
| AC-SWAG-1.4 | ApiDocumentTest (criterion changed in step 45 to allow `CREATED` and `RUNNING`) | Pass |
| AC-SWAG-2.1 | ApiDocumentTest | Pass |
| AC-SWAG-2.2 | ApiDocumentTest | Pass |
| AC-SWAG-2.3 | ApiDocumentTest | Pass |
| AC-SWAG-3.1 | SwaggerUiTest | Pass |
| AC-SWAG-3.2 | SwaggerUiTest | Pass |
| AC-SWAG-3.3 | SwaggerUiTest (404 half), RUN tests | Pass |

### run-execution.md

| Criterion | Proving test (class) | Result |
| --- | --- | --- |
| AC-EXEC-1.1 | WorkflowRunnerTest (workflow package) | Pass |
