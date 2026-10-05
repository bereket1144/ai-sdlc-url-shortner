# 0004. Build a thin end-to-end skeleton first, then add the governance

Date: 2026-10-04

Status: accepted

## Context

By 2026-10-04 the foundations existed: the run API, run execution, a dummy stage graph with a parallel join, the role catalog, the model port with prompts, answer checks, retries, fallback, a call budget, call records, replay and versioned artifacts. No stage had yet run on a real model, and no generated code had been built and tested. The agent pipeline requirements held 57 open stories after the scope cut of step 85, in an order that finished approvals, per-task development, gates, recovery and audit before a single real run was possible. Time was limited. The problems that only a real run shows (answer size, hidden reasoning tokens, the shape of model output) would have appeared last.

## Decision

Build one thin path through the whole pipeline first, as one story, AGT-62 (steps 87 and 88): a model client for a hosted model, a real agent for every stage, development that writes files into a per-run workspace and commits them, a sandboxed build and test whose result ends the run, read operations that Postman can call, a scripted end-to-end test and an opt-in live one. Then add the governance on top of that path, story by story, in order of value.

The path is deliberately small. Development runs once for all stories, the open questions of every role except requirements become assumptions, and a run ends SUCCEEDED when the build and the tests pass. Until a control is built, the documents say plainly that it is missing and the meaning of SUCCEEDED is not widened (see `docs/platform/architecture.md` and `docs/platform/limitations.md`).

## Consequences

- The first real run, on 2026-10-05, went through every stage in about 100 seconds with seven model calls and no repairs, and 17 generated tests passed. It showed three problems at once, while they were cheap to fix: an answer cut off at the limit (step 94), hidden reasoning using the answer limit (step 95), and empty fields from the other phase failing a check (step 101).
- Every later control has a real flow to attach to: gates at the edges of the graph and after the development step, approvals at the clarification pause.
- Cost: SUCCEEDED means only that the stages returned and the generated tests passed. QA and review findings change nothing, and there is no repair loop, no approval and no stop operation.
- Cost: the order of the requirements file no longer matches the order of the build, and the stories coded earlier (AGT-6 to AGT-12, AGT-16) had no QA and review pass when this was written. Coverage work was deferred as well (ADR 0008).
- Risk: thin work can be mistaken for finished work. The list of governance that is not built, in the architecture overview, is the guard against that.

## Alternatives considered and rejected

- Finish the pipeline story by story in the order of the requirements file. No real run until late, so the integration problems would have shown at the end.
- Build all the governance over the dummy stage nodes first. It would prove routing only, not real model output, files or builds.
- Show a hand-built demonstration outside the platform. The platform is the deliverable.
