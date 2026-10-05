# Walking skeleton scenario

The first end-to-end run: one clear requirement for the platform to build and test. This file is an input for manual
and opt-in live runs. It names the product and its language because a scenario may; the platform code does not.

## Requirement

Post this text as the `requirement` of `POST /v1/runs`:

> Build a small link-shortening web service in Python, using only the standard library, SQLite for storage and pytest
> for tests. It must: create a short code for a URL with `POST /links` and a JSON body `{"url": "..."}`, answering 201
> with `{"code": "..."}`; redirect `GET /{code}` to the original URL with status 302, or answer 404 for an unknown
> code; count every redirect as a click and report the count with `GET /links/{code}/stats`; and refuse a URL that is
> not http or https with status 400. Put the code and the tests directly in the product folder, so that
> `python -m pytest`, run from that folder, finds and runs every test without network access.

## What a run should leave behind

- Artifacts for requirements, design, plan, development, QA, review and documentation.
- A commit on the branch `run/<run-id>` of the run's own workspace, with the files under `apps/url-shortener/`.
- A stored verification result of the build and tests, run in a container.
