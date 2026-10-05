# Scenarios

Three requirements for a reviewer to paste into the platform: the start page at http://localhost:8080/ or
`POST /v1/runs`. They name the product (a URL shortener) because a scenario may; the platform code does not. Start the
controller with the `live` profile first (see the top-level README, sections 3 to 5).

| Order | File | What it shows |
| --- | --- | --- |
| 1 | [greenfield.md](greenfield.md) | A product built from nothing: core APIs and click analytics, at most three stories. Decomposition into stories, design and plan, two approvals, parallel QA, review and documentation, bounded retries, sandbox validation. |
| 2 | [brownfield.md](brownfield.md) | A reliability change on the first run's result (link expiry with an exact boundary and a 410): a `BROWNFIELD` run with a `baseRunId`, impact analysis (needs `agent.stages.impactAnalysis=agent`), and the whole suite run again. |
| 3 | [ambiguous.md](ambiguous.md) | A vague request ("more dependable"): one batch of at most four questions, a wait, and answers that narrow the scope to one small feature (rate limiting). |

Run them in this order: the second and third change a run that the first one (or the second) left `SUCCEEDED`.

## Evidence checklist

For each run keep:

- [ ] the run id and the `GET /v1/runs/<run-id>` response (status, stages, artifacts, verification);
- [ ] the branch `run/<run-id>` and `git log --graph --oneline run/<run-id>` (the `development` and `controller` authors);
- [ ] the folder `runs/<run-id>/` (`artifacts/<kind>-v<version>.json` and `lineage.json`);
- [ ] the questions and answers, and the approvals with the approver name, where the run had them;
- [ ] the metrics (`GET /v1/metrics` or http://localhost:8080/#metrics);
- [ ] a note of anything that failed, was retried or was rejected, and how it ended.

A run of a real model can differ each time; the files say what to expect, and each lists its own limits.
