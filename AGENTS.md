# Repository Guide

`flower-agent` is a Flower-native agent loop library, not another workflow
engine.

## Invariants

- Keep `flower-agent-core` small, provider-neutral, and independent of Flower
  runtime types. It owns Agent contracts, not a loop implementation.
- Flower owns Flow and Step execution.
- `flower-agent-recipes` owns reusable Agent loop implementations and their
  small construction DSL. Every recipe creates an ordinary Flower Flow; it
  never owns a scheduler, Worker, executor, or checkpoint store.
- Keep the built-in ReAct loop in `flower-agent-recipes`. Add later reusable
  loop styles to that same module after concrete host use proves that their
  control flow is genuinely different. Keep one-off business workflows in the
  host application as ordinary Flower Flows and Steps.
- Worker ticks never block on model, tool, network, process, or human work.
- `ContextBuilder` performs bounded in-memory selection only; it never performs
  RAG, database, network, model, or tool I/O.
- Every assistant-declared tool call has exactly one terminal tool-result
  message before a run leaves the batch.
- A model-facing tool is not automatically authorized to mutate business data.
- Mutating tools must delegate to a governed boundary such as
  `flower-action-runtime`.
- Agent-turn retry, AI Harness task retry, and Action Runtime execution retry
  are different policies and must not be merged.
- Observation sinks return immediately, are thread-safe, and never alter Agent
  execution when observation fails.
- Do not add provider, MCP, persistence, Spring, sample, or UI code to core.

## Verification

Run:

```powershell
mvn -B -ntp verify
```

Tests must use deterministic Flower ticks and must not sleep.
