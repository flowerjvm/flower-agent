# Implementation Plan

## Phase 0: boundary and executable skeleton

Status: completed in the initial `0.1.0` implementation.

- one `flower-agent-core` artifact;
- Agent run, thread, turn, message, model, tool, transcript, budget, retry, and
  completion contracts;
- transient Flower Flow;
- sequential non-blocking tool execution;
- protocol-complete tool-call transcripts, including synthetic cancellation of
  unexecuted calls;
- delayed current-turn retry with cancellable model and tool handles;
- deterministic tests with fake model and tool handles.

Exit condition: tests prove the happy-path loop and every terminal path closes
the current assistant tool-call batch.

Result: covered by `AgentRunFlowFactoryTest`, including happy path,
retry/backoff, timeout, external cancellation, unknown tool, turn/tool/usage
budgets, interrupt, completion-policy failure, and pre-submission purity.

## Phase 1: local model adapter

Status: completed in the initial `0.1.0` implementation.

- OpenAI-compatible chat and tool-call encoding;
- cloud, proxy, vLLM, NIM, and Ollama-compatible endpoint configuration;
- cancellation and provider deadline behavior;
- token and finish-reason mapping;
- strict request validation that rejects dangling or duplicate tool results;
- no model fallback policy in this module.

Exit condition: a deterministic full Agent Flow test proves
`model ToolCall -> ToolResult -> next model turn -> final answer`, including
the exact OpenAI-compatible request bodies. Focused tests cover generated call
ids for permissive local providers, HTTP retryability, reserved body fields,
strict transcript rejection, and cancellation.

## Recipe track R0: module boundary and ReAct DSL

Status: completed in the current source tree for the next minor release.

- keep provider-neutral contracts in `flower-agent-core` with no Flower runtime
  dependency;
- move the existing ReAct Flow and its deterministic tests to
  `flower-agent-recipes`;
- provide `AgentFlows.react(spec)` and `AgentRecipe` as a small construction
  DSL over the ordinary Flow;
- preserve direct `AgentRunFlowFactory` access for hosts that need lower-level
  construction;
- add payload-light, sequenced Agent lifecycle events and a non-blocking sink;
- prove event ordering and prove that observation failure cannot change run
  behavior.

Exit condition: the Recipe-built run passes the same protocol, budget, timeout,
retry, interruption, and cancellation tests as the original Flow, while core
compiles without `flower-core`.

The next reusable loop is added to `flower-agent-recipes`, not to another Maven
module. It should be accepted only after at least two concrete host workflows
need meaningfully different control flow that cannot be expressed by changing
the prompt, Tool Registry, or existing policies. Candidate shapes include
evaluator/optimizer and planner/executor, but neither is committed yet.

See [Agent Recipe Development](RECIPES.md).

Before releasing `0.2.0`, update `flower-agent-samples` to depend on
`flower-agent-recipes`, migrate its direct factory wiring to the Recipe DSL,
run the live-compatible smoke test, and publish all three artifacts together.

## Observation track O1: Studio-ready execution read model

Add monitoring in layers without coupling UI or storage to execution:

- generic Flower Flow definition snapshots and Step transition events;
- an optional Agent event store and projection API;
- run list, run detail, timeline, Tool-call, usage, error, and interrupt views;
- explicit redaction and opt-in payload capture;
- a separate Studio application that renders Steps and transitions while
  highlighting the actual execution route.

Exit condition: one built-in Recipe and one custom host Flow can be displayed
from the same Flower execution model, with Agent detail overlaid only where it
exists.

## Phase 2: durable run and resume

Add `flower-agent-persistence-jdbc` and, if the idle-wait scale requires it,
an event-loop backend.

- `AgentRunStore` and JDBC implementation;
- transcript persistence;
- persisted model/tool operation lifecycle;
- interrupt, user-input, approval, cancellation, and deadline resume;
- restart-during-in-flight tests;
- duplicate event and dispatch-once tests.

Exit condition: a process restart during model wait, tool wait, and interrupt
resumes the same operation without duplicate dispatch.

## Phase 3: governed action tools

Add an optional Action Runtime bridge. Do not make
`flower-action-runtime` a mandatory core dependency.

- turn `ToolCall` into `ActionProposal`;
- carry tool-call identity into idempotency;
- map denied, pending approval, accepted, failed, and succeeded outcomes to
  `ToolResult` or agent interrupt;
- keep action execution retry inside Action Runtime.

Exit condition: a mutating tool cannot bypass policy, approval, idempotency, or
audit.

## Phase 4: optional integrations

Create a module only when at least one real host needs it:

- `flower-agent-tools`: reusable, mostly read-only tool implementations;
- `flower-agent-mcp`: MCP protocol adapter;
- `flower-agent-spring-boot-starter`: wiring and lifecycle only;
- `flower-agent-test`: reusable public fakes and assertions;
- `flower-agent-samples`: runnable local-model and governed-action examples.

Admin UI, RAG, vector databases, and model serving remain outside this
repository.
