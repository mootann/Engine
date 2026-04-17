package dev.tinyflow.core.test;

import dev.tinyflow.core.chain.Chain;
import dev.tinyflow.core.chain.ChainDefinition;
import dev.tinyflow.core.chain.Event;
import dev.tinyflow.core.chain.listener.ChainEventListener;
import dev.tinyflow.core.chain.repository.ChainDefinitionRepository;
import dev.tinyflow.core.chain.repository.InMemoryChainStateRepository;
import dev.tinyflow.core.chain.repository.InMemoryNodeStateRepository;
import dev.tinyflow.core.chain.runtime.ChainExecutor;
import dev.tinyflow.core.parser.ChainParser;

import java.util.HashMap;
import java.util.Map;

/**
 * 执行流程：
 *   1. 解析 JSON 格式的工作流定义 → ChainDefinition
 *   2. 创建 ChainExecutor（工作流执行器）
 *   3. 注入 ChainDefinitionRepository（工作流定义存储）
 *   4. 注入状态存储（ChainState 和 NodeState 的内存实现）
 *   5. 调用 executor.execute() 执行业务逻辑
 */
public class TinyflowTest {

    /**
     * 工作流 JSON 定义
     * 节点：
     *   - id="3", type="startNode"  : 开始节点，定义输入参数 name
     *   - id="2", type="llmNode"    : 大模型节点，使用 llmId="gpt-4"
     *   - id="4", type="endNode"    : 结束节点，输出结果
     *
     * 边：
     *   - source="3", target="2"    : 开始节点 → LLM 节点
     *   - source="2", target="4"    : LLM 节点 → 结束节点
     *
     * 数据流：
     *   StartNode 输出 name="michael"
     *        ↓
     *   LlmNode 接收 {{3.name}}，调用大模型处理
     *        ↓
     *   EndNode 接收 {{2.param}}（LLM 输出）并输出
     */
    static String data1 = "{\"nodes\":[{\"id\":\"2\",\"type\":\"llmNode\",\"data\":{\"title\":\"大模型\",\"description\":\"处理大模型相关问题\",\"expand\":true,\"outputDefs\":[{\"id\":\"pyiig8ntGWZhVdVz\",\"dataType\":\"Object\",\"name\":\"param\",\"children\":[{\"id\":\"1\",\"name\":\"newParam1\",\"dataType\":\"String\"},{\"id\":\"2\",\"name\":\"newParam2\",\"dataType\":\"String\"}]}]},\"position\":{\"x\":600,\"y\":50},\"measured\":{\"width\":334,\"height\":687},\"selected\":false},{\"id\":\"3\",\"type\":\"startNode\",\"data\":{\"title\":\"开始节点\",\"description\":\"开始定义输入参数\",\"expand\":true,\"parameters\":[{\"id\":\"Q37GZ5KKvPpCD7Cs\",\"name\":\"name\"}]},\"position\":{\"x\":150,\"y\":25},\"measured\":{\"width\":306,\"height\":209},\"selected\":false},{\"id\":\"4\",\"type\":\"endNode\",\"data\":{\"title\":\"结束节点\",\"description\":\"结束定义输出参数\",\"expand\":true,\"outputDefs\":[{\"id\":\"z7fOwoTjQ7AbUJdm\",\"ref\":\"3.name\",\"name\":\"test\"}]},\"position\":{\"x\":994,\"y\":218},\"measured\":{\"width\":334,\"height\":209},\"selected\":false,\"dragging\":false}],\"edges\":[{\"markerEnd\":{\"type\":\"arrowclosed\",\"width\":20,\"height\":20},\"source\":\"3\",\"target\":\"2\",\"id\":\"xy-edge__3-2\"},{\"markerEnd\":{\"type\":\"arrowclosed\",\"width\":20,\"height\":20},\"source\":\"2\",\"target\":\"4\",\"id\":\"xy-edge__2-4\"}],\"viewport\":{\"x\":250,\"y\":100,\"zoom\":1}}";

    public static void main(String[] args) {
        // =============================================================
        // 第 1 步：准备输入变量
        // =============================================================
        Map<String, Object> variables = new HashMap<>();
        variables.put("name", "michael");

        // =============================================================
        // 第 2 步：创建解析器（解析 JSON → ChainDefinition）
        // =============================================================
        // withDefaultParsers(true) 表示加载内置的节点解析器
        ChainParser chainParser = ChainParser.builder()
                .withDefaultParsers(true)
                .build();

        // =============================================================
        // 第 3 步：创建工作流执行器
        // =============================================================
        //   a) ChainDefinitionRepository
        //      工作流定义仓库，根据 ID 返回工作流定义
        //
        //   b) ChainStateRepository
        //      工作流实例状态存储（如 RUNNING, SUSPEND, SUCCEEDED 等）
        //
        //   c) NodeStateRepository
        //      节点状态存储（如重试次数、循环次数等）
        ChainExecutor executor = new ChainExecutor(
                new ChainDefinitionRepository() {
                    @Override
                    public ChainDefinition getChainDefinitionById(String id) {
                        // 解析 JSON 为工作流定义对象
                        ChainDefinition definition = chainParser.parse(data1);
                        definition.setId(id);
                        return definition;
                    }
                }
                , new InMemoryChainStateRepository()  // 状态存储（内存）
                , new InMemoryNodeStateRepository()   // 节点状态存储（内存）
        );

        // =============================================================
        // 第 4 步：注册事件监听器（可选）
        // =============================================================
        // 监听工作流执行过程中的事件，如节点开始、结束、失败等
        // 常见事件：NODE_START, NODE_END, CHAIN_START, CHAIN_END, ERROR 等
        executor.addEventListener(new ChainEventListener() {
            @Override
            public void onEvent(Event event, Chain chain) {
                System.out.println(event.toString());
            }
        });

        // =============================================================
        // 第 5 步：执行工作流
        // =============================================================
        // 参数说明：
        //   - "1"                          : 工作流实例 ID（用于状态存储的 key）
        //   - variables                    : 输入变量，StartNode 的 name 参数会接收 "michael"
        //
        // 执行过程：
        //   1. 根据 ID 从仓库获取 ChainDefinition
        //   2. 创建 Chain 实例
        //   3. 从 StartNode 开始执行
        //   4. 根据边流向下一个节点
        //   5. 最终到达 EndNode
        Map<String, Object> result = executor.execute("1", variables);
        System.out.println("执行结果：" + result);
    }
}
