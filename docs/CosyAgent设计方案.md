# CosyAgent 企业级 AI 智能体系统设计方案

> 版本：v0.5（Step 1 ~ Step 3 + Step M + Step 4 落地版）
> 技术底座：Java 17 / Spring Boot 3.5.16 / Spring AI 1.1.8 / Redis / PostgreSQL + PGVector / Resilience4j 2.4.0

---

## 1. 项目背景与目标

### 1.1 背景

传统对话系统只能"一问一答"，无法自主完成任务。企业级智能体需要具备：

- **自主规划**：模型理解目标，自行拆解任务并选择执行路径；
- **工具调用**：调用外部能力（查询、计算、写入、通知等）获取信息并产生动作；
- **多轮迭代**：观察工具结果，调整策略，循环直至目标达成；
- **记忆**：跨轮次、跨会话保持上下文与用户画像；
- **知识**：结合企业内部/领域知识库回答，而非仅靠模型参数；
- **可靠**：对模型与外部依赖的抖动具备容错能力；
- **可恢复**：长任务执行中故障后可恢复、可审计。

### 1.2 目标

基于 Spring AI 构建一套**可落地的企业级 ReAct 智能体框架**，按步骤交付：

1. **Step 1（本次）**：搭建基础框架 —— 分层工程骨架、核心契约、工具注册表、统一接口与配置体系，保证工程可编译、可测试、可运行；
2. Step 2 ~ Step 6：逐步接入 ReAct 编排、Redis 多层记忆、PGVector 知识检索、Resilience4j 容错、状态持久化与生产化能力。

### 1.3 设计原则

- **契约先行**：核心能力（记忆、向量、工具、编排）先定义接口，再分步实现，保证各步骤可独立交付与替换；
- **配置驱动**：所有容量/超时/TTL 参数外置到配置，环境变量可覆盖，避免硬编码；
- **容错内置**：从 Step 1 起在依赖层预留 Resilience4j，任何外部调用（LLM、Redis、PG、工具）都落在容错策略内；
- **可观测**：全链路日志 + 执行轨迹记录，便于审计与问题定位。

---

## 2. 总体架构

```mermaid
flowchart TB
    subgraph 接入层
        A1[HTTP API<br/>/api/agent/chat]
        A2[鉴权与限流<br/>网关/过滤器]
    end

    subgraph 编排层
        B1[AgentOrchestrator<br/>会话上下文]
        B2[ReActAgent<br/>Thought→Action→Observation]
        B3[状态机<br/>AgentState]
    end

    subgraph 能力层
        C1[工具层<br/>ToolRegistry + @Tool 桥接]
        C2[记忆层<br/>Redis 多层记忆]
        C3[知识层<br/>PGVector 检索]
        C4[容错层<br/>Resilience4j]
    end

    subgraph 基础设施
        D1[(Redis<br/>工作/会话/长期记忆)]
        D2[(PostgreSQL<br/>+ PGVector 向量库)]
        D3[LLM<br/>OpenAI 兼容接口]
        D4[日志/指标/审计]
    end

    A1 --> B1 --> B2
    B2 --> C1 --> B2
    B2 --> C2 --> D1
    B2 --> C3 --> D2
    B2 -.调用.-> D3
    C4 -.包裹所有外部调用.-> C1 & C2 & C3
    B2 --> B3
    C1 & C2 & C3 -.-> D4
```

**分层职责**

| 层 | 职责 | 对应模块 |
| --- | --- | --- |
| 接入层 | HTTP 接口、参数校验、鉴权、并发限流 | `controller` |
| 编排层 | 会话上下文、ReAct 循环、状态推进、终止判定 | `agent.core` / `service` |
| 能力层 | 工具注册与执行、记忆读写、知识检索、容错策略 | `agent.tool` / `agent.memory` / `agent.vector` / `agent.resilience` |
| 基础设施 | Redis、PGVector、LLM、日志监控 | 外部组件 |

---

