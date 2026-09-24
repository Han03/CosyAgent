# CosyAgent 源码级学习指南 —— 企业级 ReAct 智能体的工程化落地

> 面向：Java 高级工程师（已掌握 JVM、并发、Spring Boot、设计模式，以下不再赘述 Java 语法级内容）。
> 定位：以 **CosyAgent 的业务流程为主线（学习路线）**，以 **每个环节承载的知识点为章节目标（学习目标）**，
> 讲清"为什么这样设计"和"代码是怎么长出来的"，而非堆砌 API。
> 建议方式：边读本文档边打开对应源码文件对照；每个章节末尾有"设计要点"与"延伸思考"。

---

## 0. 读前须知

### 0.1 这个项目是什么

CosyAgent 是一个 **企业级任务型 AI 智能体（Agent）系统**：

- 不是"聊天机器人接个大模型"——它让模型 **自主规划任务、调用工具、多轮迭代**，最终完成一个任务（查时间、查天气、检索知识库、执行任意注册的工具）。
- 端到端包含：**Spring Boot 服务端 + Flutter 客户端**，双仓库，已实现完整业务闭环。

### 0.2 技术栈总览（这就是你的知识地图）

| 知识域 | 项目里的落地 | 对应章节 |
| --- | --- | --- |
| Spring AI 1.1.8（ChatModel / Prompt / Message 体系） | 模型调用抽象、工具调用协议 | 第 4 章 |
| ReAct 范式编排 | `DefaultReActAgent` 自研循环 | 第 4 章 |
| 多平台模型路由 + 降级 | `ModelRouter` / `ModelRoutingAdmin` | 第 6 章 |
| Redis 多层记忆 | `RedisMemoryStore` / `MemoryLevel` | 第 7 章 |
| PGVector 向量检索（RAG） | `PgVectorKnowledgeStore` | 第 8 章 |
| Resilience4j（重试/熔断/限流/超时/舱壁） | `ResilienceSupport` | 第 9 章 |
| 任务状态机 + 轨迹审计 + 断点恢复 | `TaskStore`（memory/pg/mysql 三实现） | 第 10 章 |
| SSE 流式输出 | `SseEmitter` + 事件协议 | 第 11 章 |
| 端到端 Mock（无真实模型全链路） | `MockScriptEngine` | 第 5 章 |
| MySQL / PostgreSQL 持久化 | 原生 JDBC 双实现 | 第 10 章 |

### 0.3 学习路线图（业务流程 = 路线）

一次对话的完整旅程（这是全文档的主线，每章对应一个站点）：

```
客户端(Flutter)
  │  ① 用户输入 → SSE POST /api/agent/chat/stream
  ▼
AgentController            → 解析请求头(X-Cosy-Mock / X-Cosy-Model)、校验
  ▼
AgentOrchestrator          → 会话惰性创建、任务登记(RUNNING)、组装 AgentContext
  ▼
DefaultReActAgent.run      → 循环：构建提示(记忆+RAG) → 调模型(路由/Mock) → 有工具调用?
  │    ├─ 否 → 输出最终回答，循环结束
  │    └─ 是 → ToolRegistry 执行工具 → 观察结果回传模型 → 下一轮
  ▼
ResilienceSupport          → 每一环（LLM/TOOL/MEMORY/VECTOR/TASK）套容错组合
  ▼
TaskStore.finish           → 落终态(COMPLETED/FAILED/TIMEOUT) + 轨迹审计
  ▼
SSE 事件流                 → thinking → tool → toolResult → answer → done
```

> **学习建议**：先读第 3 章建立整体感，然后按 4→5→6（核心执行链），7→8→9（增强设施），10→11（生产化与体验），12（端侧）。

---

## 1. 一次对话的完整旅程（业务主线总览）

> 学习目标：**在动手看任何类之前，能用自己的话讲清楚一次请求经过了哪些组件、每个组件只做一件事。**

### 1.1 旅程分站

**① 入口站 —— Controller（`AgentController`）**
- 职责：HTTP 协议翻译。校验参数、解析两个请求头、调编排层。
- 两个特殊请求头是"请求级覆盖"机制：
  - `X-Cosy-Mock: true|false` → 本次请求强制开/关 Mock（覆盖全局配置），这是"开关即插拔"的关键设计。
  - `X-Cosy-Model: auto|平台/模型` → 本次请求指定模型（覆盖后端默认链），这是"配置权威在后端、选择权给前端"的关键设计。
- 为什么 Controller 这么薄？**HTTP 细节与业务编排分离**——换协议（gRPC/WebSocket）时编排层零改动。

**② 编排站 —— Orchestrator（`AgentOrchestrator`）**
- 职责：**任务生命周期管理**，不关心"模型怎么思考"。
- 四件事：会话惰性创建（无 sessionId 就生成 `s-xxxx`）→ `createTask` 登记任务 → 组装 `AgentContext`（一次执行的全部参数）→ 执行后落终态。
- 它握着"状态机"的钥匙：`markRunning` / `finish` 是唯二改变任务状态的地方。

**③ 大脑站 —— ReAct 循环（`DefaultReActAgent`）**
- 职责：**自主规划与迭代**。循环执行"思考→行动→观察"，直到给出最终答案或触发终止条件。
- 每轮做三件事：调模型（拿到 Thought + 可能的 Action/toolCalls）→ 有工具调用就执行并观察 → 把观察结果作为下一轮上下文。
- 这是全文最核心的一章，详见第 4 章。

