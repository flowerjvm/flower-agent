# flower-agent

[![CI](https://github.com/flowerjvm/flower-agent/actions/workflows/ci.yml/badge.svg)](https://github.com/flowerjvm/flower-agent/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.flowerjvm/flower-agent-core.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.flowerjvm/flower-agent-core/0.2.0)

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

The repository is currently an early implementation. It contains
provider-neutral Agent contracts, a built-in transient ReAct recipe, lifecycle
observation events, and an OpenAI-compatible model adapter. It does not yet
contain durable persistence, resume, or Spring Boot auto-configuration.

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

The built-in ReAct recipe owns that loop and its Agent-specific state. Flower
still owns Flow and Step execution.

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
| `flower-agent-core` | Defines provider-neutral Agent state and ports. |
| `flower-agent-recipes` | Creates reusable Agent loops as ordinary Flower Flows. |
| Host application | Supplies prompts, AgentTools, domain services, and RAG. |
| `flower-action-runtime` | Optionally governs mutating tools with policy, approval, idempotency, retry, and audit. |
| `flower-ai-harness` | Optionally wraps a completed AgentRun for final structured-output validation, refinement, or whole-task fallback. |

For example, a read-only log-search tool can call an application query service
directly. A refund or equipment-stop tool should adapt the model's ToolCall to
an ActionProposal and delegate the actual governed change to
`flower-action-runtime`.

The source tree currently contains four modules:

| Artifact | Purpose |
| --- | --- |
| `flower-agent-core` | Agent run, model, tool, transcript, budget, policy, and observation contracts. It has no Flower runtime dependency. |
| `flower-agent-recipes` | Reusable Flower-native loop implementations and their construction DSL. It currently contains ReAct. |
| `flower-agent-observability` | Payload-light adapter from Agent lifecycle events to `FlowerObservationEvent`. |
| `flower-agent-model-openai-compatible` | Async `/chat/completions` gateway with complete Agent message and tool-call mapping. |

Official provider SDK, MCP, JDBC, Spring Boot, reusable tool, public testkit,
and sample modules are deferred until a concrete integration requires them.

The `0.2.0` release separates reusable ReAct construction into
`flower-agent-recipes` and adds the optional common observation adapter in
`flower-agent-observability`. It builds against Flower `0.1.2`.

## Install 0.2.0 From Maven Central

Flower Agent requires Java 21. Add the recipe and model adapter needed by the
host application. Add observability only when the host publishes Agent
lifecycle facts into Flower's common observation stream:

```kotlin
dependencies {
    implementation("io.github.flowerjvm:flower-agent-recipes:0.2.0")
    implementation("io.github.flowerjvm:flower-agent-model-openai-compatible:0.2.0")
    implementation("io.github.flowerjvm:flower-agent-observability:0.2.0")
}
```

```xml
<dependency>
    <groupId>io.github.flowerjvm</groupId>
    <artifactId>flower-agent-recipes</artifactId>
    <version>0.2.0</version>
</dependency>
<dependency>
    <groupId>io.github.flowerjvm</groupId>
    <artifactId>flower-agent-model-openai-compatible</artifactId>
    <version>0.2.0</version>
</dependency>
<dependency>
    <groupId>io.github.flowerjvm</groupId>
    <artifactId>flower-agent-observability</artifactId>
    <version>0.2.0</version>
</dependency>
```

These modules bring in the provider-neutral core transitively. Existing
`0.1.0` consumers should move ReAct construction to `AgentFlows.react(...)`
from `flower-agent-recipes`.

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

## ReAct recipe

A host supplies a model gateway, Tool Registry, and Transcript Store to the
built-in ReAct recipe, then creates a Flow for one user message:

```java
AgentModelGateway modelGateway = new OpenAiCompatibleAgentModelGateway(
        OpenAiCompatibleAgentGatewayConfig
                .builder("http://localhost:8000/v1")
                .build()
);

ToolRegistry tools = new InMemoryToolRegistry(List.of(
        searchAtcssLogTool,
        pauseEquipmentTool
));

AgentSpec agent = AgentSpec.of(
        "incident-agent",
        "local-model",
        "Investigate equipment incidents and explain each conclusion."
);

AgentRecipe recipe = AgentFlows.react(agent)
        .modelGateway(modelGateway)
        .tools(tools)
        .transcripts(new InMemoryTranscriptStore())
        .build();

AgentRunFlow run = recipe.createRun(
        AgentThread.create(),
        AgentMessage.user("Investigate the latest ARMG214 incident.")
);

// Submit run.flow() through the host's Flower Engine/Worker wiring.
```

`AgentFlows` is not an executor. It only constructs an ordinary Flower Flow.
The host can observe `run.run()` for status and counters, inspect
`run.transcript()` for the conversation and tool protocol, or call
`run.cancel(reason)`.

The Recipe boundary is intentionally present while ReAct is the only recipe.
When a second reusable loop such as evaluator/optimizer or planner/executor is
proven by real applications, it belongs in `flower-agent-recipes` beside
ReAct. Domain prompts, Tool bundles, and one-off business workflows remain in
the host application. See [Agent Recipe Development](docs/RECIPES.md).

## Observation and future Studio

Recipes can publish payload-light lifecycle events without coupling execution
to an observability backend:

```java
ConcurrentLinkedQueue<AgentEvent> events = new ConcurrentLinkedQueue<>();

AgentRecipe recipe = AgentFlows.react(agent)
        .modelGateway(modelGateway)
        .tools(tools)
        .transcripts(transcriptStore)
        .events(events::add)
        .build();
```

An event sink must return immediately and enqueue persistence or network work
elsewhere. Events identify Recipes, runs, turns, model calls, Tool calls,
outcomes, and per-run sequence, but omit prompt and Tool payloads by default.

A future Studio can draw ordinary Flower Steps and transitions, then overlay
these Agent events as the actual execution path. This does not require public
Node or Edge runtime concepts. Custom Agent workflows are still authored with
Flower `Flow`, `Step`, and `StepResult`; reusable loop shapes become Recipes.

`flower-agent-observability` maps these native events into the common Flower
observation stream:

```java
AgentEventSink agentEvents = new AgentObservationSinkAdapter(
        flowerObservationSink,
        event -> new AgentObservationCorrelation(outerTraceId, parentRunId));

AgentRecipe recipe = AgentFlows.react(agent)
        .modelGateway(modelGateway)
        .tools(tools)
        .transcripts(transcriptStore)
        .events(agentEvents)
        .build();
```

The adapter preserves run, turn, model-call, Tool-call, usage, retry, and
terminal metadata while excluding prompt, message, failure-text, and Tool
payload bodies by default.

`flower-agent-model-openai-compatible` works with compatible cloud endpoints,
proxies, and local servers such as vLLM, NIM, or Ollama's OpenAI-compatible
API. Supply a base URL ending in `/v1` or the complete
`/chat/completions` URL. API keys are optional, and custom headers are
supported for internal gateways.

```java
OpenAiCompatibleAgentGatewayConfig config =
        OpenAiCompatibleAgentGatewayConfig
                .builder("http://localhost:11434/v1")
                .apiKey(System.getenv("MODEL_API_KEY"))
                .header("X-Tenant", "terminal-a")
                .build();
```

The model name comes from `AgentSpec.modelId()`. Per-agent generation options
such as temperature, token limits, tool choice, and provider-specific extra
body fields can be supplied through the constants in
`OpenAiCompatibleAgentOptions` and `AgentSpec.metadata()`.

Domain tool names may use dotted identifiers such as `atcss.log.search` or
`equipment.pause`. The gateway deterministically maps names that violate an
endpoint's function-name restrictions to provider-safe aliases and maps model
ToolCalls back to the original Registry names.

An opt-in live smoke test can verify a real compatible endpoint without
putting credentials in source control:

```powershell
$env:OPENAI_API_KEY = "..."
$env:OPENAI_BASE_URL = "https://api.openai.com/v1" # optional
$env:OPENAI_MODEL = "gpt-4.1-mini"                 # optional
.\mvnw.cmd -B -ntp -pl flower-agent-model-openai-compatible -am `
    verify -Plive-openai-compatible
```

The live profile forces one tool call and then sends its tool result for a
final answer. It is not active during the normal deterministic build. Never
commit API keys or place them in Maven properties.

### Agent provider versus AI Harness provider

`flower-ai-harness` also has an OpenAI-compatible provider, but the two modules
serve different contracts and are not interchangeable:

| Module | Unit of work | Protocol surface |
| --- | --- | --- |
| `flower-ai-harness-provider-openai-compatible` | One AI task attempt | Prompt messages in, final text out. |
| `flower-agent-model-openai-compatible` | One turn inside an AgentRun | System/user/assistant/tool transcript, tool schemas, tool calls, usage, and finish reason. |

Use the Harness provider for one-shot structured generation and validation.
Use the Agent provider when the model must select application tools and receive
their results over multiple turns. A host may still wrap the completed
AgentRun in an outer AI Harness task when final structured validation or
whole-task fallback is required.

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