## 3. 核心技术选型

| 组件 | 版本 | 选型理由 |
| --- | --- | --- |
| Java 17 | LTS | Spring Boot 3.x 基线，长期维护 |
| Spring Boot | 3.5.16 | 稳定维护线，自动装配体系成熟 |
| Spring AI | 1.1.8 | 官方抽象：`ChatClient`、`@Tool` 工具调用、`VectorStore` 向量抽象、模型多厂商切换 |
| Spring Data Redis | Boot 管理 | 多层记忆的 TTL 能力与高性能读写 |
| PostgreSQL + PGVector | 插件 | Spring AI 一等公民支持（`PgVectorStore`），SQL + 向量一体 |
| Resilience4j | 2.4.0 | 与 Spring Boot 3 原生集成，注解 + 声明式配置，支持 Retry/CB/RateLimiter/TimeLimiter/Bulkhead |

> 说明：Spring AI 提供 `spring-ai-bom` 统一管理版本；模型厂商（OpenAI/DeepSeek/Qwen 等兼容接口）通过 `base-url` 切换，不改代码。

---

## 4. ReAct 编排设计（Step 2）

### 4.1 推理循环

ReAct（Reasoning + Acting）核心循环：**思考（Thought）→ 行动（Action）→ 观察（Observation）**。

```mermaid
sequenceDiagram
    participant U as 用户
    participant O as AgentOrchestrator
    participant R as ReActAgent
    participant M as LLM(ChatClient)
    participant T as ToolRegistry
    participant Mem as MemoryStore

    U->>O: 输入任务
    O->>O: 构建 AgentContext(会话/用户/配置)
    O->>R: run(context, input)
    loop 迭代 i < maxIterations
        R->>Mem: 加载记忆上下文
        R->>M: 组装 Prompt(系统提示+记忆+历史+工具描述)
        M-->>R: Thought + Action(工具名+参数)
        alt 需要调用工具
            R->>T: 查找并执行工具
            T-->>R: Observation(工具结果)
            R->>R: 记录轨迹
        else 已产生最终回答
            R-->>O: AgentResult(答案+轨迹)
            O-->>U: 返回结果
        end
    end
```

### 4.2 终止条件

- **自然终止**：模型输出最终答案（无工具调用意图）；
- **强制终止**：达到 `cosy.agent.max-iterations`（默认 8 轮），返回部分结果并标记 `TIMEOUT`；
- **异常终止**：LLM/工具调用连续失败超过容错上限，标记 `FAILED`；
- **取消**：异步场景下支持中断，标记 `CANCELLED`。

### 4.3 状态机

```
INIT → PLANNING → RUNNING ⇄ TOOL_CALLING → COMPLETED
                        │
                        ├─→ FAILED（异常）
                        ├─→ TIMEOUT（超迭代）
                        └─→ CANCELLED（中断）
```

状态在 Step 6 持久化到任务表，支撑长任务恢复与审计。

### 4.4 上下文窗口管理

- 每轮迭代后裁剪历史：保留系统提示 + 最近 N 轮 + 记忆摘要；
- 工具结果超长时截断（默认 4000 字符），避免撑爆上下文；
- 记忆摘要由模型周期性生成（见第 5 节）。

---

## 5. 记忆系统设计：Redis 多层记忆（Step 3 已实现）

### 5.1 分层模型

| 层级 | 定位 | 典型内容 | TTL 默认 | Key 前缀 |
| --- | --- | --- | --- | --- |
| L1 工作记忆 | 当前任务执行中的临时状态 | 任务拆解、中间结果、工具输出缓存 | 任务生命周期 | `cosy:work:{session}` |
| L2 会话记忆 | 单会话多轮对话上下文 | 对话历史、摘要、用户当轮意图 | 30 分钟 | `cosy:session:{session}` |
| L3 长期记忆 | 跨会话的用户画像与事实 | 用户偏好、事实、领域规则 | 180 天 | `cosy:long:{user}` |

