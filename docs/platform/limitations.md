# Limitations and trade-offs

This is a working prototype of an agentic delivery pipeline. It runs end to end on a real model. It is not an unattended production service. Each limit below was checked against the code and the review index ([reports/review/index.md](../../reports/review/index.md)). Finding ids such as RX-1 come from that index. Story ids such as AGT-30 are in [agent-pipeline-backlog.md](requirements/agent-pipeline-backlog.md) unless the story was delivered ([agent-pipeline.md](requirements/agent-pipeline.md)).

## How to read SUCCEEDED

`SUCCEEDED` means: every selected stage returned, the generated tests passed in the sandbox, the documents were exported, and (with automatic delivery) the work was merged into the checkout. It does not mean that review findings were resolved, that every acceptance criterion has a test, that coverage met a threshold, or that a person approved the final commit. Only the design and the plan are approved by a person.

## Generation and quality

| Limit | What the code does | Mitigation or next step |
| --- | --- | --- |
| Model dependence and variance | Output quality comes from the model. The same requirement can give different code on each run. Plain code checks the shape of an answer and the test report, not the quality of the design or the code. The repair loop bounds shape errors only | Approvals of the design and the plan, tests decide pass or fail, a different model is a profile setting ([ADR 0009](../adr/0009-choose-the-model-api-style-by-profile-setting.md)) |
| One development answer for the whole product | Development returns every file in one answer. The planned tasks are not executed one by one. The live answer limit is 12,000 tokens and a file is at most 100,000 bytes | Keep a requirement to about three stories. Next step: a per-task development loop with its own build (AGT-24, AGT-37) |
| Fix attempts can oscillate or regress | A fix attempt gets the failing test names, the first 2,000 characters of the report and at most 30 files (40,000 characters). It rewrites files on top of the earlier commits. Nothing compares attempts or goes back to the best one. Real runs showed the cost: nine identical failed checks because a module was named like a standard library module (step 124), and fix attempts that rewrote old tests instead of the product when a new run started on a checkout that held an earlier product (step 127) | Both causes were fixed (deletions in a change set, an empty product folder). Attempts stay bounded: three per stage, three per run. Next step: keep the best attempt and roll back a regression |
| QA and review findings change nothing | QA and review artifacts are model-written. A role boundary tells them not to claim tests ran, and nothing checks that. There is no repair loop from findings and no gate | Next step: a plain-code release gate and a bounded repair from findings (AGT-30, AGT-31) |
| Clarification is one round | At most four questions, one batch. The run waits with no time limit. Stages after requirements turn open questions into assumptions. Assumptions in the stories are confirmed by nobody, except through the design and plan approvals | Next step: questions from other roles and several rounds (AGT-13, AGT-14) |
| Brownfield is partial | A change run is cut from the base run's branch and its agents are shown at most 30 existing files (40,000 characters). The live profile leaves impact analysis off (`agent.stages.impactAnalysis=agent` turns it on). Plain code does not check the analysis against the files. The base branch must still exist. Scenario evidence for change runs is partial | Start the controller with the setting for real impact analysis. Next step: a check of the claimed files and a complete brownfield scenario run (AGT-55) |
| Parallel stages take turns | A profile allows one concurrent model call by default, so QA, review and documentation take turns at the model although the graph runs them side by side | Raise `max-concurrent-calls` for a profile whose provider allows it |
| Fallback model is not used live | The code retries, then calls a second profile for a retryable failure. The live profile names no fallback | Name a fallback profile in the settings |

## Sandbox and execution

