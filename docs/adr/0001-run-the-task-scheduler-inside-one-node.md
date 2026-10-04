# 0001. Run the task scheduler inside one graph node

Status: accepted

## Context

The planner decides how many tasks a run has, so the number of tasks that can run side by side is only known while the run is going. The workflow itself is a LangGraph4j graph.

In LangGraph4j 1.8.27 the branches of a parallel step are separate `addEdge` calls made before the graph is compiled. A conditional edge returns one node name, a `Command` names one next node, and a conditional edge on a parallel node is rejected. There is no way to ask the graph for "one branch per task in this list".

## Decision

The graph stays fixed (`planner -> executor -> reporter`). The executor node is a plain-Java scheduler: it reads the planned tasks from the state, runs them side by side, and returns only when all have finished, so the next stage always gets every result.

`RuntimeFanOutTest` shows this with one compiled graph that runs 3 tasks and then 5.

## Consequences

- The number of tasks is free at run time and the graph never changes.
- Individual tasks are not graph nodes, so LangGraph4j does not checkpoint them. Their state has to be saved by the scheduler itself, in PostgreSQL.
- Failure handling for tasks is the scheduler's job: a crashed task must fail the node, never count as a pass.
- Parallel branches whose number is fixed (for example, documentation next to testing) can still be real graph branches.

## Update: relation to the SDLC stage graph

The `planner -> executor -> reporter` graph above showed that the number of tasks can change at run time without changing the graph. It is not the platform's stage topology. The active stage topology is the SDLC graph in `docs/platform/requirements/sdlc-flow.md`. The decision still holds: the task scheduler described here belongs inside the Development node of that graph, which runs the planned tasks and returns only when all have finished.