### 5.2 读写策略

- **写**：每轮迭代结束后异步写入 L2（对话历史）；关键事实抽取后写入 L3；工具中间结果写 L1；
- **读**：每次 LLM 调用前，按 L3 → L2 → L1 顺序合并为记忆上下文注入 Prompt；
- **遗忘**：依赖 Redis TTL 自动过期；L2 摘要采用"滚动摘要"——上下文超过阈值时由模型压缩为摘要后替换；
- **检索**：L3 长期记忆按关键词/向量语义检索（Step 3 用 Redis Search，Step 4 后可向量化），取 TopK 命中注入。

### 5.3 Redis Key 设计

```
cosy:work:{sessionId}:{taskId}      # 当前任务状态（Hash，TTL=任务级）
cosy:session:{sessionId}:messages   # 会话消息列表（List，TTL=30m）
cosy:session:{sessionId}:summary    # 会话滚动摘要（String，TTL=30m）
cosy:long:{userId}:fact:{factId}    # 长期事实（String，TTL=180d）
cosy:lock:{taskId}                  # 任务并发锁（用于幂等/防重入）
```

### 5.4 实现状态（Step 3 已落地）

- `RedisMemoryStore`：实现 `MemoryStore` 契约，Key 规范 `cosy:{level}:{namespace}:{key}`（work/session/long），TTL 分层（WORKING=10m / SESSION=30m / LONG_TERM=180d，均可配置）；
- 记忆接入：`DefaultReActAgent` 运行前注入【会话记忆】+【用户长期记忆】到系统提示，运行后持久化滚动会话记录（最近 6 条）与工作状态；
- 降级：记忆读写失败自动降级为无记忆直答（仅告警，不阻断推理）；
- 检索：Step 3 用关键词包含过滤；Step 4 已升级为向量化语义检索（InMemory/PGVector 双实现，RAG 注入见第 6 节）。

---

## 6. 知识检索设计：PGVector（Step 4 已实现）

### 6.1 处理链路

```
文档 → 切分（分块 500~800 字符，重叠 50）→ Embedding（模型向量化）→ PgVectorStore 入库（含元数据）
查询 → Embedding → 相似度检索（TopK + 最小分数阈值）→ 结果注入 Prompt（RAG）
```

### 6.2 关键决策

- **存储**：`spring-ai-starter-vector-store-pgvector`，Spring AI 自动建表 `vector_store`（`id`、`content`、`metadata`、`embedding`）；
- **命名空间**：按 `namespace`（业务域/租户）隔离，避免跨域串扰；
- **检索参数**：`topK=5`、最小相似度阈值 0.7（按模型向量空间可调）；
- **混合检索**（进阶）：PGVector 向量检索 + PostgreSQL 全文检索（`tsvector`）加权融合，提升专业术语召回；
- **版本一致性**：Embedding 模型固定版本，模型升级需重建向量（记录模型指纹于元数据）。

### 6.3 实现状态（Step 4 已落地）

- **存储双实现**（`cosy.agent.vector.store` 切换）：
  - `InMemoryKnowledgeStore`（默认，`memory`）：无外部依赖，确定性 2-gram 哈希向量化 + 余弦检索，本地演示/CI；
  - `PgVectorKnowledgeStore`（`pgvector`）：PostgreSQL + pgvector 扩展，原生 JDBC（表 `vector_doc`：namespace/doc_id/content/metadata/embedding vector(256)），`<=>` 余弦距离检索 + TopK/阈值过滤；