| Limit | What the code does | Mitigation or next step |
| --- | --- | --- |
| The container shares the host kernel | Non-root, no network, all capabilities dropped, `no-new-privileges`, memory, CPU and process limits, a time limit, no mount, no environment. That is not virtual-machine isolation | Documented. A stronger isolation layer would be a separate project |
| No disk limit and a writable root file system (RX-1, partly fixed) | `--read-only` and a size-limited `--tmpfs` were declined: `docker cp` is refused on a read-only root file system, and a temporary file system is empty after the container stops, so the report could not be copied out | Next step: return the report another way, then add both (AGT-33) |
| The controller holds Docker access | It starts containers with the `docker` command line and has daemon access, a strong privilege. Only the controller starts containers | Documented limitation (decision 8 in `AGENTS.md`). The labelled containers it leaves after a crash are not removed |
| The verdict file is written by the code under test (RX-4) | The pass or fail evidence is a JUnit report at `/tmp/junit.xml` that generated code produces inside the container. The container's exit code is ignored. A generated test suite could write a false report | Next step: run the test tool from outside the generated code, or compare the report with the exit code |
| The sandbox image is not pinned (RC-3) | pytest is pinned, but the base image is a moving tag and the configured image is `:latest` | Pin the base image by digest |
| Docker and Git output is dropped or unfiltered (RX-5, RX-6) | The output of `docker` commands is discarded, so a missing image and a stopped daemon look alike in the log. `git` runs with the host's global configuration | Next step: log the exit text, and isolate the Git configuration |
| The generated product is not started | The controller runs the product's tests. It does not start the product, call its endpoints or check that its README instructions work | A person follows the generated README. Next step: a smoke test of the running product |

## Operation, recovery and delivery

| Limit | What the code does | Mitigation or next step |
| --- | --- | --- |
| One controller instance and no crash recovery | One scheduler thread dispatches one run at a time. A controller that dies leaves the run `RUNNING`, and nothing claims it again. Leftover containers stay. The graph has no checkpoint saver | Next step: end or resume `RUNNING` runs at startup (AGT-39). Stop, resume and retry work only for runs the controller still knows about |
| Slow continuation after an answer | An answered clarification waits until the run that is executing has finished (RW-5) | Next step: runs side by side (AGT-35) |
| No stage or run time limit | Only the model call timeout (300 s in the live profile) and the container limit (5 minutes) exist | Next step: stage limits (AGT-36) |
| Stop is cooperative and has no rollback | A model call in flight finishes first. A stopped run keeps everything. Nothing deletes a workspace or undoes a commit or a merge. The tests of stop and resume pass in the last build, but the story is deferred (ADR 0012) and the page buttons were not checked in a browser | Next step: acceptance of its criteria, and a discard operation |
| Retention | Branches, worktrees, database rows and containers are never removed by the controller | A person prunes them |
| Replay mode has no recorder (RM-10) | Replay reads a recording file. Nothing in the controller writes one. A recorded attempt is served once per JVM (part of RM-1) | Next step: a recorder (AGT-58). The tests use scripted models |

## Automatic merge into the checkout

| Limit | What the code does | Mitigation or next step |
| --- | --- | --- |
| Delivery needs a completely clean checkout | With `workspace.auto-merge=true` (the live profile), delivery runs only if the checkout is on a branch, has no uncommitted, staged or untracked file, has no merge, rebase or cherry-pick in progress, and the run changed only the product folder and `runs/`. Git 2.38 or newer is needed. Otherwise the run ends `FAILED` with `DELIVERY_BLOCKED` and the work stays on its branch | Commit or clean the checkout before starting a run and keep it clean until the run finishes, then use Retry. The first retry reruns QA, review and documentation, which spends model calls |
| No merge approval | The merge happens after the checks, with no person approving the commit. The design and plan approvals come earlier | Set `workspace.auto-merge=false` for manual delivery. Next step: merge approval (AGT-32) |
| Git and PostgreSQL do not commit together | A merge can succeed and the recording of success can fail. A repeat is a no-op. Merges are serialized only inside one controller process | Do not run Git in the checkout during delivery |
| Runs on the same product folder | A new run that changes no earlier run starts with an empty product folder (step 127), so it replaces the product that was in the checkout when it started. Two runs cut from the same commit can conflict when the second is delivered | Deliver one run at a time ([ADR 0013](../adr/0013-automatically-merge-completed-work.md)) |
| The controller never pushes | Pushing the branches stays with a person | By design |

