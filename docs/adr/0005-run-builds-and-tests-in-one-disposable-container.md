# 0005. Run builds and tests in one disposable container started with the docker command line

Date: 2026-10-04

Status: accepted

## Context

Generated code is untrusted. It has to be built and tested, and the verdict has to come from plain code, never from a model. AGENTS.md already required throwaway sandbox containers and said that only the controller starts containers. On 2026-10-04 (step 86) the run setup was settled: only PostgreSQL runs in Docker Compose, the controller runs on the host, and no Docker client library is added.

## Decision

Each verification of a run uses one new container. `DockerVerificationRunner` drives it through a small port, `DockerCommand`, whose one implementation (`ProcessDockerCommand`) starts the `docker` command line as a process:

1. `docker create` with the label `io.github.bereket1144.agentcontroller.sandbox=true`, user `10001:10001`, `--network none`, memory and swap both 1g, 1 CPU, 256 processes, the configured image and the configured test command. No environment variable is passed, so no credential reaches the container.
2. `docker cp` copies the run's workspace into `/workspace`. No host folder is mounted.
3. `docker start -a` runs the tests under a time limit (5 minutes by default).
4. `docker cp` copies one JUnit report out. A report that is missing, unreadable or late is NO_RESULT, which counts as a failure.
5. `docker rm -f` removes the container in a `finally` block, which also kills one that ran over time. `--rm` is not used, because Docker would delete the container before the report could be copied out.

The image is built outside the application (`docker/sandbox-python`: Python 3.12, pytest pinned, a non-root user with id 10001). The image, the command, the report path and the limits are configuration under `sandbox.verify.*`; the code names no language or test tool. The exit code of the container is not the verdict: plain code parses the report. Only the controller starts containers.

## Consequences

- Generated code never runs on the host, has no network and cannot see host files. The limits on memory, CPU, processes and time stop a runaway test.
- No new dependency, and the container steps are testable with a fake `DockerCommand`.
- The controller needs access to the Docker daemon, which is a strong privilege, and containers share the host kernel. This is a documented limitation, not VM-level isolation.
- A crash of the controller between create and remove leaves a labelled container behind. The label makes it findable; automatic removal at startup is not built.
- With no network, everything the tests need must already be in the image. One image means one language (Python) for now.
- What the tests print is thrown away, not logged, because it comes from generated code.

## Alternatives considered and rejected

- A Docker client library. A new dependency that AGENTS.md rules out, for four commands the command line already provides.
- Mounting the workspace into the container. A mount lets generated code change host files; copying in and out does not.
- Running the tests on the host in a virtual environment. Untrusted code would get the host's network and files.
- A long-lived sandbox service in Compose. Compose starts PostgreSQL only, and a shared container would carry state from one run to the next.
