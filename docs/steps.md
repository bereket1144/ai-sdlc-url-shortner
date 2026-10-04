# Steps

The record of the work done in this repository, one line per step, in the order it happened. The rules for this file are in `AGENTS.md` under "Steps log".


- [x] 1. Add the JaCoCo plugin to measure coverage. Result: JaCoCo 0.8.14 wired into the pom; agent records `jacoco.exec` and `jacoco:report` writes `target/site/jacoco`; full `verify` not green yet because Docker is not running. (direct)
- [x] 2. Add a root docker-compose.yml that starts PostgreSQL. Result: `postgres:17` healthy on host port 5433 (5432 is taken by a local Postgres), data kept in a named volume; the app is not pointed at it yet. (direct)
- [x] 3. Point the app at the Compose PostgreSQL in application.properties. Result: app starts and connects to localhost:5433 (Flyway sees an empty schema); `./mvnw verify` passes and JaCoCo writes its report. (direct)
- [x] 4. Prove with a test that two LangGraph4j branches run in parallel and the next stage waits for both. Result: `ParallelBranchesJoinTest` passes in `./mvnw verify` and fails when the parallel executor is removed; no production code yet, so coverage is unchanged. (direct)
