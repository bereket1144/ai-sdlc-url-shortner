# Run execution requirements

## Request

A saved run needs to start a workflow and report when that workflow finishes successfully. The stories establish the graph path, connect a pending run to it, record successful completion, and start pending work automatically.

## Ambiguities and assumptions

- A claimed run has status `RUNNING` and already has an ID and a saved requirement.
- The first node is deterministic so its inputs and execution can be checked without a model call.
- EXEC-2 invokes a dispatch attempt explicitly; EXEC-4 starts dispatch attempts through scheduled polling.
- `SUCCEEDED` means the workflow reached its final successful outcome. Pausing for approval does not count as success.

## User stories and acceptance criteria

### EXEC-1 Run one workflow node

Status: coded

As the platform, I want a claimed run to execute one workflow node, so that a saved run can begin graph execution.

- AC-EXEC-1.1 Given a `RUNNING` run, the workflow executes one deterministic node using that run's ID and requirement.

### EXEC-2 Dispatch one pending run

Status: coded

As the platform, I want one dispatch attempt to start a pending run's workflow, so that a submitted requirement begins execution.

- AC-EXEC-2.1 Given a `CREATED` run, one dispatch attempt starts the workflow with that run's stored ID and requirement.
- AC-EXEC-2.2 When the workflow starts, that run's `RUNNING` status is already persisted.
- AC-EXEC-2.3 Given no `CREATED` run, a dispatch attempt does not start a workflow.

### EXEC-3 Record successful workflow completion

Status: coded

As the platform, I want a run to record successful workflow completion, so that clients can distinguish finished work from work still running.

- AC-EXEC-3.1 Given a `RUNNING` run whose workflow finishes successfully, a later read of that run reports status `SUCCEEDED`.
- AC-EXEC-3.2 Given a workflow that fails before successful completion, the run is not reported as `SUCCEEDED`.

### EXEC-4 Start pending runs automatically

Status: coded

As a user, I want submitted runs to start automatically, so that I do not need to trigger dispatch manually.

- AC-EXEC-4.1 Given a `CREATED` run already saved when the application starts, scheduled polling starts its workflow without a manual dispatch call.
- AC-EXEC-4.2 Given a new `CREATED` run while the application is running, scheduled polling starts its workflow without a manual dispatch call.
- AC-EXEC-4.3 Repeated polling does not start a run again after it has left `CREATED`.

## Out of scope

- Model calls, additional workflow stages, approval gates, and failure-state transitions.