- **入库管线**：`DocumentChunker`（分块大小/重叠可配，默认 600/50）+ `Vectorizer` 接口（`DeterministicVectorizer` 默认，可替换 Embedding 模型实现）；
- **RAG 注入**：`DefaultReActAgent` 运行前以用户输入检索知识库（TopK + 阈值），命中注入系统提示【知识库检索结果】；检索失败自动降级（跳过 RAG，不阻断推理）；
- **接口**：`POST /api/agent/knowledge/upsert`（文档入库，自动切分）+ `POST /api/agent/knowledge/search`（检索，支持指定命名空间）；
- **配置**：`cosy.agent.vector.{store, namespace, top-k, min-score, chunk-size, overlap, pg.*}`（环境变量 `COSY_AGENT_VECTOR_*` 可覆盖）；
- **验证**：全量 56 项测试通过，其中 `PgVectorKnowledgeStoreIntegrationTest` 3 项在真实 PostgreSQL 14 + pgvector 0.8.6 上通过（`PGVECTOR_IT=true` 时执行，默认跳过）；真实运行验证入库/检索/命名空间隔离/RAG 注入（日志记录命中数）。

---

## 7. 容错设计：Resilience4j（Step 5）

### 7.1 策略落点矩阵

| 调用对象 | Retry | CircuitBreaker | RateLimiter | TimeLimiter | Bulkhead |
| --- | :-: | :-: | :-: | :-: | :-: |
| LLM 推理（ChatClient） | ✅ 3 次退避 | ✅ 慢调用/失败熔断 | ✅ | ✅ 60s | ✅ 并发隔离 |
| 工具调用（外部 API） | ✅ 幂等工具 2 次 | ✅ | ✅ | ✅ 30s | ✅ |
| Redis 记忆读写 | ✅ | ✅ | — | ✅ 2s | — |
| PGVector 检索 | ✅ | ✅ | — | ✅ 5s | — |

### 7.2 降级策略

- **LLM 熔断**：返回降级文案"模型服务暂时不可用"，保留会话上下文以便恢复；
- **记忆降级**：降级为无记忆直答（仍返回结果，仅丢失上下文）；
- **知识检索降级**：跳过 RAG 直接回答，并在结果中标注"未使用知识库"；
- **工具失败**：错误作为 Observation 回传模型，由模型决定更换工具或终止（ReAct 天然容错）。

### 7.3 配置形态（Step 5 已启用）

```yaml
resilience4j:
  retry:
    instances:
      llm-retry:        # LLM：3 次 × 2s 退避
        max-attempts: 3
        wait-duration: 2s
      tool-retry:       # 幂等工具：2 次 × 1s
        max-attempts: 2
        wait-duration: 1s
      memory-retry:     # 记忆读写：2 次 × 500ms
        max-attempts: 2
        wait-duration: 500ms
      vector-retry:     # 知识检索：2 次 × 500ms
        max-attempts: 2
        wait-duration: 500ms
  circuitbreaker:
    instances:
      llm-cb:           # 20 窗口 / 50% 失败率 / 打开 30s / 半开 5
        sliding-window-size: 20
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 5
      # tool-cb / memory-cb / vector-cb 同参数
  ratelimiter:
    instances:
      llm-ratelimit:    # 60 次/分，超限不等待直接拒绝
        limit-for-period: 60
        limit-refresh-period: 1m
        timeout-duration: 0s
      tool-ratelimit:   # 120 次/分
  timelimiter:
    instances:
      llm-timelimiter: 60s / tool-timelimiter: 30s / memory-timelimiter: 2s / vector-timelimiter: 5s
  bulkhead:
    instances:
      llm-bulkhead:     # 8 并发
        max-concurrent-calls: 8
        max-wait-duration: 0
      tool-bulkhead: 8 并发
```

### 7.4 实现状态（Step 5 已落地）

