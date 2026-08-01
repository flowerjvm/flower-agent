# Architecture

## One sentence

`flower-agent` is the Agent-specific state and loop layer that runs on Flower.

It is not a second runtime beside Flower. It builds a Flower Flow whose Steps
advance an `AgentRun`.

## Ecosystem boundary

| Component | Unit of work | Owns |
| --- | --- | --- |
| `flower-core` | Flow | Step execution, transitions, worker lanes, lifecycle |
| `flower-ai-harness` | One AI task | output validation, refine, task retry, model fallback |
| `flower-agent` | Agent run | turns, model/tool loop, transcript, budget, completion |
| `flower-action-runtime` | Business action | registry, policy, approval, idempotency, execution audit |

The three retries have different meanings:

| Owner | Retry meaning |
| --- | --- |
| AI Harness | The whole AI task result is invalid, so perform the task again. |
| Agent | The current model turn failed, so retry that same turn within its policy. |
| Action Runtime | A governed business action execution failed, so apply action retry policy. |

None of these retries implies either of the other two.

`CompletionPolicy` is an Agent-loop routing policy. It decides whether one
model response completes the run, requests tools, continues, or interrupts; it
does not validate or refine the host's final structured output.

When a host needs final JSON schema validation, refinement, model fallback, or
whole-task retry, it may wrap one completed `AgentRun` as the inner operation
of a `flower-ai-harness` task. A whole-run retry can repeat tool requests.
Agents with mutating tools must therefore propagate a stable outer-task
idempotency scope into `flower-action-runtime`; a model-generated tool-call id
alone is not guaranteed to remain stable across separate agent runs.

## Core contracts

The first core keeps only the contracts needed to run one transient loop:

- `AgentRun`, `AgentThread`, `AgentTurn`, `AgentMessage`;
- `AgentModelGateway`, `AgentModelCall`, request and response records;
- `AgentTool`, `ToolRegistry`, `ToolCall`, `ToolResult`;
- `TranscriptStore`, `ContextBuilder`;
- `AgentBudget`, `CompletionPolicy`, `ModelTurnRetryPolicy`;
- `AgentRunFlowFactory` and `AgentRunFlow`.

The core contains interfaces for model, tool, transcript, and policy boundaries.
It does not contain a provider SDK, MCP client, JDBC schema, Spring
auto-configuration, or domain tools.

## OpenAI-compatible model adapter

`flower-agent-model-openai-compatible` is an optional adapter outside core. It
maps an `AgentModelRequest` to a non-streaming OpenAI-compatible
`/chat/completions` request and returns a pollable `AgentModelCall`.

Unlike the similarly named AI Harness provider, this adapter preserves the
complete Agent protocol:

- system, user, assistant, and tool message roles;
- model-facing function definitions and JSON schemas;
- assistant `tool_calls` and matching tool-result messages;
- multiple tool calls in one model response;
- usage, finish reason, and provider trace metadata;
- HTTP and transport failure details for retry policy decisions.

Host-facing Tool names remain domain identifiers. Names such as
`atcss.log.search` are deterministically encoded to provider-safe function
aliases and decoded back before Registry lookup, because strict OpenAI
function names do not permit dots.

Before dispatch, it rejects a transcript with dangling, duplicate, unknown, or
mismatched tool-call identities. It uses JDK asynchronous HTTP and never waits
inside a Flower Worker tick.

## Tool versus action

`ToolRegistry` is the surface shown to the model. It answers:

> Which named capabilities may this model request in this run?

`ActionRegistry` in `flower-action-runtime` is the governed business-action
surface. It answers:

> Which side effect exists, and under which policy, approval, idempotency, and
> audit rules may it execute?

These registries are intentionally different. A read-only in-memory lookup may
be a direct `AgentTool`. A refund, notification, database write, or production
change must be implemented as an Agent Tool adapter that submits an
`ActionProposal` to `flower-action-runtime`.

