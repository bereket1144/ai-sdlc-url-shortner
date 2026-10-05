# Brownfield scenario: link expiry with an exact boundary

A reliability change to the product that an earlier run built. It shows decomposition (the change is traced through the
existing files before it is designed, then planned as a small set of tasks), orchestration (a run of kind `BROWNFIELD`
whose branch starts from the earlier run's branch, with the same approvals, parallel stages, join and bounded
retries) and validation (the whole test suite, the earlier tests included, runs again in a container, so a regression
fails the run). This file names the product because a scenario may; the platform code does not.

## How to run it

First finish `scenarios/greenfield.md` until the run is `SUCCEEDED`. Its branch `run/<id>` must still exist in this
repository, because the change run starts from it. That `id` is the `baseRunId`. The controller answers 400 when the base
run does not exist, has not `SUCCEEDED` or has no stored commit.

The impact analysis stage (which reads the existing files and lists affected files, behaviours and risks) makes a model
call only when `agent.stages.impactAnalysis=agent` is set. The live profile leaves it off, so it is a placeholder
unless you start the controller like this (README, "Request a change to an existing result"):

```sh
export OPENROUTER_API_KEY='your-api-key'
cd agent-controller
./mvnw spring-boot:run -Dspring-boot.run.profiles=live -Dspring-boot.run.arguments="--agent.stages.impactAnalysis=agent"
```

On the page: tick **Change an existing run**, choose the earlier run from the list or paste its id, paste the
requirement below and press **Start run**. Or, on the earlier run's page, send the requirement from the bottom chat bar.
Then expect, in order: questions if the model asks (at most four, one batch; answer them), the stories, the impact
analysis, the design (approve), the plan (approve), development and verification, QA, review and documentation in
parallel, the document export, delivery, `SUCCEEDED`. The calls for questions, replies and decisions are the ones in
`scenarios/greenfield.md`. The start call:

```sh
curl -s -X POST http://localhost:8080/v1/runs -H 'Content-Type: application/json' -d '{
  "kind": "BROWNFIELD",
  "baseRunId": "<the id of the SUCCEEDED run>",
  "requirement": "Change the link-shortening service that is already in this repository, so that links stop working when they should. `POST /links` accepts an optional field `expiresAt`, a UTC time written like `2030-01-01T00:00:00Z`. It answers 400 for a value that is not such a time, and for a time that is not in the future. A link works strictly before its expiry instant and is expired at that instant and after it. `GET /{code}` answers 410 for an expired code, and still answers 302 for a link that has not reached its expiry. A link without `expiresAt` never expires. A request for an expired link is not counted as a click, and `GET /links/{code}/stats` still answers for it. The service reads the current time in one place that a test can set, so that the tests prove the boundary (one instant before the expiry, the expiry itself, one instant after) without waiting. Keep every existing behaviour and every existing test passing, and add tests for the new behaviour."
}'
```

## Requirement

> Change the link-shortening service that is already in this repository, so that links stop working when they should.
> `POST /links` accepts an optional field `expiresAt`, a UTC time written like `2030-01-01T00:00:00Z`. It answers 400
> for a value that is not such a time, and for a time that is not in the future. A link works strictly before its
> expiry instant and is expired at that instant and after it. `GET /{code}` answers 410 for an expired code, and still
> answers 302 for a link that has not reached its expiry. A link without `expiresAt` never expires. A request for an
> expired link is not counted as a click, and `GET /links/{code}/stats` still answers for it. The service reads the
> current time in one place that a test can set, so that the tests prove the boundary (one instant before the expiry,
> the expiry itself, one instant after) without waiting. Keep every existing behaviour and every existing test passing,
> and add tests for the new behaviour.

## What a good run shows

- [ ] Requirement understanding: criteria for the exact boundary, the 400 cases, the 410 and the no-click rule.
- [ ] Task decomposition: an impact analysis naming the existing files and behaviours touched, and a plan that follows it.
- [ ] Parallel stages and join, and human approvals of the design and the plan, as in the greenfield run.
- [ ] Bounded retries: a build that fails on an old or a new test is retried with the failing tests, within the limits.
- [ ] Validation in the sandbox: one verification result for the whole suite; the boundary tests do not wait or sleep.
- [ ] Lineage: `GET /v1/runs/<run-id>/lineage` shows which artifact versions each result used; the run reads back
      with `kind` `BROWNFIELD` and the `baseRunId`.
- [ ] Commits: the earlier run's commits come first in `git log --graph --oneline run/<run-id>`, then new commits by
      `development` and `controller`; the earlier branch is unchanged.
- [ ] Evidence under `runs/<run-id>/` for the new run, next to the earlier run's folder.

## Expected limits

- The model decides the code quality; it may need a retry to keep every old test passing.
- Development changes everything in one answer, so this change is kept to one feature.
- Without the impact analysis setting the stage is not a real analysis; say which way the run was started.
- The base run's branch must exist, and delivery needs a clean checkout.

## Capture the evidence

Save both run ids, the branch `run/<run-id>`, `runs/<run-id>/`, the metrics, and
`git diff run/<base-run-id>...run/<run-id> -- apps/url-shortener/` (the change only).