- `agent.resilience`：`ResilienceTarget`（LLM / TOOL / MEMORY / VECTOR 四类调用点）+ `ResilienceSupport`（@Component，注入五个 Registry）。
- 组合链：**Retry → CircuitBreaker → RateLimiter → Bulkhead → TimeLimiter**（外层→内层；执行序是 TimeLimiter 最先包住真实调用，Retry 最外层整体兜底）；实例按 `<target>-retry / -cb / -ratelimit / -bulkhead / -timelimiter` 命名约定查找，**未配置的组件自动跳过**（声明式，无容错时行为与 Step 4 前一致）。
- **关键实现决策（踩坑实证）**：resilience4j 2.x 中 `Registry.instance(name)` 单参重载**一律返回默认配置实例**（`of(Map)` 注册的按名配置不会被采用），且 `getConfiguration(name)` 为空时也会创建默认实例；因此解析统一走 `getConfiguration(name)` 门控 + `instance(name, config)` 双参重载，未注册返回 null 跳过。
- 接线点：`DefaultReActAgent`（LLM 调用 / 工具执行，工具按 `AgentTool.retryable()` 决定是否可重试）、`RedisMemoryStore`（MEMORY）、`InMemoryKnowledgeStore` / `PgVectorKnowledgeStore`（VECTOR）；熔断打开（`CallNotPermittedException`）→ 降级文案"模型服务暂时不可用（熔断中）"。
- 指标：`management.health.circuitbreakers.enabled: true`（熔断打开 → 对应健康检查 DOWN，可联动告警）；resilience4j 事件日志 DEBUG。
- 测试：`ResilienceSupportTest` 5 项（重试自愈 / 熔断快速失败 / 限流拒绝 / 慢调用超时 / 舱壁隔离）+ `DefaultReActAgentTest` 新增 2 项（瞬时失败重试自愈、熔断后友好文案）+ `TestResilience` 测试助手，全量 60 项通过。

---

## 8. 状态持久化设计（Step 6）

| 存储 | 内容 | 说明 |
| --- | --- | --- |
| Redis | 会话快照、执行中任务状态 | 热状态，TTL 兜底 |
| PostgreSQL | 任务表、执行轨迹表、记忆审计表 | 冷存储，审计与恢复 |

**任务表草案**

```sql
CREATE TABLE agent_task (
    task_id       VARCHAR(64) PRIMARY KEY,
    session_id    VARCHAR(64) NOT NULL,
    user_id       VARCHAR(64) NOT NULL,
    state         VARCHAR(32) NOT NULL,   -- AgentState
    input         TEXT,
    output        TEXT,
    iterations    INT DEFAULT 0,
    created_at    TIMESTAMP DEFAULT now(),
    updated_at    TIMESTAMP DEFAULT now(),
    finished_at   TIMESTAMP
);

CREATE TABLE agent_trace (
    id           BIGSERIAL PRIMARY KEY,
    task_id      VARCHAR(64) NOT NULL,
    seq          INT NOT NULL,
    role         VARCHAR(16) NOT NULL,    -- SYSTEM/USER/ASSISTANT/TOOL
    content      TEXT,
    tool_name    VARCHAR(128),
    created_at   TIMESTAMP DEFAULT now()
);
```

恢复机制：任务中断后，读取 `agent_task` + `agent_trace` 重建 `AgentContext` 与历史，从断点继续迭代。

---

## 9. 可观测性与安全

- **日志**：每轮迭代打印 Thought/Action/Observation（脱敏后）；全局异常统一收敛；
- **指标**：Actuator 暴露 `metrics`，统计迭代次数、工具调用耗时、容错触发次数；
- **审计**：`agent_trace` 完整记录工具调用参数与结果，满足合规追溯；
- **安全**：
  - 工具白名单 + 权限映射（按用户/角色控制可用工具）；
  - Prompt 注入防护：工具结果作为数据而非指令处理，敏感操作（写库/发消息）二次确认；
  - 密钥管理：API Key 走环境变量/密钥托管，不入代码库（见 `.gitignore`）。

---

## 10. 分步实施计划