```text
model ToolCall
     |
     v
AgentTool adapter
     |
     v
ActionProposal -> flower-action-runtime -> governed executor
     |
     v
ToolResult returned to transcript
```

The tool-call id should be carried into the action idempotency key together
with a stable host or outer-task scope.

## Current execution model

The initial Flow uses ordinary `flower-core`:

```text
initialize-run
  -> prepare-context
  -> await-model-turn
  -> interpret-decision
  -> execute-tools
  -> prepare-context
  -> ...
  -> finalize-run
```

Model and tool ports return pollable handles. A Step starts work once and
observes it on later ticks. It never waits synchronously.

`ContextBuilder` runs outside the session monitor but still runs on a Worker
tick. Its contract permits only bounded, in-memory transcript selection. RAG,
database, HTTP, model, and tool work must be prepared through asynchronous
ports and observed by pollable Steps.

The transcript preserves the model protocol, not just human-readable text:

- an assistant message stores the exact tool calls it declared;
- each declared call receives exactly one terminal tool-result message;
- a call skipped by interruption, cancellation, failure, or budget exhaustion
  receives a synthetic `CANCELLED` result;
- `ContextBuilder` may select context, but it is not responsible for repairing
  an invalid tool-call sequence.

This first Flow is explicitly transient. A process restart loses in-flight
handles. Durable support must persist:

- run and turn state;
- transcript;
- provider and tool operation ids;
- operation status and terminal result or failure;
- deadlines and interrupt/resume tokens.

Recovery must observe the same operation and must not redispatch solely because
an in-memory handle disappeared.

The current core can produce and expose a `resumeToken`, but it does not yet
provide a resume command that consumes the token. That arrives with the durable
run lifecycle in Phase 2.

## Feedback-control interpretation

- P-like correction: a failed tool result is written back to the transcript so
  the next model turn can correct its immediate decision. A bounded model-turn
  retry handles transient failure of the current turn.
- I-like correction: aggregate run metrics and repeated error patterns belong
  to later observability and tuning modules, with operator-controlled policy
  changes.
- D-like protection: sudden failure or tool-call spikes belong to host
  circuit-breaker and Action Runtime policy/interlock integration.

The initial core implements only the local P-like loop and hard budgets. It
does not pretend to be a centralized PID controller.

### Future feedback extension

P/I/D-like behavior describes feedback over different time horizons. It is an
architectural analogy, not a promise to embed a numerical PID controller in the
Agent runtime.

| Feedback horizon | Example data | Where it is applied |
| --- | --- | --- |
| P-like, current run | latest tool result, current error, immediate state change | the next model turn through the transcript |
| I-like, across runs | repeated incidents, action success rate, accumulated failure count | host-provided context, memory, or operator-controlled policy |
| D-like, rate of change | rising error rate, shorter recurrence interval, tool-call spike | host safeguards, circuit breakers, or Action Runtime policy/interlocks |

The core should make these extensions possible without owning their storage,
aggregation, or domain policy. Stable extension points may include:

- run, turn, model-call, and tool-call events for external observation;
- bounded context contributions prepared outside the Worker tick and supplied
  through `ContextBuilder`;
- replaceable budget, completion, and retry policies;
- host and Action Runtime checks that can restrict or redirect requested work.

A future host or optional integration module may derive feedback from run
history, metrics, action audit records, or domain telemetry. It can then return
that feedback to a later run as model context or as deterministic policy input:

```text
Agent events and domain telemetry
              |
              v
external history / metrics / trend analysis
              |
              +--> context contribution --> later AgentRun
              |
              +--> deterministic policy --> budget, circuit breaker, action gate
```

Raw long-term history should not be appended blindly to every prompt. The host
must select, summarize, bound, and timestamp relevant feedback. Safety or
authorization decisions must remain deterministic policy checks; model context
may explain those decisions but must not replace them.

No `flower-agent-feedback` module is planned merely for symmetry. Such a module
should be introduced only after multiple real hosts demonstrate the same data,
aggregation, and injection contract.
