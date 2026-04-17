# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Tinyflow is a Java-based workflow orchestration engine for AI agents. It executes workflows defined as JSON (typically from a frontend visual editor) containing nodes (LLM calls, HTTP requests, code execution, loops, etc.) and edges (connections with optional conditions).

## Build Commands

```bash
# Build all modules
mvn clean package

# Skip tests
mvn package -Dmaven.test.skip=true

# Run a single test
mvn test -Dtest=TinyflowTest -pl tinyflow-core

# Install locally (with javadoc/sources)
mvn clean source:jar install

# Deploy (requires GPG setup)
mvn deploy -Dmaven.test.skip=true -e -P release
```

## Architecture

### Core Execution Flow

1. **ChainExecutor** (`core/chain/runtime/ChainExecutor.java`) - Entry point for executing workflows. Creates Chain instances and manages async execution.
2. **Chain** (`core/chain/Chain.java`) - The runtime executor for a single workflow instance. Manages state, scheduling, and node execution.
3. **ChainDefinition** (`core/chain/ChainDefinition.java`) - Static structure: nodes + edges parsed from JSON.
4. **TriggerScheduler** (`core/chain/runtime/TriggerScheduler.java`) - Schedules node executions on a thread pool (32-512 threads).

### State Management

- **ChainState** - Workflow-level state: status, memory (shared data between nodes), suspend info
- **NodeState** - Per-node state: status, retry count, loop count, trigger history
- **Optimistic locking** with exponential backoff retry (30s timeout, 10ms base delay with ±25% jitter)
- Default implementations are in-memory (`InMemoryChainStateRepository`, `InMemoryNodeStateRepository`)

### Node Types

Nodes extend **BaseNode** (`core/node/BaseNode.java`) which extends **Node** (`core/chain/Node.java`). Each node implements `execute(Chain chain)` returning `Map<String, Object>`.

Built-in nodes: StartNode, EndNode, LlmNode, HttpNode, CodeNode, TemplateNode, LoopNode, ConfirmNode, KnowledgeNode, SearchEngineNode

### Data Flow Between Nodes

Results stored in `ChainState.memory` as `nodeId.keyName -> value`. Subsequent nodes reference via `{{nodeId.keyName}}` syntax in parameters.

### Parameter Resolution

Parameters support three types:
- **FIXED** - Direct value with Enjoy template rendering
- **REF** - Reference to another node's output via `{{nodeId.keyName}}`
- **INPUT** - External input required; missing causes SUSPEND

### LLM Integration

**Llm** interface (`core/llm/Llm.java`) defines the chat contract. Implementations:
- Spring AI (`tinyflow-support-springai`)
- LangChain4j (`tinyflow-support-langchain4j`)
- AgentsFlex (`tinyflow-support-agentsflex`)
- SolonAI (`tinyflow-support-solonai`)

Register via `LlmManager.getInstance().register(llmId, llm)`.

### JSON Format

Workflows follow React Flow-style JSON:
```json
{"nodes": [...], "edges": [...]}
```

Parse with `ChainParser.builder().withDefaultParsers(true).build()`.

### Status Lifecycle

```
READY(0) → RUNNING(1) → [SUSPEND(5)] → SUCCEEDED(20)/FAILED(21)/CANCELLED(22)
```

## Module Structure

| Module | Purpose |
|--------|---------|
| `tinyflow-core` | Core engine: Chain, Node, Parser, State, Events |
| `tinyflow-node` | Groovy/QLExpress script nodes |
| `tinyflow-support-*` | LLM provider integrations |