| 步骤 | 目标 | 关键交付物 | 验收标准 | 状态 |
| --- | --- | --- | --- | --- |
| **Step 1** | 基础框架 | Maven 工程、分层骨架、核心契约（Agent/Tool/Memory/Vector）、ToolRegistry、统一接口、配置体系、测试 | 工程可编译；`mvn test` 通过；服务可启动；接口可调用 | ✅ 已交付 |
| **Step 2** | ReAct 编排 | DefaultReActAgent 实现（ChatModel 手动循环）、AgentToolBridging 工具桥接（FunctionTool）、迭代与终止逻辑 | Mock/真实 LLM 下可完成"规划→调用工具→多轮→回答"闭环；超迭代/异常正确终止 | ✅ 已交付 |
| **Step 3** | Redis 多层记忆 | RedisMemoryStore 实现、滚动会话记录、记忆注入与持久化、自动降级 | 跨会话/多轮记忆命中；Redis 不可用时降级不崩溃；真实 Redis 集成测试通过（REDIS_IT=true） | ✅ 已交付 |
| **Step 4** | PGVector 知识检索 | 文档入库管线（切分/向量化/双存储）、RAG 检索注入、命名空间隔离、知识接口 | 知识库问答命中率达标；命名空间隔离生效；真实 PGVector 集成测试通过（PGVECTOR_IT=true） | ✅ 已交付 |
| Step 5 | Resilience4j 容错 | 策略配置 + 降级实现 + 容错指标 | 模拟 LLM/Redis 故障时系统不雪崩、可降级 | ✅ 已交付 |
| Step 6 | 持久化与生产化 | 任务状态机、轨迹持久化、鉴权、部署（Docker/K8s） | 任务断点恢复；审计轨迹完整；可灰度上线 | 待实施 |
| **Step M** | LLM 端到端 Mock 模块 | ChatModel 装饰器 + 剧本引擎 + 随机性注入 + 条件装配 | 开关开启时全链路可跑通（无真实 Key）；scripted 模式可复现；random 模式有随机性；35 项测试通过 | ✅ 已交付 |

每步独立可交付、可回滚；后续步骤不破坏 Step 1 契约（接口稳定是硬约束）。

---

## 11. 交付说明（Step 1 ~ Step 3 + Step M + Step 4 + Step 5）

### 11.1 Step 1 已落地内容

- 分层工程骨架（`common / config / agent.core / agent.tool / agent.memory / agent.vector / agent.resilience / service / controller`）；
- 核心契约：`AgentState`、`AgentMessage`、`AgentContext`、`AgentResult`、`ReActAgent`（接口）、`AgentTool`、`ToolRegistry`、`MemoryLevel`、`MemoryStore`、`VectorKnowledgeStore`；
- 示例工具：`get_server_time`、`get_server_info`（验证注册表链路）；
- 统一响应/错误码/全局异常处理；
- 配置体系：`cosy.agent.*` 与外部依赖全部环境变量化。

### 11.2 Step 2 已落地内容

- `DefaultReActAgent`：Thought → Action → Observation 循环，**基于 ChatModel 手动驱动**（非 ChatClient 自动执行），完整可控地记录轨迹、执行工具、判定终止；
- `AgentToolBridging`：`AgentTool`（name/description/parameters）→ OpenAI `FunctionTool`（JSON Schema）桥接，`OpenAiChatOptions` 启动时按注册表生成工具定义；
- 终止条件：最终回答 / `max-iterations` 超限（TIMEOUT）/ LLM 异常（FAILED）；未知工具或工具执行异常以 Observation 形式回传模型；
- 测试：`DefaultReActAgentTest`（脚本化 ChatModel 桩：正常闭环、未知工具、超迭代、LLM 异常）+ `AgentApiIntegrationTest`（Mock LLM 端到端）+ 原有测试，共 11 项全部通过。

> 说明：真实 LLM 链路需配置 `OPENAI_API_KEY`；未配置时请求返回 `state=FAILED` 与错误信息（优雅降级，不崩溃）。

### 11.3 Step 3 已落地内容