**④ 设施站 —— 记忆 / 知识 / 容错 / 持久化**
- 记忆：运行前注入"它还记得什么"，运行后存"这轮聊了什么"。
- 知识（RAG）：运行前检索知识库，命中就注入系统提示。
- 容错：LLM 调用、工具调用、记忆、检索、任务落库**五条链路**各自套 Resilience4j 组合策略。
- 持久化：任务主记录 + 完整推理轨迹（trace），支持断点恢复。

**⑤ 出口站 —— 结果与流式**
- 同步接口 `/chat`：一次性返回 `AgentResult`（兼容/测试用）。
- 流式接口 `/chat/stream`：SSE 逐事件推送（生产形态，见第 11 章）。

### 1.2 领域模型速览（先认识这些 record）

| 类型 | 字段 | 说明 |
| --- | --- | --- |
| `AgentContext` | sessionId / userId / maxIterations / taskId / mockOverride / modelChoice | 一次执行的"参数包"，贯穿整个 ReAct 循环 |
| `AgentMessage` | role / content / toolCallId / toolName / toolArguments / timestamp | 统一消息载体：USER/ASSISTANT/TOOL/SYSTEM 四角色 |
| `AgentResult` | sessionId / answer / state / trace / iterations / costMs / errorMessage / taskId | 一次执行的产出：答案 + 完整轨迹 |
| `AgentState` | RUNNING / COMPLETED / FAILED / TIMEOUT | 任务状态机四态（见第 10 章） |
| `AgentTask` | taskId / sessionId / userId / input / state / ... | 持久化的任务主记录 |

> 设计观察：这些全是 **record（不可变值对象）**——Agent 领域里消息/结果天然是"快照"性质，用 record 避免共享可变状态，配合 `List.copyOf` 防御性拷贝，多线程流式场景下安全。

---

## 2. 项目骨架与配置体系（Step 1）

> 学习目标：读懂一个 Spring Boot 企业项目的**包结构与配置哲学**。

### 2.1 分层与包结构

```
com.cosy.agent
├── controller   # HTTP 层：协议翻译、参数校验、请求头解析（薄）
├── service      # 编排层：AgentOrchestrator（任务生命周期）
├── agent
│   ├── core     # ReAct 循环、消息/结果/上下文模型
│   ├── tool     # 工具注册表 + AgentTool 契约
│   ├── memory   # 多层记忆（Redis）
│   ├── vector   # 知识检索（PGVector）
│   ├── router   # 模型路由（平台注册/候选链/管理 API/配置存储）
│   ├── mock     # Mock 引擎（剧本/随机/延迟）
│   ├── task     # 任务状态机 + 轨迹 + 三种存储实现
│   └── resilience # 容错统一入口
├── config       # @ConfigurationProperties 配置模型
└── common       # 统一返回 Result<T>、异常、错误码
```

**观察**：每个知识域一个独立子包，**依赖方向自上而下**（controller → service → agent.*）。这与 DDD 的分层思想一致：领域内核（agent.*）不感知 HTTP。

### 2.2 配置体系（重点）

`application.yml` 的顶层结构（部分）：

```yaml
cosy:
  agent:
    mock:          # Mock 引擎：enabled / 剧本概率 / 延迟区间
    model-routing: # 模型路由：platforms / routes / store / pg / mysql
    task:          # 任务持久化：store / pg / mysql
    security:      # API 鉴权：api-key
```

**四个值得学习的配置哲学**：

1. **环境变量占位符 + 默认值**：`api-key: ${COSY_AGENT_API_KEY:}`——配置进 git，密钥进环境。这是"12-Factor 配置外置"。
2. **Profile 隔离敏感信息**：`spring.profiles.default: local` + `application-local.yml`（.gitignore 排除）——真实 API Key 只在本机，公共仓库零泄露。这是"配置分级"。
3. **配置对象化**：`@ConfigurationProperties(prefix = "cosy.agent.model-routing")` 绑定为 `ModelRoutingProperties` record——配置不再是散落的 `@Value`，而是有类型、可校验、可注入的领域对象。
4. **可插拔实现**：`store: memory | pg | mysql`——同一配置键切换实现，靠 Spring 条件装配（`@ConditionalOnProperty`）。

### 2.3 统一返回与异常（`Result<T>` / `BizException`）

```java
Result<T> { int code; String message; T data; }   // code==0 成功
BizException(ErrorCode, msg)  // 业务异常：错误码 + 文案，全局 @RestControllerAdvice 兜底
```

**设计要点**：错误码集中枚举（`ErrorCode`），业务异常携带语义化文案；**异常与 HTTP 状态码解耦**——业务错误一律 200 + code，网络/鉴权才用 HTTP 语义。客户端只需解 `code`。

### 2.4 API 鉴权（`ApiKeyFilter`）

- `cosy.agent.security.api-key` 配置后，`/api/**` 需携带 `X-API-Key`；留空不启用（本地开发零摩擦）。
- 实现：`OncePerRequestFilter`，配置了 key 才拦截——**"默认宽松、配置即收紧"**，适合本地开发与生产两态共存。

---

## 3. 设计决策速览（为什么不是"拿来即用"）

> 学习目标：理解本项目所有"反框架默认"的决策——**高级工程师的标志是知道何时不按默认来**。

| 默认做法 | 本项目决策 | 原因 |
| --- | --- | --- |
| Spring AI `ChatClient` 自动工具执行 | **自研 ReAct 循环** | 需要每轮插桩：容错、轨迹审计、SSE 事件、未知工具自愈——自动执行是黑盒 |
| 模型调用失败整体降级 | **4xx 不降级、5xx/超时/熔断才降级** | 切模型对 4xx 无意义（参数/端点错误换平台也一样错），避免掩盖真实问题 |
| `ChatModel` 内部解析工具调用 | **`toolExecutionEligibilityPredicate=false`** | 模型可能改写工具名（中文描述当名字），Spring AI 内部解析失败会直接抛异常中断整次对话；把执行权收归 ReAct 循环，失败可自愈 |
| 每个平台固定 `/v1/chat/completions` | **`completions-path` 可配置** | Spring AI 默认拼 `/v1`，智谱/火山端点无 `/v1` 结构（踩坑实录，见第 6 章） |
| 真实模型才能开发联调 | **端到端 Mock** | 无模型/无额度也能全链路跑通、CI 可复现、模拟故障路径 |
| 一次性同步返回 | **SSE 流式** | 长任务（多轮 × 延迟）下同步返回体验差、易超时；流式对齐主流 Agent 产品 |

