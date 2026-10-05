# SDLC flow requirements

## Request

Replace the single-node workflow with a connected graph of the platform's SDLC roles. For this first story, each role performs deterministic dummy work so the route and state handoff can be proven before model calls or artifact generation are added.

## Ambiguities and assumptions

- The named roles are requirements, impact analysis, design, planning, development, QA, code review, and documentation. Impact analysis runs only for brownfield work.
- QA, code review, and documentation follow development as parallel branches. Their completion order is not fixed, so state contributions are identified by role rather than by their position in a concatenated string.
- A `SUCCEEDED` run in this story means the dummy graph reached its end. It does not mean a product or SDLC artifact was created.
- The review node here represents an initial review. Final review of a frozen candidate is later work.
- **Run kind is stored, not yet chosen by the caller.** Each run stores a kind, `GREENFIELD` or `BROWNFIELD`. Every run stored before this story becomes `GREENFIELD`. `POST /v1/runs` does not change in this story: it takes no kind, so every run created through the API is `GREENFIELD`, and the API document stays as it is. Tests create `BROWNFIELD` runs through the service. **Limitation:** an API caller cannot start a brownfield run yet. Letting the caller choose the kind is a later story and will change `POST /v1/runs` and its API document.
- **This story supersedes the single-node topology.** The graph in this story is the active workflow. It replaces the one-node graph that AC-EXEC-1.1 in `run-execution.md` describes. EXEC-1 stays as a historical milestone and its criterion is not rewritten; the same fact (the active graph receives the run ID and requirement) is now proved by AC-FLOW-1.1 and AC-FLOW-1.3.
- **Relation to ADR 0001.** The `planner -> executor -> reporter` graph in `docs/adr/0001-run-the-task-scheduler-inside-one-node.md` showed that the number of tasks can change at run time. It is not the stage topology. The SDLC graph here is. The future task scheduler from that ADR belongs inside the Development node.
- **State keys.** Inputs: `runId`, `requirement`, `kind`. Each executed node adds one value under its own role key: `requirements`, `impactAnalysis`, `design`, `planning`, `development`, `qa`, `review` (the code review node), `documentation`. The value is the role name followed by `:done` (for example `qa:done`). Two helper values are not role keys: `receipt` (set by the initialize node; it contains the requirement and must never be logged) and `joined` (set by the collect node).

## User stories and acceptance criteria

### FLOW-1 Traverse the SDLC nodes with dummy state

Status: done

As the platform, I want a run to move through the named SDLC nodes while each node adds a visible dummy contribution to shared graph state, so that the complete route and state handoff are proven before the agents do real work.

- AC-FLOW-1.1 Given a greenfield run, the graph passes the run ID and requirement through requirements, design, planning, and development in that order. After development it runs QA, code review, and documentation, which AC-FLOW-1.4 allows to overlap. Each required node executes exactly once, shown by a per-node invocation count in the tests.
- AC-FLOW-1.2 Given a brownfield run, impact analysis executes exactly once, after requirements and before design, and its `impactAnalysis` value is present for the later nodes. Given a greenfield run, impact analysis executes zero times and there is no `impactAnalysis` value.
- AC-FLOW-1.3 Each executed node reads the values contributed by its predecessors and adds its own role-named value (see "State keys") without removing earlier values. At the end, the graph state contains `runId`, `requirement`, `kind`, and one value for every executed node.
- AC-FLOW-1.4 QA, code review, and documentation start after development and overlap in time: a test holds each branch open until all three have started, so it can only pass if they run at the same time. The graph does not reach its end until all three have finished, and the `qa`, `review`, and `documentation` values are all present whichever finishes first.
- AC-FLOW-1.5 When the dummy graph reaches its end through live dispatch, the run is persisted as `SUCCEEDED`.
- AC-FLOW-1.6 A run stores its kind. A run created through `POST /v1/runs` without a kind, and every run stored before this story, has kind `GREENFIELD`. A run created through the service as `BROWNFIELD` reads back as `BROWNFIELD`, and live dispatch gives its workflow that kind. The request body of `POST /v1/runs` may also name a kind and a base run (AC-AGT-50.1).

### FLOW-2 Show dummy node progress in logs

Status: done

As an operator, I want to see each dummy node's output in the run logs, so that I can follow the route and confirm which state markers reached the join.

- AC-FLOW-2.1 Each executed dummy node logs its run ID, node name, output key, and the role markers visible to that node. The `collect` log includes the QA, review, and documentation markers together. A greenfield run has no impact-analysis node log.
- AC-FLOW-2.2 Node logs do not contain the requirement text or the `receipt` value, which includes that text.

## Out of scope

- Model calls, generated files, patches, builds, coverage reports, and real SDLC decisions.
- Letting an API caller choose the run kind (a later story; it will change `POST /v1/runs`).
- Human approval gates, repair loops, bounded retries, final review, and release readiness.
