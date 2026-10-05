# Ambiguous scenario: "make it more dependable"

A deliberately vague request. It shows decomposition (the questions and your answers narrow an open wish to one
concrete feature before any story is written), orchestration (the run pauses in `WAITING_FOR_INPUT` after one batch of
at most four questions and goes on only when every question is answered, then the usual approvals, parallel stages,
join and bounded retries) and validation (the narrowed feature is built and tested in a container). This file names
the product because a scenario may; the platform code does not.

## How to run it

The request is about an existing product, so run it last, on a `SUCCEEDED` run from `scenarios/brownfield.md` (or
`scenarios/greenfield.md`) as the base, and send the page's **Change an existing run** form or this call:

```sh
curl -s -X POST http://localhost:8080/v1/runs -H 'Content-Type: application/json' -d '{
  "kind": "BROWNFIELD",
  "baseRunId": "<the id of the SUCCEEDED run>",
  "requirement": "Make the link shortener more dependable. People say it is not reliable enough when it is busy or when something goes wrong, and I want to trust it more."
}'
```

With no earlier run, start a fresh greenfield run (no `kind`, no `baseRunId`) and begin the text with: "Build a small
link-shortening service in Python with the standard library, SQLite and pytest, and make it dependable." The rest is
the same. Start the controller as in `scenarios/brownfield.md` if you also want the impact analysis.

Then expect, in order:

1. The run becomes `WAITING_FOR_INPUT`. The page shows up to four questions together; through the API read
   `GET /v1/runs/<run-id>/questions`. It is one batch only: nothing else happens until you answer every question.
2. Answer all of them: the page's **Send answers**, or `POST /v1/runs/<run-id>/replies` with
   `{"answers": [{"questionId": "Q-1", "answer": "..."}, ...]}`. The run goes on by itself.
3. Stories that reflect your answers, and a list of assumptions for what you left open.
4. Approve the design, then the plan (`POST /v1/runs/<run-id>/decisions`), as in `scenarios/greenfield.md`.
5. Development and verification, QA, review and documentation in parallel, export, delivery, `SUCCEEDED`.

## Requirement

> Make the link shortener more dependable. People say it is not reliable enough when it is busy or when something goes
> wrong, and I want to trust it more.

## Example answers

The questions are the model's, so the wording differs from run to run. Answer them to narrow the scope to one small
feature, rate limiting per client with a fixed limit:

| If it asks about | Answer |
| --- | --- |
| Which kind of dependability | Only protection against overuse: limit how many links one client can create. Nothing else now. |
| Who is a client | The IP address of the connection. |
| The limit | At most 10 `POST /links` requests per client in a fixed 60 second window. Redirects and stats are not limited. |
| The response and how to test it | 429 with a `Retry-After` header. Read the clock in one place so that tests set it. In-memory counters are enough. |

## What a good run shows

- [ ] Requirement understanding: at most four questions, one batch; the stories name only rate limiting.
- [ ] Task decomposition: the plan tasks follow the narrowed stories; unanswered points are listed as assumptions.
- [ ] Orchestration: the wait holds no thread (the questions are stored), and the run continues without a second batch.
- [ ] Human approvals of the design and the plan, as in the greenfield run.
- [ ] Bounded retries and validation in the sandbox, as in the other scenarios.
- [ ] Lineage, the `development` and `controller` commits, and the evidence under `runs/<run-id>/`.

## Expected limits

- The model decides whether to ask and what to ask. A vague request makes questions likely, not certain; if it asks
  none, the run still lists its assumptions, but you did not see the batch. Start again, or make the request vaguer.
- The model decides the code quality; one development answer builds the whole change, so keep the answers narrow.
- A broad answer ("add retries, health checks and backups") widens the scope and risks a failing build.

## Capture the evidence

Save the run id, the questions (`GET /v1/runs/<run-id>/questions`) with the answers you gave, the requirements artifact
(its version 1 holds the questions, the later version the stories), the branch `run/<run-id>`, `runs/<run-id>/`, and
the metrics (`GET /v1/metrics`).
