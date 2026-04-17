# Tinyflow 工作流引擎架构分析

## 一、项目整体结构及各组件作用

### 1. 模块结构

| 模块 | 作用 |
|------|------|
| **tinyflow-core** | 核心引擎模块，包含工作流定义、状态管理、节点执行、事件系统等核心功能 |
| **tinyflow-node** | 扩展节点模块，包含 Groovy 脚本节点和 QLExpress 表达式节点 |
| **tinyflow-support-springai** | Spring AI 集成支持，提供 LLM 能力 |
| **tinyflow-support-langchain4j** | LangChain4j 集成支持 |
| **tinyflow-support-agentsflex** | AgentsFlex 集成支持 |
| **tinyflow-support-solonai** | Solon AI 集成支持 |

### 2. 核心组件

#### Chain（工作流执行器）
- 工作流执行的核心类，负责任务调度、状态管理、节点执行
- 维护 `ChainState`（工作流状态）和 `NodeState`（节点状态）
- 提供 `start()`、`resume()`、`suspend()` 等生命周期方法

#### ChainDefinition（工作流定义）
- 定义工作流的静态结构，包含节点列表（`List<Node>`）和边列表（`List<Edge>`）
- 提供获取起始节点、根据ID查找节点/边等方法

#### Node（节点基类）
- 抽象节点类，所有具体节点（如 LlmNode、HttpNode）继承此类
- 支持循环执行（`loopEnable`）、重试机制（`retryEnable`）、执行条件（`condition`）
- 抽象方法 `execute(Chain chain)` 由子类实现具体逻辑

#### Edge（边/连接）
- 连接两个节点，定义执行流向
- 支持条件判断（`EdgeCondition`），实现分支逻辑

---

## 二、工作流执行机制

### 执行流程

```java
// 1. 启动工作流
chain.start(variables);

// 2. 调度入口节点
List<Node> startNodes = definition.getStartNodes();
for (Node startNode : startNodes) {
    scheduleNode(startNode, null, TriggerType.START, 0);
}

// 3. 节点执行
executeNode(Node node, Trigger trigger) {
    // 检查状态、执行节点、处理结果
    nodeResult = node.execute(this);
    handleNodeResult(node, nodeResult, triggerEdgeId, error);
}

// 4. 调度下一个节点
scheduleNextForNode(node, result) {
    // 检查循环条件 -> 调度向外节点
    scheduleOutwardNodes(node, result);
}
```

### 触发器调度机制
- **TriggerScheduler**：核心调度器，管理所有节点触发
- **Trigger**：封装触发信息（节点ID、边ID、触发类型、触发时间）
- **TriggerType**：START、NEXT、RETRY、LOOP、RESUME、CHILD、PARENT、SELF

---

## 三、状态更新机制

### 工作流状态（ChainStatus）

```
READY(0) -> RUNNING(1) -> [SUSPEND(5)] -> SUCCEEDED(20)/FAILED(21)/CANCELLED(22)
```

| 状态 | 说明 |
|------|------|
| READY | 初始状态，尚未执行 |
| RUNNING | 执行中 |
| SUSPEND | 暂停（等待外部输入） |
| ERROR | 错误（中间状态，可重试） |
| SUCCEEDED | 成功完成（终态） |
| FAILED | 失败结束（终态） |
| CANCELLED | 已取消（终态） |

### 节点状态（NodeStatus）

```
READY -> RUNNING -> SUCCEEDED/FAILED/SUSPEND
```

### 状态更新方式

采用**乐观锁 + 重试机制**：

```java
public ChainState updateStateSafely(ChainStateModifier modifier) {
    // 30秒超时，指数退避重试
    while (System.currentTimeMillis() - startTime < timeoutMs) {
        current = chainStateRepository.load(stateInstanceId);
        EnumSet<ChainStateField> updatedFields = modifier.modify(current);
        
        // 尝试更新，版本号冲突则重试
        if (chainStateRepository.tryUpdate(current, updatedFields)) {
            return current;
        }
        sleepUninterruptibly(calculateNextRetryDelay(attempt, maxRetryDelayMs));
    }
}
```

---

## 四、节点间数据传递

### 数据存储结构

**ChainState.memory**：`ConcurrentHashMap<String, Object>`，全局共享内存

```java
// 节点执行结果存储格式
memory.put(node.getId() + "." + key, value);

// 示例：llmNode 的输出存储为
// "llmNode1.output" -> "生成的文本内容"
```

### 参数解析机制

```java
// 1. 固定值参数（FIXED）
value = TextTemplate.of(parameter.getValue()).formatToString(...);

// 2. 引用参数（REF）
value = this.resolveValue(parameter.getRef());  // 从 memory 中读取

// 3. 输入参数（INPUT）
// 等待用户输入，缺失则抛出 ChainSuspendException
```

### 数据流向

```
节点A执行 -> 结果存入 ChainState.memory -> 
节点B通过 parameter.ref 引用 -> 解析为实际值 -> 节点B执行
```

---