## Trust, access and audit

| Limit | What the code does | Mitigation or next step |
| --- | --- | --- |
| No authentication; the approver is a typed name | Anyone who can reach the port can start, approve, stop, retry and read artifacts. The approver name is free text of up to 100 characters. The reviewer page has a CSP meta tag but sends no frame or content-type headers (RU-3) | Use it only in a trusted local environment. Next step: identities and permissions (AGT-48) |
| Sensitive data is stored | The `model_call` table keeps full prompts and answers, so the database holds the requirement and generated content. The Compose file holds a fixed local database password. Logs exclude prompts, answers and keys | Treat the database as sensitive. Do not expose it |
| Audit is logs and tables, not a tamper-evident trail | Artifacts, model calls, decisions and verification results are only added by the code, but the database does not enforce it. Fingerprints are not chained. Log lines go to standard output and the platform does not store them. Approval decisions and model calls are not exported as files | Next step: an append-only trail with enforcing permissions (AGT-44) |
| Prompt injection is only partly handled | Input markers in text are escaped (RM-4, fixed), and the role prompts treat inputs as data. A requirement can still steer a model. The defences are the plain-code checks, the path rules, the approvals and the sandbox | Keep the human reading of the design and the plan |

## Evidence and tests

| Limit | What exists | Mitigation or next step |
| --- | --- | --- |
| Coverage below the target | Lines 94.6%, branches 89.0%. The build gate is 85% until a sweep ([ADR 0008](../adr/0008-keep-tests-light-and-gate-coverage-at-85-percent-for-now.md)). The sweep is a promise, not a mechanism | Named gaps in [testing](testing.md) |
| Unproved criteria | 21 of 240 criteria in scope have no proving test, each with its reason in [functional-coverage.md](../../reports/coverage/functional-coverage.md). Stories marked stale or deferred are outside the 240 | Tests that cite the criterion id, a browser test for the page |
| Review and QA are incomplete | The review index has 14 rows. Of the 42 findings of the 2026-10-05 pass, 9 are fixed (RM-1, RM-3, RM-4, RA-1, RW-1, RX-1 partly, RX-2, RX-3, RU-1). Some rows still show them as open. The later work (retry, brownfield, worktrees, approvals, stop and resume, delivery, deletions) has had no review pass | Run QA and review on the later features |
| Scenario evidence is partial | One greenfield run with clarification and a second development answer was delivered, with its evidence in `runs/`. Brownfield and ambiguous scenarios are described in `scenarios/`, but a complete evidence set for each is not in the repository. There is no live end-to-end test class | Run the scenarios and keep the evidence listed in [scenarios/README.md](../../scenarios/README.md) |

## Trade-offs chosen on purpose

- **Thin path first, governance after** ([ADR 0004](../adr/0004-build-a-thin-end-to-end-skeleton-first.md)). A real run existed early and showed real defects. The price is the list in [architecture, section 8](architecture.md#8-not-built).
- **Whole-file answers and direct context injection.** Easy to produce and to check. The cost is large prompts, tight output limits and a multi-file write that is not transactional.
- **One local process.** Simple to debug, with no distributed execution.
- **One execution profile (Python, SQLite, pytest).** Small and offline. It shows nothing about other languages or scale.
- **Light tests and an 85% gate** ([ADR 0008](../adr/0008-keep-tests-light-and-gate-coverage-at-85-percent-for-now.md)). Speed now, a named debt later.
- **Automatic merge with preconditions** ([ADR 0013](../adr/0013-automatically-merge-completed-work.md)). The product appears beside the controller without a manual step, with no merge approval.
- **Scripted models and stubs in the normal build.** Repeatable and free. They do not show the quality of a current live model.

The next work should add the missing controls explicitly and not widen the meaning of `SUCCEEDED` without a matching gate and its evidence.
