# Agent Recipe Development

## Purpose

`flower-agent-recipes` is the home for reusable Agent loop implementations.
It exists now even though ReAct is the only built-in recipe, so the contract
and dependency direction are clear before more loop styles appear.

```text
flower-agent-core
  Agent contracts and observation events
          |
          v
flower-agent-recipes
  reusable loop DSL + ordinary Flower Flow implementations
          |
          v
flower-core
  Flow, Step, StepResult, Worker, Engine
```

The module is an authoring and implementation layer, not another runtime.
Every recipe must create a normal Flower `Flow`; Flower remains the only
component that ticks Steps and applies transitions.

## Current recipe

`AgentFlows.react(spec)` builds the standard model/tool loop:

```text
prepare context
  -> call model
  -> complete, or execute requested tools
  -> return tool results to context
  -> call model again
```

The fluent builder binds an `AgentSpec`, `AgentModelGateway`, `ToolRegistry`,
`TranscriptStore`, `Clock`, and optional `AgentEventSink`. Calling `build()`
returns an `AgentRecipe`; calling `createRun(...)` creates one ordinary
`AgentRunFlow` for submission to Flower.

## Where the next recipe goes

The second and later reusable recipes go into `flower-agent-recipes`, beside
ReAct. Do not create one Maven module per recipe.

Plausible future recipes include evaluator/optimizer, planner/executor, and
supervisor/worker loops. These names are examples, not committed APIs.

A new recipe is justified when all of the following are true:

- its loop topology or state progression is meaningfully different from an
  existing recipe;
- at least two concrete host workflows need the same control structure;
- prompt changes, tool selection, or policy configuration cannot express the
  difference cleanly;
- it can run entirely as Flower Flow and Step behavior without a second
  scheduler or executor;
- it reuses the contracts in `flower-agent-core` where their semantics fit.

Do not add a recipe merely to package a domain prompt, a particular Tool set,
or one application's approval sequence. Those belong to the host application.

## Public vocabulary

Recipe APIs use Flower's existing execution vocabulary:

- recipe produces `Flow`;
- phase is implemented by `Step`;
- routing is returned as `StepResult`;
- repetition routes to an earlier Step;
- waiting uses Flower wait or pollable Step behavior;
- completion is a terminal Step result.

The project does not introduce a second public Node/Edge execution model.
A future Studio may draw Steps and their possible or observed transitions as a
graph, but that visualization does not change the runtime model.

## Observation and future Studio

Recipes publish payload-light `AgentEvent` records for run, turn, model-call,
and tool-call lifecycle points. Event sinks must be non-blocking and enqueue
storage or network work elsewhere.

A future Studio should build read models from two sources:

- generic Flower Flow definition and Step transition events;
- Agent-specific lifecycle events from the recipe.

This keeps monitoring outside execution. Prompt or Tool payload capture must
be an explicit, redacted opt-in because those values may contain credentials
or business data.

## Compatibility

Adding a recipe is additive. Changing the semantics of an existing recipe is
not. ReAct changes require deterministic tests for event order, transcript
protocol closure, budgets, retry, cancellation, interrupt, and completion.