## 五、异步并行执行能力

### 线程池设计（ChainRuntime）

```java
// 节点执行线程池
NODE_POOL_CORE = 32
NODE_POOL_MAX = 512
队列容量 = 10000

// 调度线程池
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1)
```

### 并行执行特性

1. **天然并行**：多个无依赖的节点可同时被调度到线程池执行
2. **异步调度**：`TriggerScheduler` 将任务提交到 `ExecutorService` 异步执行
3. **Loop 节点**：内部子节点形成独立的执行上下文，支持嵌套循环

### 执行模式

```java
// scheduleNode 将任务提交到调度器
public void scheduleNode(Node node, String edgeId, TriggerType type, long delayMs) {
    Trigger trigger = new Trigger();
    // ... 设置触发信息
    getTriggerScheduler().schedule(trigger);
}
```

---

## 六、持久化支持

### 状态持久化接口

| 接口 | 作用 |
|------|------|
| `ChainStateRepository` | 工作流状态存储 |
| `NodeStateRepository` | 节点状态存储 |
| `TriggerStore` | 触发器存储 |

### 默认实现（内存模式）

```java
// InMemoryChainStateRepository - 单机内存存储
private static final Map<String, ChainState> chainStateMap = new ConcurrentHashMap<>();

// InMemoryNodeStateRepository - 节点状态内存存储
private static final Map<String, NodeState> chainStateMap = new ConcurrentHashMap<>();
```

### 持久化扩展

通过实现 `ChainStateRepository` 和 `NodeStateRepository` 接口，可对接：
- **Redis**：分布式缓存
- **MySQL/PostgreSQL**：关系型数据库
- **MongoDB**：文档数据库

---

## 七、高并发安全设计

### 1. 乐观锁机制

```java
// ChainState 包含 version 字段
private long version;

// 更新时检查版本号
boolean tryUpdate(ChainState newState, EnumSet<ChainStateField> fields);
```

### 2. 分布式锁（ChainLock）

```java
// 获取锁
ChainLock lock = chainStateRepository.getLock(instanceId, timeout, unit);
if (!lock.isAcquired()) {
    throw new ChainLockTimeoutException();
}

// try-with-resources 自动释放
try (ChainLock lock = chainStateRepository.getLock(...)) {
    // 执行临界区代码
}
```

### 3. 本地锁实现（LocalChainLock）

```java
// 全局锁表，带引用计数
private static final Map<String, LockRef> GLOBAL_LOCKS = new ConcurrentHashMap<>();

// ReentrantLock 保证线程安全
private final ReentrantLock lock;
```

### 4. 线程安全数据结构

- `ConcurrentHashMap`：状态存储
- `CopyOnWriteArrayList`：监听器列表
- `AtomicInteger`：计数器（triggerCount、executeCount）

### 5. 冲突退避策略

```java
private long calculateNextRetryDelay(int attempt, long maxDelayMs) {
    // 指数退避：10ms * (2^(attempt-1))
    long baseDelay = 10L * (1L << (attempt - 1));
    
    // 添加随机抖动 ±25%，避免惊群效应
    double jitterFactor = 0.75 + (Math.random() * 0.5);
    
    return Math.max(1L, Math.min(delayWithJitter, maxDelayMs));
}
```

---

## 八、工作流引擎功能总结

### 核心功能

| 功能 | 说明 |
|------|------|
| **可视化编排** | 通过 JSON 定义工作流，支持节点和边的可视化配置 |
| **多节点类型** | LLM节点、HTTP节点、代码节点、知识库节点、循环节点、模板节点等 |
| **条件分支** | 边条件（EdgeCondition）和节点条件（NodeCondition）支持复杂分支逻辑 |
| **循环执行** | LoopNode 支持迭代执行，支持最大次数限制和跳出条件 |
| **重试机制** | 节点失败自动重试，支持最大重试次数和重试间隔 |
| **暂停/恢复** | 支持等待外部输入（SUSPEND），可随时恢复执行 |
| **事件驱动** | 完整的事件系统（ChainStartEvent、NodeEndEvent等） |
| **错误处理** | 全局错误监听、节点错误监听、异常传播机制 |

### 高级特性

1. **父子工作流**：支持嵌套调用，子流程状态自动同步到父流程
2. **算力消耗统计**：支持按表达式计算积分/资源消耗
3. **参数验证**：必填参数检查，缺失时自动挂起等待输入
4. **模板渲染**：Enjoy 模板引擎支持动态文本生成
5. **多LLM支持**：可对接 Spring AI、LangChain4j、AgentsFlex 等

### 适用场景

- AI Agent 编排
- 业务流程自动化
- 数据处理管道
- 审批工作流
- 定时任务调度

---

## 总结

Tinyflow 是一个设计精良的**轻量级工作流引擎**，采用**事件驱动架构**和**状态机模式**，通过**乐观锁 + 分布式锁**保证高并发安全，支持**水平扩展**（通过自定义 Repository 实现），非常适合集成到 Java Web 应用中实现智能代理编排。
