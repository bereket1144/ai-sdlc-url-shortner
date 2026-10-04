# Run execution requirements

## Request

A claimed run needs to begin executing a workflow. This first story establishes the smallest working graph path before adding more stages.

## Ambiguities and assumptions

- A claimed run has status `RUNNING` and already has an ID and a saved requirement.
- The first node is deterministic so its inputs and execution can be checked without a model call.

## User stories and acceptance criteria

### EXEC-1 Run one workflow node

Status: coded

As the platform, I want a claimed run to execute one workflow node, so that a saved run can begin graph execution.

- AC-EXEC-1.1 Given a `RUNNING` run, the workflow executes one deterministic node using that run's ID and requirement.

## Out of scope

- Polling for runs and starting this workflow automatically.
- Model calls, additional workflow stages, approval gates, and completion or failure transitions.
