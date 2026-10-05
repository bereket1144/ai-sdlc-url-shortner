# 0009. Choose the model API style by a profile setting, and switch hidden reasoning off by another

Date: 2026-10-05

Status: accepted

## Context

The model client first spoke two protocols: Ollama's chat API for a local model, and the Anthropic messages API for a hosted one, chosen by whether the profile had an `api-key`. The first real run needed a hosted model reached through OpenRouter, which speaks an OpenAI-style chat API and also takes a key, so the key can no longer tell the style. The first live run also showed that a model that spends tokens on hidden reasoning has less of the answer limit left for the answer: the design stage was cut off at the limit on the first attempt and on both repairs, and the run failed with OUTPUT_INVALID (step 94).

## Decision

Steps 91 to 95.

- A model profile may set `api-style` to `ollama`, `anthropic` or `openai`. When it is absent the old rule applies: a profile with a key is Anthropic and one without is Ollama, so existing profiles do not change.
- `LiveModelClient`, the `ModelClient` of live mode, sends each call to the client for the profile's style. The new `OpenAiCompatibleModelClient` posts to `<base-address>/chat/completions` with the key as a bearer token (the key comes from the environment, never from a file), `max_tokens`, `temperature`, a system message and a user message. It reads the first choice: `stop` is a complete answer, `length` is an answer cut off at the limit (the answer service repairs it), anything else is unreadable. A leading `<think>` block is removed. An error object inside a 200 reply is mapped by its code. Failures use the same typed reasons, the same call slot per profile and the same logging rules as the other clients; no key, prompt or answer is logged.
- A profile may set `reasoning`. When it is `false` the OpenAI-style client sends `reasoning: {enabled: false}`; when it is not set nothing is sent and the model keeps its default.
- The live profile (`application-live.properties`) names one profile, `router`: style `openai`, model `deepseek/deepseek-v4.1-flash` (override with `OPENROUTER_MODEL`), a window of 1,000,000 tokens, an answer limit of 12,000, reasoning off and the key from `OPENROUTER_API_KEY`. The system part of every prompt also states the answer size in words.

## Consequences

- Changing provider or model is a settings change. The roles, the answer checks, retries, fallback and budgets do not change.
- The handoff of 2026-10-05 records that, with reasoning off, the chosen model completed a whole run with no repairs.
- `reasoning` is OpenRouter's convention. How another OpenAI-style server treats it was not checked; it may ignore or reject the field, so it is sent only when a profile sets it.
- Only the first choice of a reply without streaming is read, and no tool calls are used.
- A fallback profile may use any style; the live settings name none.

## Alternatives considered and rejected

- Keep inferring the style from the key. It is ambiguous once two styles take a key.
- Infer the style from the base address. Hosts and gateways change, and the guess would be silent.
- Add a model client library for the new API. Not evaluated. The existing clients call providers through Spring's `RestClient` behind one port (ADR 0002), and the new one follows them.
- Leave reasoning at the model's default and raise only the answer limit. Hidden reasoning would still take part of the limit, and a larger limit costs more per call. The limit was raised too, to 12,000, but switching reasoning off is the actual fix.