- `RedisMemoryStore`：`MemoryLevel` 分层（WORKING/SESSION/LONG_TERM）、Key 前缀 `cosy:work|session|long`、TTL 分层（record 自带 TTL 优先，否则用层级默认）；
- 记忆接入 ReAct 循环：系统提示注入【会话记忆】+【用户长期记忆】；运行后持久化滚动会话（最近 6 条）与工作状态；读写失败自动降级；
- 契约调整：`MemoryRecord` / `MemoryStore` 使用 `namespace`（会话用 sessionId、长期记忆用 userId）；
- 配置：`cosy.agent.working-timeout`（默认 10m）；Redis 纳入健康检查；
- 测试：`RedisMemoryStoreTest`（Mock 单测 6 项）+ `RedisMemoryStoreIntegrationTest`（真实 Redis 3 项，`REDIS_IT=true` 时执行）+ Agent 记忆集成测试（注入/持久化/降级 3 项），全量 23 项通过。

### 11.4 运行与验证

```bash
mvn test                        # 全部单测通过（54 项；PG/Redis 集成默认跳过）
REDIS_IT=true PGVECTOR_IT=true mvn test   # 全量 60 项：追加真实 Redis（3）+ 真实 PGVector（3）集成测试（需本地 Redis/PostgreSQL）
mvn spring-boot:run             # 启动（Redis/PG 不可用时对应能力自动降级）

curl http://localhost:8080/api/agent/status
curl http://localhost:8080/api/agent/tools
curl -X POST http://localhost:8080/api/agent/knowledge/upsert \
  -H 'Content-Type: application/json' \
  -d '{"namespace":"hr","docId":"kb-1","content":"重置企业账号密码的操作步骤：..."}'
curl -X POST http://localhost:8080/api/agent/knowledge/search \
  -H 'Content-Type: application/json' -d '{"namespace":"hr","query":"如何重置密码","topK":3}'
curl -X POST http://localhost:8080/api/agent/chat \
  -H 'Content-Type: application/json' -d '{"sessionId":"s1","message":"如何重置密码？"}'
```

### 11.5 仓库地址

`https://github.com/Han03/CosyAgent.git`（Step 1 ~ Step 3 + Step M + Step 4 + Step 5 代码已推送 main 分支，commit 链 `7ceef15 → 462b7ed → 52c6ce5 → ae49599 → 96e1e48 → 9371bdb`）

### 11.6 Step M 已落地内容

- `MockChatModelDecorator`：ChatModel 装饰器，`cosy.agent.mock.enabled` 开关路由（开启→剧本引擎，关闭→透传真实模型）；`MockChatConfig` 条件装配（@Primary 覆盖自动配置 Bean `openAiChatModel`）；
- `MockScriptEngine`：按 Prompt 历史推导推进位置（无共享状态，支持并发）；5 个预置剧本覆盖单工具 / 多轮多工具 / 未知工具自愈 / 超迭代路径；最终回答引用真实工具观察结果；
- 随机性：scripted 固定种子可复现（CI）；random 以用户消息哈希+运行时刻为锚，每次运行独立随机；行为注入（附加轮 / 未知工具 / 多工具并行 / 模型异常）概率可配；
- 全链路验证：`MockE2eIntegrationTest`（无真实 Key 走通 HTTP→ReAct→工具执行→记忆持久化）+ `MockScriptEngineTest`（8 项）+ `MockChatModelDecoratorTest`（2 项），全量 35 项通过；真实运行验证随机性与多剧本切换正常。

### 11.7 Step 4 已落地内容

