# Implementation Plan

## Phase 0: boundary and executable skeleton

Status: completed in the initial `0.1.0-SNAPSHOT` skeleton.

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

Add `flower-agent-model-openai-compatible` only after the core loop stabilizes.

- OpenAI-compatible chat and tool-call encoding;
- vLLM, NIM, and Ollama compatibility tests;
- cancellation and provider deadline behavior;
- token and finish-reason mapping;
- strict request validation that rejects dangling or duplicate tool results;
- no model fallback policy in this module.

Exit condition: the same core test scenario runs against a local test server.

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