> 这些决策会在对应章节展开。**先把这张表记住，后面每章都在为它找证据。**

---

## 4. ReAct 编排与工具桥接（Step 2）——全项目的心脏

> 学习目标：
> 1. 理解 ReAct（Reason + Act）范式为什么能让模型"干活"而不是"聊天"；
> 2. 掌握 Spring AI 的消息模型与工具调用协议；
> 3. 读懂 `DefaultReActAgent` 的循环，能画出每一轮的状态变化。

### 4.1 ReAct 范式：让模型"边想边做"

ReAct 把一次任务拆成循环往复的三步：

```
Thought（思考）：我要做什么、需要什么信息
  → Action（行动）：调用某个工具（或直接回答）
  → Observation（观察）：工具返回了什么
  → 回到 Thought，直到信息足够 → 最终回答
```

- **为什么有效**：大模型擅长推理但"没有手"；工具是它的手，观察是它的眼睛。循环让它把任务**拆解、执行、验证**。
- **和普通聊天的区别**：聊天是"一次性生成文本"；Agent 是"生成 → 行动 → 基于结果再生成"。

### 4.2 Spring AI 的消息模型（先认识积木）

Spring AI 1.x 用**统一的 Message 体系**描述一次对话，本项目用到的四块积木：

| Message | 角色 | 用途 |
| --- | --- | --- |
| `SystemMessage` | 系统 | 告诉模型"你是谁、怎么干活"（本项目：ReAct 指令 + 记忆 + RAG） |
| `UserMessage` | 用户 | 本轮任务输入 |
| `AssistantMessage` | 助手 | 模型的思考/回答/**工具调用意图**（携带 `ToolCall` 列表） |
| `ToolResponseMessage` | 工具 | 工具执行结果，按 toolCallId 回填给模型 |

```java
List<Message> messages = List.of(
    new SystemMessage(buildSystemPrompt(context, userInput)),  // 系统提示 = 指令 + 记忆 + RAG
    new UserMessage(userInput)
);
ChatResponse response = chatModel.call(new Prompt(messages, chatOptions));
AssistantMessage assistant = response.getResult().getOutput();
List<AssistantMessage.ToolCall> toolCalls = assistant.getToolCalls();  // 模型说"我要调用工具"就在这里
```

**关键认知**：模型**不会真的执行工具**，它只输出一个结构化的"调用请求"（工具名 + JSON 参数）。执行是**框架/我们**的活。OpenAI function calling 协议的核心就在这。

### 4.3 `DefaultReActAgent` 的循环（逐段解读）

```java
for (int i = 0; i < context.maxIterations(); i++) {   // ① 迭代上限（防死循环）
    iterations++;
    emit(listener, AgentStreamEvent.thinking(iterations));   // ② SSE 事件：本轮开始思考

    // ③ 调模型：Mock 短路 或 走模型路由（候选链 + 降级）
    ChatResponse response = useMock
        ? resilience.execute(LLM, () -> mockEngine.generate(new Prompt(messages, chatOptions)))
        : modelRouter.call(new Prompt(messages, chatOptions), context.modelChoice()).response();

    AssistantMessage assistant = response.getResult().getOutput();
    List<ToolCall> toolCalls = assistant.getToolCalls();

    if (toolCalls == null || toolCalls.isEmpty()) {   // ④ 模型直接回答 → 结束
        answer = assistant.getText();
        emit(listener, AgentStreamEvent.answer(answer));
        state = COMPLETED; break;
    }

    // ⑤ 有工具调用：把助手消息追加进上下文，逐个执行工具
    messages.add(assistant);
    for (ToolCall toolCall : toolCalls) {
        AgentTool tool = toolRegistry.find(toolCall.name()).orElse(null);  // ⑥ 查工具注册表
        Object result = tool == null
            ? Map.of("error", "未知工具: " + toolCall.name())   // ⑦ 未知工具 → 自愈（不崩溃，回传模型）
            : resilience.execute(TOOL, () -> tool.execute(parseArgs(toolCall.arguments())), tool.retryable());
        String resultJson = toJson(result);
        trace.add(AgentMessage.tool(...));                    // ⑧ 轨迹审计
        messages.add(ToolResponseMessage.builder().responses(...).build());  // ⑨ 观察回填
    }
    // ⑩ 下一轮循环：模型看到工具结果后继续 Thought
}

if (state == RUNNING) { state = TIMEOUT; answer = "已达到最大迭代次数..."; }  // ⑪ 超限兜底
```

**逐点解读（这是全文最重要的代码）**：

- **① 迭代上限**：模型可能陷入循环（反复调同一个工具），`maxIterations`（默认 8）是**硬止损**，超限转 TIMEOUT 而非无限烧钱。
- **③ 双通道**：Mock 走剧本引擎（第 5 章），真实走模型路由（第 6 章）。**同一套循环、两条数据源**——这是"可测试性"的胜利。
- **⑥ 注册表模式**：`ToolRegistry` 是 `Map<String, AgentTool>` 的封装，`find()` 找不到返回 empty——**不抛异常**，让上层决定怎么处理。
- **⑦ 未知工具自愈**：模型可能编造工具名（尤其被截断/改写时），这里把它变成一条"error 观察"回传模型，模型看到错误后自行修正或放弃——**失败不打断流程，交给模型自己消化**。这正是 ReAct 循环比硬编码 try/catch 强的地方。
- **⑧ 轨迹审计**：每一轮、每一步都追加到 `trace`（USER/ASSISTANT/TOOL 消息序列），它是任务持久化、断点恢复、客户端渲染的共同数据源。
- **⑨ 观察回填**：`ToolResponseMessage` 用 `toolCallId` 与模型的调用请求**配对**——这是 function calling 协议的闭环（模型能区分"这个结果属于哪个调用"）。

**⑪ 终止条件汇总**：最终回答（COMPLETED）/ 达到迭代上限（TIMEOUT）/ LLM 调用异常（FAILED）。**三种结局都落库**，没有"跑了没下文"的状态。

### 4.4 工具契约（`AgentTool`）

```java
public interface AgentTool {
    String name();                      // 注册名（模型调用时用的名字）
    String description();               // 描述（注入模型，让它知道工具能干嘛）
    Map<String, Object> execute(Map<String, Object> args);  // 执行
    default boolean retryable() { return false; }  // 幂等工具可重试（第 9 章）
}
```

**设计要点**：
- 工具 = **注册表 + 契约**，新增一个工具只需实现接口并注册，ReAct 循环零改动——**开闭原则**。
- `retryable()` 把"这个工具能不能重试"的决策**下沉到工具自身**（幂等的才重试，非幂等的重试会造成重复扣款/重复下单——高级工程师必懂）。
- 参数解析用 Jackson 把模型给的工具参数 JSON 转 `Map`——容错：解析失败返回空 Map（工具自行兜底）。

### 4.5 为什么自研循环而不是 `ChatClient`

Spring AI 的 `ChatClient` 会**自动**执行工具并继续调用模型直到出结果——看起来很省事，但企业场景要的是**每一轮的控制权**：

| 需求 | 自动执行 | 自研循环 |
| --- | --- | --- |
| 每轮上 Resilience4j 容错 | 难插桩 | ✅ `resilience.execute(LLM, ...)` |
| 完整轨迹审计（trace） | 黑盒 | ✅ 每步追加 |
| SSE 流式事件 | 拿不到中间态 | ✅ `emit(listener, ...)` |
| 未知工具自愈 | 直接抛异常 | ✅ error 回传模型 |
| Mock 引擎注入 | 困难 | ✅ 双通道 |

**结论**：**框架的自动能力要敢于关掉，换成自己的可观测循环**——这是从"会用框架"到"驾驭框架"的分水岭。

---

## 5. 端到端 Mock 模块：没有模型也能跑全链路

> 学习目标：
> 1. 理解"请求级开关 + 可复现随机"的设计；
> 2. 读懂剧本引擎如何模拟模型的工具调用行为；
> 3. 掌握延迟模拟与超时预算的平衡。

### 5.1 为什么需要 Mock

- 开发/CI 阶段没有真实模型额度，或不想每次调用花钱；
- 要**确定性复现**故障路径（模型异常、未知工具、超限）；
- 客户端联调不等后端模型配置。

**核心设计：Mock 是"另一个模型源"**——在 ReAct 循环里 `useMock ? mockEngine.generate(...) : modelRouter.call(...)`，对上层完全透明。开关有三层：

```
全局配置  cosy.agent.mock.enabled      （默认 true）
  ← 请求头 X-Cosy-Mock: true|false     （请求级覆盖，客户端设置页开关）
    ← 上下文 context.mockOverride()     （透传到 ReAct 循环）
```

### 5.2 剧本引擎（`MockScriptEngine`）

它不是"随机瞎答"，而是**按剧本走**——模拟一个"正常 Agent 的典型行为"：

- 分析用户输入 → 选择一个合适工具（`get_server_time` / `calculator` / 知识检索等）→ 构造"模型式"的 `AssistantMessage`（带 ToolCall）→ 下一轮给出最终答案。
- **随机性**（`MockRandomSource`）：
  - `random` 模式：每次运行独立随机（用户消息哈希 ^ 当前时刻）；
  - `scripted` 模式：固定种子派生——**CI 上同一输入永远产生同一序列**，测试可复现。
- **故障剧本**：按概率注入 `unknown-tool`（工具名替换为未注册名 → 走自愈路径）、`multi-tool`（单轮多工具）、`error`（模型异常 → FAILED 路径）——**把生产可能遇到的每种路径都变成可触发剧本**。

### 5.3 延迟模拟（Latency）：贴近真实的等待感

- 配置 `cosy.agent.mock.latency.{enabled,min-ms,max-ms}`，默认 `true / 500 / 2500`。
- **粒度 = 单次模型调用**（每轮 ReAct 迭代各一次），与真实模型逐轮推理语义一致；
- 随机取 [min, max] 区间的延迟 `Thread.sleep`；**中断恢复语义**：被上游 TimeLimiter 取消时恢复中断位并上抛，不吞中断；
- **超时预算链**（高级工程师必须会算账）：

```
单轮延迟 ≤ 2.5s
8 轮累计 ≤ 20s  <  客户端 receiveTimeout 30s  <  服务端 llm-timelimiter 60s
```

> **设计要点**：Mock 延迟不是"随便加个 sleep"，而是把**超时预算**当成一个等式来设计——任何一层都不能吃掉下一层的余量。

---

## 6. 模型路由 v2：多平台候选链 + 降级

> 学习目标：
> 1. 理解"配置权威在后端、选择权给前端"的架构；
> 2. 掌握降级语义的精细设计（什么错该降级、什么错不该降级）；
> 3. 收获两个 Spring AI 真实踩坑（completions-path / 工具自动执行）。

### 6.1 为什么需要路由

- 企业不想绑定单一模型商（成本/可用性/合规）；
- 不同任务类型（对话、推理）适合不同模型；
- 单一模型故障时要有**后备**。

### 6.2 配置模型

```yaml
model-routing:
  platforms:            # 平台注册表（唯一标识 → base-url / api-key / completions-path）
    aliyun:     { base-url: https://dashscope.aliyuncs.com/compatible-mode, api-key: ${...}, completions-path: /v1/chat/completions }
    zhipu:      { base-url: https://open.bigmodel.cn/api/paas/v4, api-key: ${...}, completions-path: /chat/completions }
    openrouter: { base-url: https://openrouter.ai/api, api-key: ${...}, completions-path: /v1/chat/completions }
  routes:               # 路由类型 → 有序候选链（降级顺序）
    default:   [zhipu/glm-4-flash, aliyun/qwen-max, openrouter/stealth/ox-alpha, ...]
    reasoning: [aliyun/qwen-max, openrouter/stealth/ox-alpha]
```

- 候选格式 `平台/模型`（`RouteConfig.resolveCandidates` 用**第一个** `/` 拆分，模型名里可以有 `/`，如 `stealth/ox-alpha`）；
- `modelChoice=auto` → 走 `default` 链；`modelChoice=平台/模型` → 锁定单候选（**不跨模型降级**——用户明确指定了就不要偷偷换）。

### 6.3 降级语义（精细化）

`ModelRouter` 的候选链循环 + 异常分类：

```java
for (String candidate : candidates) {
    try { return callCandidate(cfg, candidate, prompt); }
    catch (RuntimeException e) {
        if (!isFallbackEligible(e)) throw e;   // 不可降级异常：立即终止
        log.info("降级到下一候选...");
    }
}
```

**可降级（切换下一候选）**：连接失败 / 超时 / 5xx / 429 / 熔断打开。
**不可降级（立即抛出）**：4xx 等客户端错误。

> **为什么 4xx 不降级**：404 端点不存在、401 密钥无效——换个平台一样错（甚至掩盖配置问题）。降级只解决"**这个平台暂时不可用**"，不解决"**我们的配置错了**"。这个区分是生产级降级的标志。

### 6.4 踩坑实录①：Spring AI 强制拼 `/v1`？

**现象**：智谱平台请求 404，路径 `/v4/v1/chat/completions`。
**源码证据**（spring-ai-openai 1.1.8 `OpenAiApi`）：

```java
private String completionsPath = "/v1/chat/completions";  // 默认值
.uri(this.completionsPath)   // baseUrl + 该路径
```

**结论**：默认**是**在 baseUrl 后拼 `/v1/chat/completions`，但 builder 提供 `completionsPath(...)` **可覆盖**——不是强制，是"默认值"。
**修复**：平台新增 `completions-path` 配置项（全链路透传：Properties → RouteConfig → ModelPlatformRegistry），默认 `/v1/chat/completions` 兼容 OpenAI 系；智谱/火山配 `/chat/completions` 即可真实接入。

### 6.5 踩坑实录②：模型改写工具名导致对话崩溃

**现象**：glm-4-flash 把工具描述当工具名返回（"获取服务器当前本地时间，格式…"），报错 `LLM may have adapted the tool name '...'`。
**根因**：`OpenAiChatModel.call()` 内 `ToolCallingManager` **自动解析并执行工具**，工具名不匹配直接抛异常——我们的 ReAct 循环根本拿不到 toolCalls，"未知工具自愈"无从触发。
**修复**（架构级）：

```java
OpenAiChatModel.builder()
    .toolExecutionEligibilityPredicate((options, response) -> false)  // 关闭内部自动执行
    ...
```

- 工具定义**仍正常注入**模型（模型看得到工具）；
- 返回的 toolCalls **原样透出**给 ReAct 循环统一处理（自愈/容错/审计）。

> **经验**：框架的"自动执行"是黑盒假设（工具名严格匹配），LLM 的不可靠性要求**把执行权收回到自己能插桩的地方**。

### 6.6 配置权威在后端 + 热更新

- **YAML 基线**（入库）→ 启动加载；
- **管理 API**（`PUT /api/agent/model-routing`）热更新——api-key 留空表示"保持原值"（**不会把脱敏视图回写覆盖真实密钥**，这个细节很关键）；
- **store 持久化**：`memory | pg | mysql` 三实现，重启后从持久化覆盖基线；
- 变更通过 `router.refresh(updated)` + `registry.invalidate()`（缓存键含配置摘要）**即时生效**，无需重启。
- **客户端只读目录**（`GET /model-routing/catalog`）：`auto + 可用模型列表` 驱动聊天页模型选择器——**前端不持有任何配置/密钥**。

---

## 7. Redis 多层记忆（Step 3）

> 学习目标：
> 1. 理解 Agent 记忆为什么必须**分层**；
> 2. 掌握"滚动窗口 + TTL"的会话记忆实现；
> 3. 学会"降级优先"的外部依赖设计。

### 7.1 记忆分层（`MemoryLevel`）

| 层级 | 命名空间 | 内容 | TTL |
| --- | --- | --- | --- |
| `LONG_TERM` | userId | 用户长期偏好/事实 | 长（如 30 天） |
| `SESSION` | sessionId | 会话内滚动对话记录（`recent` key） | 会话级（如 1 小时） |
| `WORKING` | sessionId | 工作状态（如上次任务 state） | 短（如 10 分钟） |

**为什么分层**：不同信息寿命不同。长期记忆跨会话（"用户是前端工程师"），会话记忆只在本会话有效，工作状态是瞬时的。**分层 = 按数据生命周期划分存储域**，比"一个 key 装所有"更可控、可清理。

### 7.2 滚动窗口（关键细节）

```java
List<Map<String, String>> recent = loadRecent(sessionId);
recent.add(user); recent.add(assistant);
int from = Math.max(0, recent.size() - RECENT_LIMIT);   // RECENT_LIMIT=6（约 3 轮对话）
recent = recent.subList(from, recent.size());
save(JSON(recent));   // 序列化为 JSON 存 Redis
```

**设计要点**：
- 会话记忆**只保留最近 3 轮**——模型上下文有限，全量历史会"淹没"当前任务（成本 + 噪声）。**Agent 记忆不是存档，是"当下该想起什么"**。
- 存 JSON 字符串而不是 Redis Hash——**一条 key 一次读写**，简单且原子。

### 7.3 注入与降级

- 运行前：`buildSystemPrompt` 把 会话记忆 + 长期记忆 作为"【会话记忆】【用户长期记忆】"块追加进系统提示（RAG 结果也在这里，见第 8 章）；
- 运行后：`persistMemory` 把本轮对话 + 工作状态写回；
- **降级优先**：Redis 不可用时 `catch` 记 warn，**不带记忆继续对话**——外部设施故障绝不阻断主业务。这是所有外部依赖的通用策略（记忆/知识/任务存储都是）。

---

## 8. PGVector 知识检索（Step 4）——RAG 落地

> 学习目标：理解检索增强生成（RAG）在 Agent 里的接法，以及"检索失败降级"的兜底哲学。

### 8.1 RAG 是什么、为什么

- 模型知识有截止日期、且不知道企业私有知识。**检索增强生成**：回答前先检索相关文档，把结果**注入提示**，模型基于材料回答。
- 本项目实现：`PgVectorKnowledgeStore`（PostgreSQL + pgvector 扩展）与 `InMemoryKnowledgeStore`（无 PG 时的降级）。

### 8.2 接入位置（只有 3 行逻辑）

```java
// buildSystemPrompt 里：
List<KnowledgeHit> hits = vectorStore.search(namespace, userInput, topK, minScore);
if (!hits.isEmpty()) {
    sb.append("\n\n【知识库检索结果】\n");   // 命中才注入，没命中就不污染提示
    hits.forEach(hit -> sb.append("- [").append(hit.docId()).append("] ").append(hit.content()).append('\n'));
}
```

**设计要点**：
- **TopK + 阈值双控**：只取前 K 条且相似度 ≥ minScore——避免"没相关也硬塞"。
- **检索失败降级跳过**：catch → warn → 不带知识继续——与记忆同一哲学（第 7.3）。
- 检索发生在**每轮循环之前**（系统提示构建时），而不是整次任务开始一次——上下文随迭代更新。

---

## 9. Resilience4j 容错（Step 5）

> 学习目标：
> 1. 掌握 Resilience4j 五种策略的语义与组合；
> 2. 理解"按链路命名 + 统一入口"的落地方式；
> 3. 学会给外部依赖配置**合理**的参数而不是照抄。

### 9.1 五件套（Resilience4j 组件）

| 组件 | 解决的问题 | 本项目配置（llm 链路示例） |
| --- | --- | --- |
| `Retry` | 瞬时故障重试 | 3 次，退避 2s |
| `CircuitBreaker` | 连续失败熔断，防止雪崩 | 滑动窗口 20、失败率 50%、打开 30s、半开 5 次 |
| `RateLimiter` | 限流保护上游/预算 | 60 次/分钟，超限不等待 |
| `TimeLimiter` | 单次调用超时 | 60s（必须 > 任务最坏耗时） |
| `Bulkhead` | 舱壁隔离，防线程池耗尽 | 8 并发 |

### 9.2 命名约定与统一入口（重点）

```yaml
resilience4j:
  retry: { instances: { llm-retry: {...}, tool-retry: {...}, memory-retry: {...}, vector-retry: {...}, task-retry: {...} } }
  circuitbreaker: { instances: { llm-cb: ..., tool-cb: ..., memory-cb: ..., vector-cb: ..., task-cb: ... } }
```

- **约定式命名**：`<链路>-<组件>`。五条链路（LLM/TOOL/MEMORY/VECTOR/TASK）× 五类组件。
- `ResilienceSupport.execute(ResilienceTarget.LLM, supplier)` 内部**按命名约定自动查找**对应实例并组合执行——实例未配置时对应组件**自动跳过**（零配置也能跑，配置了才生效）。

```java
public <T> T execute(ResilienceTarget target, Supplier<T> supplier) {
    // 按 target.name() 组装：TimeLimiter → Bulkhead → RateLimiter → CircuitBreaker → Retry
    // 某一实例不存在就跳过该组件——配置是增量式的
}
```

### 9.3 落点矩阵（哪些调用套了容错）

| 调用点 | 链路 | 重试 | 备注 |
| --- | --- | --- | --- |
| 模型推理（Mock/真实路由） | LLM | ✅ | 熔断开 → 返回"稍后重试"友好文案 |
| 工具执行 | TOOL | 仅 `retryable=true` | 非幂等工具不重试 |
| 记忆读写 | MEMORY | 2 次快速 | 失败降级 |
| 知识检索 | VECTOR | 2 次快速 | 失败降级 |
| 任务/轨迹落库 | TASK | 2 次快速 | 失败降级（审计不阻断对话） |

**设计要点**：
- **每个工具自己决定能否重试**（`AgentTool.retryable()`）——容错策略需要业务语义（幂等性）参与；
- **熔断降级文案友好化**：`CallNotPermittedException` → "模型服务暂时不可用（熔断中），请稍后重试"——把技术信号翻译成用户语言；
- **外部依赖故障绝不阻断主流程**（记忆/知识/任务三处 catch + warn 降级）——主业务（对话）永远优先。

---

## 10. 任务状态机 · 轨迹审计 · 断点恢复 · 会话管理（Step 6）

> 学习目标：
> 1. 理解 Agent 任务的**状态机**与三种结局；
> 2. 掌握 trace（轨迹）为何是"审计 + 恢复 + 渲染"的共同底座；
> 3. 理解 store 三实现（memory/pg/mysql）的可插拔设计。

### 10.1 状态机

```
               ┌──────────┐
               │   INIT   │  createTask
               └────┬─────┘
                    ▼
               ┌──────────┐
               │ RUNNING  │  markRunning（updateTask）
               └────┬─────┘
        ┌───────────┼───────────┐
        ▼           ▼           ▼
   COMPLETED    FAILED     TIMEOUT
   （最终回答）（模型异常）（迭代超限）
```

- **每种结局都落库**（answer / iterations / costMs / errorMessage）；
- `finish()` 里 `updateTask` + `appendTrace` **一起完成**——主记录与轨迹原子落库（持久化失败仅 warn，不阻断返回结果：**审计降级**）。

### 10.2 轨迹（trace）的三重身份

`trace` 是一组有序的 `AgentMessage`（USER → ASSISTANT → TOOL → ...），它同时服务：

1. **审计**：`GET /api/agent/tasks/{taskId}` 可回看任务完整推理链；
2. **断点恢复**：`resume` 把历史 trace 反序列化为 Spring AI 消息（`toSpringMessage`），注入上下文**继续运行**（产生新任务）——注意系统提示由当前轮重建，历史 SYSTEM 消息不注入；
3. **客户端渲染**：`GET /api/agent/sessions/{id}/messages` 合并全部任务轨迹按时间升序，即会话完整消息流。

> **一个数据源，三种用途**——这是"可观测性"的最优解：不需要为审计/恢复/渲染各存一份。

### 10.3 store 可插拔（`memory | pg | mysql`）

- `InMemoryTaskStore`：开发/测试用，重启即失；
- `JdbcTaskStore`（PostgreSQL）/ `MysqlJdbcTaskStore`：原生 JDBC（不触发 DataSource 自动配置），表 `agent_task / agent_trace` 自建；
- 同构接口 `TaskStore`，`@ConditionalOnProperty(cosy.agent.task.store)` 切换。
- 会话管理能力（同一 store）：惰性创建（首条消息才建会话、会话名 = 首条消息）、置顶/重命名/删除（联动清理 Redis 记忆）、按会话查消息。

> **设计要点**：Store 接口把"存哪"与"怎么用"解耦——业务代码只面对 `TaskStore`，换存储零改动。这是"依赖倒置 + 策略模式"的教科书应用。

---

## 11. SSE 流式输出（生产形态的体验）

> 学习目标：
> 1. 理解为什么 Agent 长任务必须流式；
> 2. 掌握 SSE 协议与 Spring `SseEmitter` 的用法；
> 3. 学会"事件协议 + 回调注入"不破坏既有同步接口的改造手法。

### 11.1 为什么流式

同步返回的痛点（真实发生过）：Mock 延迟加入后单次任务 3~5s、多轮 20s，客户端全程转圈、一次性出结果——**体验与超时双输**。主流 Agent 产品（豆包等）都是逐事件呈现：思考中 → 正在调用工具 → 工具结果 → 逐字回答。

### 11.2 事件协议

```
POST /api/agent/chat/stream   (text/event-stream)

event:thinking      { index }                        # 本轮思考开始
event:tool          { callId, toolName, arguments }  # 工具调用开始
event:toolResult    { callId, toolName, content }    # 工具结果
event:answer        { content }                      # 最终回答
event:done          { sessionId, taskId, state, iterations, costMs }  # 终态（含会话绑定信息）
event:error         { message }                      # 异常
```

### 11.3 实现手法（两个关键点）

**① 回调注入，不改控制流**：

```java
public interface AgentEventListener { void onEvent(AgentStreamEvent event); }

// ReActAgent.run 增加带 listener 的重载（默认实现忽略 → 同步接口零影响）
default AgentResult run(ctx, input, history, AgentEventListener listener) { return run(ctx, input, history); }
```

- `DefaultReActAgent` 循环内逐事件点 `emit(listener, ...)`（事件点不改变执行逻辑）；
- `AgentOrchestrator.streamChat` 与 `chat` 共用同一流程，仅多传 listener——**同步/流式一套代码**；
- done 事件由编排层在**任务落库后**补发（此时才有 taskId/sessionId 完整信息）。

**② SseEmitter 异步推送**：

```java
SseEmitter emitter = new SseEmitter(0L);   // 无服务端超时
taskExecutor.execute(() -> {                // 不能占请求线程 20s → 扔给任务线程池
    orchestrator.streamChat(..., event -> emitter.send(SseEmitter.event().name(event.type()).data(event)));
    emitter.complete();
});
```

- **客户端断开**：`emitter.send` 抛 IOException → 事件回调抛 `StreamSendException` → 编排线程终止（**不吞异常、不白跑**）；
- **超时预算**：mock 最坏 20s < 客户端 receiveTimeout（2 分钟）< llm-timelimiter 60s。

> **经验**：给现有同步系统加流式，优先考虑"**回调参数注入 + 同步方法委托**"，而不是另写一套执行链——一套逻辑、两种出口，测试/维护成本最低。

---

## 12. 客户端（Flutter）与工程细节

> 学习目标：了解端侧如何对接 SSE 流、以及本项目的工程纪律（适合任何项目复用）。

### 12.1 客户端要点

- **SSE 解析**：dio `ResponseType.stream` → `utf8.decoder` + `LineSplitter` 逐行取 `data:` 载荷 → 事件模型；
- **逐事件渲染**：thinking 更新"正在思考（第 N 轮）"→ tool 插入工具卡（执行中）→ toolResult 回填 → answer 即时上屏 → done 绑定会话/任务并刷新列表；
- **会话惰性创建**：首条消息才建会话，`done` 事件带回 sessionId 后绑定——与后端惰性创建语义一致；
- **模型选择器**：目录来自后端 catalog（配置权威在后端），选择本地持久化，请求带 `X-Cosy-Model`。

### 12.2 工程纪律（本项目沉淀，通用）

| 纪律 | 表现 |
| --- | --- |
| 配置外置 + 密钥隔离 | env 占位 + `application-local.yml`（gitignore） |
| 可复现测试 | Mock scripted 模式 + 测试禁延迟（`Latency.DISABLED`） |
| 验证走真实入口 | 全量测试 86 项 + 28080 冒烟 + SSE curl 实测 |
| 流式/同步单实现 | listener 回调注入，接口默认忽略 |
| 换通道不降交付 | PowerShell 编码问题改用 UTF-8 文件体、mvn clean 强制资源同步 |

---

## 13. 附录

### 13.1 类索引（类名 → 一句话职责）

| 类 | 职责 |
| --- | --- |
| `AgentController` | HTTP 入口：参数/请求头解析、SSE 端点 |
| `AgentOrchestrator` | 任务生命周期编排（创建/RUNNING/终态） |
| `DefaultReActAgent` | ReAct 循环：思考/行动/观察、事件回调 |
| `AgentContext / AgentResult / AgentMessage` | 执行上下文 / 结果 / 统一消息模型 |
| `ToolRegistry / AgentTool` | 工具注册表 / 工具契约 |
| `MockScriptEngine / MockRandomSource` | Mock 剧本引擎 / 双随机源 |
| `ModelRouter / ModelPlatformRegistry / ModelRoutingAdmin` | 候选链降级 / 平台实例缓存 / 管理 API |
| `MemoryStore / RedisMemoryStore` | 记忆契约 / Redis 实现（分层 + TTL） |
| `VectorKnowledgeStore / PgVectorKnowledgeStore` | RAG 检索契约 / PGVector 实现 |
| `ResilienceSupport / ResilienceTarget` | 容错统一入口 / 链路枚举 |
| `TaskStore / JdbcTaskStore / MysqlJdbcTaskStore / InMemoryTaskStore` | 任务/轨迹持久化三实现 |
| `AgentStreamEvent / AgentEventListener` | SSE 事件模型 / 流回调 |

### 13.2 接口清单（核心端点）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/agent/chat` | 同步对话（兼容） |
| POST | `/api/agent/chat/stream` | SSE 流式对话（生产形态） |
| POST | `/api/agent/tasks/{id}/resume` | 断点恢复 |
| GET | `/api/agent/tasks[/{taskId}]` | 任务列表/详情（含 trace） |
| GET | `/api/agent/sessions[/{sessionId}[/messages]]` | 会话列表/摘要/消息 |
| POST | `/api/agent/sessions/{id}/pin` · `/rename` · DELETE | 置顶/重命名/删除 |
| GET/PUT | `/api/agent/model-routing` | 路由配置读/热更新 |
| GET | `/api/agent/model-routing/catalog` | 客户端模型目录 |

### 13.3 踩坑记录（真金白银）

1. **Spring AI 默认拼 `/v1/chat/completions`** → `completions-path` 可配置（见 6.4）。
2. **Spring AI 内部自动执行工具** → 工具名被 LLM 改写即崩溃 → `toolExecutionEligibilityPredicate=false` 收权（见 6.5）。
3. **4xx 不降级**——换平台解决不了配置错误，反而掩盖问题（见 6.3）。
4. **`mvn spring-boot:run` 资源未重新复制** → 改 yml 后运行旧配置 → `mvn clean` 强制同步。
5. **PowerShell 中文编码**：`ConvertTo-Json` / 字节体都可能乱码 → 用 UTF-8 文件体（`-InFile`）。
6. **密钥入库泄露** → env 占位 + gitignore 的 local profile（公共仓库零密钥）。
7. **超时预算**：mock 20s < 客户端 30s < 服务端 60s，层层留余量。

### 13.4 延伸思考（学完自测）

1. 如果要在每轮工具调用前加"人工审批"，改哪里？（答：ReAct 循环 toolCalls 处理处插入审批回调，与流式 listener 同手法）
2. 为什么 trace 不存 JSON 而存结构化消息表？（答：可查询性 vs 简单性——本项目为简单性选消息表 + 时间戳排序）
3. 4xx 不降级的例外场景是什么？（答：模型侧 400"上下文超长"其实可以降级到更长上下文的模型——但要先识别出这类特殊 4xx）
4. Mock 的 scripted 模式如何保证 CI 可复现？（答：固定种子派生随机序列；若引入时间因素会破坏复现）
5. SSE 流式下如果客户端中途断开，任务还继续执行吗？（答：执行继续（emit 静默失败），但 done 发不出去——所以任务落库仍然发生，客户端重进可查历史，这是"审计兜底"的价值）

---

> **最后**：这份文档的主线是"一次对话的旅程"，希望你在读完后能**不看代码**讲出：一个请求进来，谁先谁后、每步为什么、失败怎么兜底、超时怎么预算、体验怎么流式。那就是真正掌握了。
