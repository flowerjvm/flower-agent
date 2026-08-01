# flower-agent

Small Flower-native agent loop library for Java 21.

`flower-agent` turns a model API into an application agent by repeatedly
calling the model, dispatching the tools it requests, returning tool results,
and stopping when the run reaches a terminal decision or budget.

The model may be provided by a cloud API or by a local inference server. The
location and vendor do not matter to the core: an `AgentModelGateway` adapter
maps that API to the provider-neutral model-turn contract.

```text
cloud model API -------------------+
                                    |
local vLLM / NIM / Ollama API -----+--> AgentModelGateway
                                              |
                                              v
                                       flower-agent loop
                                              |
                                              v
                                    application AgentTools
```

The repository is currently an early core implementation. It contains the
agent contracts and a transient Flower Flow, but it does not yet contain an
OpenAI-compatible provider adapter, durable persistence, or Spring Boot
auto-configuration.

## What it is

A single model call can produce one answer. An agent run can take several
model turns and use application capabilities between those turns:

```text
user request
    -> model turn
    -> tool calls
    -> application tool results
    -> next model turn
    -> final answer
```

`flower-agent` owns that loop and its Agent-specific state. Flower still owns
Flow and Step execution.

Use it when:

- a JVM application needs to turn a cloud or local model API into a multi-turn
  tool-using agent;
- the model should use tools implemented by the host application rather than a
  fixed built-in tool catalog;
- run, thread, turn, transcript, budget, timeout, cancellation, and completion
  state should be explicit;
- model and tool work must be observed without blocking Flower Worker ticks.

It is not the right layer for a one-shot structured AI call, a general workflow
engine, business-action authorization, RAG storage, or model serving.

## How it fits

`flower-agent` gives an application explicit concepts for multi-turn agent
execution while Flower remains the workflow engine.

```text
Flower
  = executes Flow and Step

flower-agent
  = defines AgentRun, Turn, model turn, tool loop, transcript, budget,
    interrupt state, and completion judgment

Host application
  = owns prompts, domain data, RAG, model deployment, UI, and business policy
```

Typical composition:

| Component | Role |
| --- | --- |
| `flower-core` | Executes the Agent Flow and its Steps. |
| `AgentModelGateway` adapter | Calls the selected cloud or local model API. |
| `flower-agent` | Runs model turns, tool calls, transcript, budgets, and completion. |
| Host application | Supplies prompts, AgentTools, domain services, and RAG. |
| `flower-action-runtime` | Optionally governs mutating tools with policy, approval, idempotency, retry, and audit. |
| `flower-ai-harness` | Optionally wraps a completed AgentRun for final structured-output validation, refinement, or whole-task fallback. |

For example, a read-only log-search tool can call an application query service
directly. A refund or equipment-stop tool should adapt the model's ToolCall to
an ActionProposal and delegate the actual governed change to
`flower-action-runtime`.

The initial project intentionally contains one deployable module:

| Artifact | Purpose |
| --- | --- |
| `flower-agent-core` | Agent contracts plus a transient Flower Flow implementation. |

Provider, MCP, JDBC, Spring Boot, reusable tool, public testkit, and sample
modules are deferred until a concrete integration requires them.

## Bring your own tools

The core is capability-less by default. It does not ship with business tools.
Each host application implements and registers the `AgentTool` instances its
work requires:

```java
ToolRegistry tools = new InMemoryToolRegistry(List.of(
        customerLookupTool,
        orderStatusTool
));
```

The loop is generic; the registered tools determine what the agent can ask to
do. Registration only exposes a capability to the model. It does not grant
business authorization. A mutating tool must delegate to a governed boundary
such as `flower-action-runtime`.

## Usage shape

At the current core level, a host supplies a model gateway, a tool registry,
and a transcript store, then creates a Flow for one user message:

```java
ToolRegistry tools = new InMemoryToolRegistry(List.of(
        searchAtcssLogTool,
        pauseEquipmentTool
));

AgentSpec agent = AgentSpec.of(
        "incident-agent",
        "local-model",
        "Investigate equipment incidents and explain each conclusion."
);

AgentRunFlowFactory factory = new AgentRunFlowFactory(
        modelGateway,
        tools,
        new InMemoryTranscriptStore()
);

AgentRunFlow run = factory.createFlow(
        agent,
        AgentThread.create(),
        AgentMessage.user("Investigate the latest ARMG214 incident.")
);

// Submit run.flow() through the host's Flower Engine/Worker wiring.
```

The host can observe `run.run()` for status and counters, inspect
`run.transcript()` for the conversation and tool protocol, or call
`run.cancel(reason)`.

The planned `flower-agent-model-openai-compatible` module will provide a common
gateway for compatible cloud and local endpoints. Until that module exists, a
host must implement `AgentModelGateway` and its pollable `AgentModelCall`.

## Boundary

`flower-agent` owns:

- agent run, thread, turn, and message state;
- non-blocking model-turn submission and observation;
- the model-facing tool registry and tool-call loop;
- transcript and context construction;
- turn, tool, token, and elapsed-time budgets;
- agent interrupt state and final completion judgment;
- retry of the current model turn.

It does not own:

- general workflow execution: `flower-core`;
- final structured AI-task validation and model fallback: `flower-ai-harness`;
- business action permission, approval, idempotency, execution retry, and
  audit: `flower-action-runtime`;
- RAG and vector storage: the host application;
- model inference: vLLM, NIM, Ollama, or a provider;
- administration UI and deployment.

## Agent loop

```text
initialize-run
      |
      v
prepare-context
      |
      v
await-model-turn  -- non-blocking submit/poll
      |
      v
interpret-decision ---- final answer ----> finalize-run
      |
      | tool calls
      v
execute-tools     -- non-blocking start/poll
      |
      +-------------------------------> prepare-context
```

Every tool call declared by an assistant message receives exactly one terminal
tool result in the transcript. Calls that cannot start because the run is
interrupted, cancelled, failed, or out of budget receive a synthetic
`CANCELLED` result. Providers therefore never see a dangling assistant
`tool_calls` batch on a later turn.

The current implementation is transient and non-restartable. Durable
interrupt/resume requires persisted run, transcript, external-operation, and
deadline state and belongs in a later persistence module. A current
`resumeToken` is observable interruption data; there is not yet an API that
consumes it to resume the run.

## Build

```powershell
mvn -B -ntp verify
```

See [Architecture](docs/ARCHITECTURE.md) and
[Implementation Plan](docs/IMPLEMENTATION_PLAN.md).