- `agent.vector`：`Vectorizer`（接口）+ `DeterministicVectorizer`（确定性 2-gram 哈希向量，无外部依赖）；`DocumentChunker`（分块/重叠可配）；`InMemoryKnowledgeStore`（默认）与 `PgVectorKnowledgeStore`（真实 PGVector，原生 JDBC，`cosy.agent.vector.store=pgvector` 条件装配）双存储；
- RAG 注入：`DefaultReActAgent` 运行前以用户输入检索（TopK + 阈值），命中注入【知识库检索结果】到系统提示；检索失败降级跳过；命中日志可观测；
- 知识接口：`POST /api/agent/knowledge/upsert`（入库切分）+ `/search`（检索，命名空间可指定）；
- 测试：`DocumentChunkerTest`（4）+ `DeterministicVectorizerTest`（2）+ `InMemoryKnowledgeStoreTest`（5）+ `DefaultReActAgentTest` RAG 注入/降级（+2）+ `KnowledgeApiIntegrationTest`（2）+ `PgVectorKnowledgeStoreIntegrationTest`（3，`PGVECTOR_IT=true` 在真实 PostgreSQL 14 + pgvector 0.8.6 上通过），全量 56 项通过；
- 真实运行验证（Mock 模式 28080）：入库（切分 1 块）→ 检索命中（score 0.34 > 阈值 0.15）→ 命名空间隔离（hr 与 it 互不可见）→ 对话触发 RAG（日志"知识库命中 1 条注入系统提示"）。

### 11.8 Step 5 已落地内容

- `agent.resilience`：`ResilienceTarget`（LLM/TOOL/MEMORY/VECTOR）+ `ResilienceSupport`（组合链 Retry→CB→RateLimiter→Bulkhead→TimeLimiter，按 `<target>-<组件>` 命名约定查找，未配置组件自动跳过）；
- 接线：`DefaultReActAgent`（LLM 调用 / 工具执行，`AgentTool.retryable()` 决定工具是否可重试）、`RedisMemoryStore`（MEMORY）、`InMemoryKnowledgeStore`/`PgVectorKnowledgeStore`（VECTOR）；`CallNotPermittedException` 降级为"模型服务暂时不可用（熔断中）"；
- 配置：`application.yml` resilience4j 五类策略全量声明（llm 3×2s / tool 2×1s / memory 2×500ms / vector 2×500ms 重试；20 窗口 50% 熔断；llm 60/min、tool 120/min 限流；60s/30s/2s/5s 超时；8 并发舱壁）+ 健康检查 circuitbreakers 指标；
- **关键实现决策**：resilience4j 2.x `instance(name)` 单参重载返回默认配置（按名配置不生效），统一改 `getConfiguration(name)` 门控 + 双参重载（详见 7.4）；
- 测试：`ResilienceSupportTest`（5）+ `DefaultReActAgentTest` 容错用例（+2）+ `TestResilience` 助手，全量 60 项通过（含真实 Redis 3 + PGVector 3）；Mock 模式真实运行验证：启动健康 UP、对话走通"规划→工具→回答"闭环。

---

## 12. 风险与演进

| 风险 | 应对 |
| --- | --- |
| 模型上下文窗口限制 | 滚动摘要 + 轨迹裁剪（第 4.4 节） |
| 工具调用幻觉（参数错误） | 工具参数 JSON Schema 校验 + 失败 Observation 回传重试 |
| 长任务不可控 | 最大迭代上限 + 任务超时 + 状态持久化可恢复 |
| 向量召回质量波动 | 阈值/TopK 调参 + 混合检索 + 向量重建流程 |
| 外部依赖抖动 | Resilience4j 全链路覆盖 + 降级路径明确 |

**演进方向**：多 Agent 协作（Planner/Executor 拆分）、流式输出（SSE）、插件化工具市场、评估集（Agent Eval）自动化回归。

---

## 13. 附录：LLM 端到端 Mock 模块

独立设计文档：[`LLM Mock 端到端模块设计方案.md`](LLM%20Mock%20端到端模块设计方案.md)（v0.1 设计稿）。

核心要点：`MockChatModelDecorator`（ChatModel 装饰器，`cosy.agent.mock.enabled` 开关路由）+ 剧本引擎（预置 5 场景，覆盖单工具/多工具/自愈/超迭代路径）+ 双层随机性（剧本选择与行为概率注入），`scripted+seed` 可复现、`random` 真随机；仅替换 LLM 推理，工具执行、ReAct 编排、Redis 记忆全链路真实运行。**已落地（35 项测试通过）。**
