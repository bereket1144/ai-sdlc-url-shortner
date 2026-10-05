# 0002. Call local Ollama behind the model port

Status: accepted

## Context

The SDLC roles need model answers without knowing a provider protocol. The local profile must send explicit model limits and return the provider's finish reason, usage, and failures. A later hosted profile may use a different API.

## Decision

`ModelClient` is the provider-neutral contract. The local adapter uses Spring's HTTP client to call Ollama's non-streaming chat endpoint, with every model setting taken from a validated `ModelProfile`. Prompt construction is separate from the HTTP adapter. Tests use an in-process HTTP stub, and a scripted implementation supplies deterministic answers to later agent tests.

## Consequences

- Roles depend on `ModelClient`, so a hosted provider can have another adapter without changing role code.
- The local adapter owns Ollama request and response mapping, including typed retryable failures.
- A real Ollama call is an opt-in check; the ordinary test suite remains independent of a running model server.
