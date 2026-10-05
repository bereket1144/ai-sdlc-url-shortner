# Brownfield scenario

A change to a product that an earlier run built. This file is an input for manual and opt-in live runs. It names the
product because a scenario may; the platform code does not.

## Before you start

Run the walking skeleton scenario (`scenarios/walking-skeleton.md`) and wait until the run is `SUCCEEDED`. Its workspace
must still exist where the controller keeps workspaces, because the change run starts from a clone of it. Note the `id`
of that run: it is the `baseRunId` below. The controller refuses a base run that does not exist, has not `SUCCEEDED` or
has no stored commit, with status 400.

## Requirement

The text of the `requirement` of the change run:

> Change the link-shortening service that is already in this repository. Let a link have an optional expiry.
> `POST /links` accepts an optional field `expiresAt`, a UTC time written like `2030-01-01T00:00:00Z`; it answers 400
> for a value that is not such a time, and for a time that is not in the future. A link works strictly before its expiry
> instant and is expired at that instant and after it. `GET /{code}` answers 410 for an expired link and still answers
> 302 for a link that has not reached its expiry; a link with no `expiresAt` never expires. A request for an expired
> link is not counted as a click, and `GET /links/{code}/stats` still answers for it. The service reads the current time
> in one place, which a test can set, so that the tests prove the boundary without waiting. Keep every existing
> behaviour and every existing test passing, and add tests for the new behaviour.

## How to start it

With Postman or any HTTP client, send `POST /v1/runs` with the header `Content-Type: application/json` and this body,
with the requirement text above and the id of the first run:

```json
{
  "requirement": "<the requirement text above>",
  "kind": "BROWNFIELD",
  "baseRunId": "<the id of the SUCCEEDED run>"
}
```

On the reviewer page, tick "Change an existing run" in the start box, choose the run from the list of runs the page
remembers or paste its id, paste the requirement text, and press "Start run". The page shows the controller's message
when it refuses the base run.

## What a run should leave behind

- A run that reads back with `kind` `BROWNFIELD` and the `baseRunId` that was given.
- Artifacts for requirements and impact analysis (before the design), then design, plan, development, QA, review and
  documentation. The impact analysis, design, planning and development agents were shown the files of the earlier run.
- A git repository for the run that starts with the earlier run's files and history, and holds new commits on top of
  them. The earlier run's repository is not changed.
- A stored verification result that ran the whole test suite, the earlier run's tests included.
