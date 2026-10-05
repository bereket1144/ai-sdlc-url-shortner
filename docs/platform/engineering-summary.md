# Engineering summary

## Outcome as of 2026-10-05

The platform takes a written requirement through requirements, design, planning, development, QA, review and documentation. It asks the requester questions, holds the design and the plan for approval, writes code and tests into a per-run Git branch, builds and tests them in a throwaway container, writes the documents and the evidence, and merges the verified work into this repository. It has run on a real model: one live run asked four questions, produced four stories with fifteen criteria, a design with three diagrams and a seven-task plan, needed a second development answer that corrected two files, passed its tests and was merged (evidence in `runs/5dc02e50-1782-4e0a-9483-3cd310282af5/`).

The last full build ran 565 tests (0 failed, 4 skipped opt-in live checks) with unit coverage of 94.6% of lines and 89.0% of branches. 215 of 240 acceptance criteria in scope have a passing test and 21 have none, each named with its reason ([testing](testing.md)). The platform is not release-ready: it has no plain-code release gate, no per-story development loop, no tamper-evident audit trail and no re-planning ([architecture, section 8](architecture.md#8-not-built)).

## Plan and rationale

1. **Prove the risky library behaviour first** (steps 4 to 12). Parallel branches and their join, a run that pauses and resumes from PostgreSQL after a killed process, and a run-time number of tasks were each proved by a test before any product code. LangGraph4j 1.8.27 cannot fan out by graph edges, so a run-time task count would have to live in plain Java ([ADR 0001](../adr/0001-run-the-task-scheduler-inside-one-node.md)).
2. **Build the run API, the scheduler and a dummy graph** (steps 13 to 62), with a QA and review pass per feature. The graph, the states and the error format were stable before any model was called.
3. **Add the agent foundations** (steps 63 to 85): role files, the model port, prompts sized to the window, output validation and repair, retry and fallback, a call budget, call records, replay and versioned artifacts.
4. **Build one thin path through everything, then add governance** ([ADR 0004](../adr/0004-build-a-thin-end-to-end-skeleton-first.md), from step 86): a hosted-model client, a real agent for every stage, a workspace, a sandboxed build and a document export. The first real run showed three problems at once while they were cheap to fix: an answer cut off at the limit, hidden reasoning using the answer limit, and empty fields failing a check.
5. **Add governance in order of value** (from step 98): clarification, metrics and lineage, stage retry with fix feedback, run retry, brownfield, one repository with a worktree per run ([ADR 0010](../adr/0010-work-in-a-git-worktree-of-this-repository.md)), approvals, cooperative stop and resume ([ADR 0011](../adr/0011-stop-cooperatively-and-retain-work-for-resume.md)), automatic delivery ([ADR 0013](../adr/0013-automatically-merge-completed-work.md)), deletions in a change set, and an empty product folder for a new greenfield run.

Why this order: time was limited, and the problems that only a real run shows would have come last. The cost is that the requirements file no longer matches the build order, and thin work can be mistaken for finished work. The documents therefore say what is missing and do not widen the meaning of `SUCCEEDED`.

Why one controller: orchestration, transactions and debugging stay in one process. An agent is a role file and a prompt, not a service. Models return structured data and whole files. Plain controller code checks the output, applies the files and decides pass or fail.

## Artifacts and where to find them

The README section [Where to find the code, the artifacts and the history](../../README.md#where-to-find-the-code-the-artifacts-and-the-history) is the map. In short:

| Artifact | Where |
| --- | --- |
| Generator code (graph, agents, model port, workspace, sandbox, delivery) | `agent-controller/src/main/java/io/github/bereket1144/agentcontroller/`, role files in `src/main/resources/agent-roles/` |
| Requirements, stories and criteria | [agent-pipeline.md](requirements/agent-pipeline.md) (29 delivered stories), [agent-pipeline-backlog.md](requirements/agent-pipeline-backlog.md) (36 not delivered), [reviewer-ui.md](requirements/reviewer-ui.md) and the run, execution, flow and Swagger files |
| Architecture, limits, testing | [architecture.md](architecture.md), [limitations.md](limitations.md), [testing.md](testing.md) |
| Decisions | [ADR 0001 to 0013](../adr/) |
| Work history with done-when and result per step | [docs/steps.md](../steps.md) (steps 1 to 127) |
| Per-run artifacts and lineage | PostgreSQL through `GET /v1/runs/{id}/artifacts/{kind}/{version}` and `GET /v1/runs/{id}/lineage`, and as files under `runs/<run-id>/` |
| Generated product, its documents and diagrams | `apps/url-shortener/` after delivery, and on the branch `run/<run-id>` |
| Scenarios | [scenarios/](../../scenarios/): greenfield, brownfield, ambiguous |
| Code review and QA evidence | [reports/review/index.md](../../reports/review/index.md), [reports/coverage/functional-coverage.md](../../reports/coverage/functional-coverage.md) |
| Coding-assistant roles | `.claude/agents/` (five roles) |

## Core requirements and what was built

| Requirement | Status | What exists and where |
| --- | --- | --- |
| Requirement understanding | Built | The requirements agent asks at most four questions in one batch, waits for the answers, then writes stories with numbered criteria and a list of assumptions (`RequirementsAgentNode`, `ClarificationService`, [ADR 0007](../adr/0007-clarify-with-questions-and-answers-only.md)). One round only, and the assumptions are confirmed by nobody |
| Decomposition | Partly built | Stories, then a design, then a plan with tasks. Development returns the whole change in one answer, so the planned tasks are not executed one by one |
| Brownfield | Partly built | A run of kind `BROWNFIELD` with a `baseRunId` is cut from the base run's branch. The agents are shown the existing files, impact analysis is available, and the whole suite runs again. The live profile leaves impact analysis off, and plain code does not check it against the files |
| Orchestration: graph | Built | One fixed LangGraph4j graph with a conditional route for the run kind and for approvals (`WorkflowRunner`) |
| Orchestration: parallel paths | Built | QA, review and documentation run side by side and join. The number of branches is fixed |
| Orchestration: lineage | Built | Each artifact stores the versions and fingerprints it was built from. `GET /v1/runs/{id}/lineage` and `lineage.json` |
| Orchestration: approvals | Partly built | Design and plan, with bounded rejections and stored decisions. The approver is a typed name. No merge approval |
| Orchestration: retries | Built | Model call retry, answer repair, stage retry with fix attempts, run retry (resume, then restart) |
| Orchestration: fallback | Built, not used live | A second model profile is called after retries on the primary run out. The live profile names none |
| Orchestration: safe stop | Partly built | Cooperative stop and resume with tests, work retained. No rollback and no crash recovery. The page buttons were not checked in a browser |
| Orchestration: guardrails | Built | Path checks, sandbox limits, call budget, output validation, role input limits, bounded retries, delivery preconditions ([architecture, section 5](architecture.md#5-guardrails-that-exist)) |
| Orchestration: observability | Built | Structured logs without prompts, call records, stage states, the reviewer page, health |
| Orchestration: metrics | Built | `GET /v1/metrics`. Recovery time and rollback frequency are reported as not measured |
| Orchestration: re-planning | Not built | A running run cannot take a changed requirement |
| Output generation | Built | Code and tests, README, requirements, design with diagrams, plan, QA and review documents, evidence files, committed per run |
| Validation and risk control | Partly built | Plain code decides pass or fail from the test report, and checks paths and answer shape. There is no release gate, no criterion-to-test check, no product coverage threshold, and QA and review findings change nothing |
| Controlled autonomy | Partly built | Approvals, budgets, bounded retries, stop, controller-only commits, no push. Delivery is automatic when its preconditions hold |

## Risks, trade-offs and validation

| Risk | Control and trade-off | How it is validated |
| --- | --- | --- |
| Model output varies or is malformed | Repair, retry, plain-code checks, approvals. Quality is not guaranteed | Scripted-model tests for every path. Live runs found real defects (steps 94, 95, 101, 124, 125, 127) |
| Generated code is untrusted | Sandbox container, path checks, no agent file access. Containers share the host kernel and there is no disk limit | `DockerVerificationRunnerTest` checks the flags and lifecycle with a fake Docker. A real container was exercised only in live runs |
| Cost runs away | Call and token budgets per run, bounded retries. Retries spend the same budget | `ModelCallBudgetTest` and a concurrency test |
| Fix attempts do not converge | At most three attempts per stage and three per run. No keep-the-best rollback | Scripted tests of the limits. Two real runs showed the cost (steps 124 and 127, [limitations](limitations.md)) |
| Work is lost on stop or failure | Versioned artifacts, retained branch and workspace, resume | Full-stack stop, retry and resume tests. No crash recovery |
| A bad merge into the checkout | Clean-checkout rule, conflict preflight, only product and evidence paths, never push | `WorkspaceMergeTest` on temporary repositories |
| A thin pipeline is mistaken for a finished one | `SUCCEEDED` is defined narrowly in every document | This summary, the architecture "not built" list and the limitations |
| Coverage below the target | Gate lowered to 85% with named gaps ([ADR 0008](../adr/0008-keep-tests-light-and-gate-coverage-at-85-percent-for-now.md)) | The build gate, the JaCoCo report and the functional coverage report |

## Assumptions

- One trusted local user. There is no authentication.
- The product is Python with the standard library, SQLite and pytest. The platform names the language only in configuration.
- A requirement is small: at most three or four stories, because development is one answer.
- A hosted model is reachable for real runs, with its key in the environment. Tests never call a model.
- Docker is running, the sandbox image is built, and Git is 2.38 or newer.
- This repository's checkout is clean when a run finishes. Otherwise delivery is blocked and the run ends `FAILED` with `DELIVERY_BLOCKED`.
- A person reads the design and the plan before approving them.
- One controller process runs one run at a time.

## How AI assistants were used

| SDLC stage | Role and what it produced | Human control |
| --- | --- | --- |
| Requirements | `requirements-agent` wrote the stories and acceptance criteria in `docs/platform/requirements/`. The platform's own requirements agent does the same job at run time | Criteria are fixed once a step starts. A change needs the user's approval and a "Criterion update" line in the steps log |
| Design | `design-agent` wrote decision records 0004 to 0009 and the architecture overview of step 110. The other [decision records](../adr/) were written in the main session or by a second assistant tool | The user and the assistant discuss each decision before it is recorded |
| Development | `development-agent`, or the main session acting as it, wrote code and tests together and ran `./mvnw verify`. Some steps ran as subagents in parallel worktrees and were merged by the main session | One logical step per turn. The steps log states a done-when line before code. Nothing is committed before the user approves. Commit subjects end with the agent's name |
| Code review | `code-review-agent` reviewed finished features and wrote one row per feature with numbered findings in [reports/review/index.md](../../reports/review/index.md). It does not fix | The user chooses which findings the development agent fixes. Unfixed findings stay open in the index |
| QA | `qa-agent` ran the build, summed the JaCoCo totals and mapped criteria to tests. It writes no file. The functional coverage report is generated by `scripts/functional_coverage.py`, plain code, not a model | QA and review run together, once per feature, only when the user asks |

Other controls on the assistants: `AGENTS.md` holds the rules, `.claude/settings.json` denies `git push`, and hooks block forced adds and hard resets and scan edits for secrets and private terms. Some steps were done by a second assistant tool, and each `Result:` line in the steps log names the tool or agent.

What this process did not cover: the QA and review pass of step 109 ran on the stories coded up to AGT-64 and UI-1. The later work (stage and run retry, brownfield, the worktree arrangement, approvals, stop and resume, delivery and deletions) has had no review pass yet.

## Limitations

The main ones: model dependence, one development answer for the whole product, fix attempts that can regress, a sandbox that shares the host kernel, one controller with no crash recovery, a trusted local environment with no authentication, audit through logs and tables, and an unproved tail of 21 criteria. Each has a mitigation or next step in [limitations.md](limitations.md).
