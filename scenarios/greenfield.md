# Greenfield scenario: core APIs and click analytics

The platform builds a small product from nothing. It shows decomposition (the requirement becomes at most three
stories with acceptance criteria, then a design and a task plan), orchestration (one fixed graph of stages, two human
approvals, QA, review and documentation side by side and then joined, bounded retries) and validation (the generated
tests run in a throwaway container, and plain code, not a model, decides pass or fail). This file names the product
because a scenario may; the platform code does not.

## How to run it

Start the controller with the `live` profile (README, section 3) and open http://localhost:8080/.

1. Paste the requirement below into **Requirement** and press **Start run**.
2. If the run is `WAITING_FOR_INPUT` with questions (at most four, in one batch), answer every one and submit. The
   model decides whether to ask; this requirement is meant to need few or none.
3. Open the design artifact, enter your name, press **Approve**. Do the same for the plan.
4. Watch development and verification: the files are committed, then built and tested in a container.
5. QA, review and documentation run in parallel and join; the documents are exported; the work is delivered into
   `apps/url-shortener/` and the run is `SUCCEEDED`.

The same through the API (`<run-id>` is the `id` of the 201 response):

```sh
curl -s -X POST http://localhost:8080/v1/runs -H 'Content-Type: application/json' -d '{
  "requirement": "Build a small link-shortening web service in Python, using only the standard library, SQLite for storage and pytest for tests. Keep the scope to at most three user stories. It must: create a short code for a URL with `POST /links`, which takes a JSON body with a field `url` and answers 201 with a JSON body with a field `code`; redirect `GET /{code}` to the original URL with status 302, or answer 404 for an unknown code; refuse a missing `url`, or one that is not an http or https URL, with status 400; and count every redirect as a click and report the count with `GET /links/{code}/stats`, which answers 200 with a JSON body with a field `clicks`, or 404 for an unknown code. Put all the code and the tests flat in the root of the repository, with the tests in a folder `tests/`, so that `python -m pytest`, run from the root, finds and runs every test without network access."
}'
# then: GET /v1/runs/<run-id> (status, stages, artifacts, verification), and when it waits:
# GET  /v1/runs/<run-id>/questions
# POST /v1/runs/<run-id>/replies    {"answers": [{"questionId": "Q-1", "answer": "..."}]}
# POST /v1/runs/<run-id>/decisions  {"decision": "APPROVE", "approver": "Your name", "comment": ""}
```

## Requirement

> Build a small link-shortening web service in Python, using only the standard library, SQLite for storage and pytest
> for tests. Keep the scope to at most three user stories. It must: create a short code for a URL with `POST /links`,
> which takes a JSON body with a field `url` and answers 201 with a JSON body with a field `code`; redirect
> `GET /{code}` to the original URL with status 302, or answer 404 for an unknown code; refuse a missing `url`, or one
> that is not an http or https URL, with status 400; and count every redirect as a click and report the count with
> `GET /links/{code}/stats`, which answers 200 with a JSON body with a field `clicks`, or 404 for an unknown code. Put
> all the code and the tests flat in the root of the repository, with the tests in a folder `tests/`, so that
> `python -m pytest`, run from the root, finds and runs every test without network access.

## What a good run shows

- [ ] Requirement understanding: stories with numbered acceptance criteria, and listed assumptions or answered questions.
- [ ] Task decomposition: a plan artifact whose tasks point back to stories and criteria.
- [ ] Parallel stages and join: QA, review and documentation overlap in time, and `collect` starts after all three.
- [ ] Human approvals: the design and the plan each wait for a decision, recorded with your name.
- [ ] Bounded retries: a failed build or a malformed answer is attempted again a limited number of times, then the run
      fails with a reason (stage retry, run retry and rejections each have a limit, see README).
- [ ] Validation in the sandbox: a stored verification result with test counts, from a container with no network.
- [ ] Lineage: `GET /v1/runs/<run-id>/lineage` shows which artifact versions each result used and which files serve
      which criteria.
- [ ] Commits: `git log --author=development` shows the generated code, `git log --author=controller` the documents and
      the merge, in `git log --graph --oneline run/<run-id>`.
- [ ] Evidence under `runs/<run-id>/`: `artifacts/<kind>-v<version>.json` and `lineage.json`.

## Expected limits

- The model decides the code quality and the tests; the platform only checks paths, builds and runs them.
- Development answers once for the whole change, so keep the scope small; this requirement is the size that has worked.
- A failing build is retried with the failing tests as input; if the limit is spent the run is `FAILED` and can be
  retried from its page. A second run of the same text can differ.
- The controller verifies the product but does not start its server.

## Capture the evidence

Save the run id, the branch `run/<run-id>`, the folder `runs/<run-id>/`, the `GET /v1/runs/<run-id>` response, and the
metrics (`GET /v1/metrics` or http://localhost:8080/#metrics). Run `git diff HEAD...run/<run-id> -- apps/url-shortener/`
to see what was delivered.
