# Steps

The record of the work done in this repository, one line per step, in the order it happened. The rules for this file are in `AGENTS.md` under "Steps log".


- [x] 1. Add the JaCoCo plugin to measure coverage. Result: JaCoCo 0.8.14 wired into the pom; agent records `jacoco.exec` and `jacoco:report` writes `target/site/jacoco`; full `verify` not green yet because Docker is not running. (direct)
- [x] 2. Add a root docker-compose.yml that starts PostgreSQL. Result: `postgres:17` healthy on host port 5433 (5432 is taken by a local Postgres), data kept in a named volume; the app is not pointed at it yet. (direct)
- [x] 3. Point the app at the Compose PostgreSQL in application.properties. Result: app starts and connects to localhost:5433 (Flyway sees an empty schema); `./mvnw verify` passes and JaCoCo writes its report. (direct)
- [x] 4. Prove with a test that two LangGraph4j branches run in parallel and the next stage waits for both. Result: `ParallelBranchesJoinTest` passes in `./mvnw verify` and fails when the parallel executor is removed; no production code yet, so coverage is unchanged. (direct)
- [x] 5. Prove with a test that a LangGraph4j run pauses at an approval gate and its checkpoint is saved to PostgreSQL and read back. Result: `ApprovalPauseCheckpointTest` passes in `./mvnw verify` against `postgres:17`; it fails without the interrupt and with an in-memory saver; no production code, so coverage is unchanged. (direct)
- [x] 6. Prove with a test that a paused run resumes after approval on a brand-new graph and saver, running the gate and the task after it exactly once. Result: a second test in `ApprovalPauseCheckpointTest` passes in `./mvnw verify`; it fails when the approval is not recorded and when the run is started over instead of resumed; no production code, so coverage is unchanged. (direct)
- [x] 7. Move the approval graph and its PostgreSQL saver setup into one shared test fixture, so a second JVM can run the same graph. No behaviour change. Result: `ApprovalGraph` fixture used by `ApprovalPauseCheckpointTest`; its two tests pass unchanged. (direct)
- [x] 8. Prove with a test that a run paused at the approval gate in one JVM is recovered at the gate by a second JVM, after the first is killed. Result: `ProcessRestartTest` passes in `./mvnw verify`; the first JVM is killed with SIGKILL after pausing, a second JVM finds the run at the gate and runs no task; it fails for a wrong run id and when the run never pauses; no production code, so coverage is unchanged. (direct)
