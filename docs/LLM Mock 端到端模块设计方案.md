# CosyAgent LLM 端到端 Mock 模块设计方案

> 版本：v0.2（已落地，35 项测试通过）
> 前置：Step 1（基础框架）/ Step 2（ReAct 编排）/ Step 3（Redis 多层记忆）已交付
> 关联主文档：`docs/CosyAgent设计方案.md`（Step 1 ~ Step 3 + Step M）

---

## 1. 背景与目标

### 1.1 背景

真实 LLM 链路依赖 `OPENAI_API_KEY` 与外网连通，带来三类问题：

- **开发/联调**：无 Key、断网或配额耗尽时，全链路（HTTP → 编排 → ReAct → 记忆 → Redis）无法端到端验证；
- **CI/演示**：自动化回归与对外演示需要稳定、可复现的完整链路，不能依赖外部模型；
- **故障演练**：需要可控地触发模型异常（超迭代、未知工具、调用失败），验证编排层各终止路径与降级行为。

### 1.2 目标

新增 **LLM 端到端 Mock 模块**（代码路径 `agent.mock`）：

1. **开关控制**：`cosy.agent.mock.enabled` 开启后，用预制模拟数据替代模型推理；关闭时完全走真实 LLM，零侵入；
2. **全链路可跑通**：仅替换「LLM 推理」一个外部依赖，其余组件（工具注册表、工具真实执行、ReAct 编排、Redis 记忆读写、HTTP 接口、状态机、降级）**全部真实执行**；
3. **带随机性**：模拟模型的非确定性——剧本选择、工具调用序列、回复措辞、行为概率（未知工具/异常/多工具并行）均可随机，同时提供「固定种子」模式保证 CI 可复现；
4. **故障路径可注入**：可控触发 `TIMEOUT`（超迭代）、`FAILED`（模型异常）、未知工具自愈等分支，覆盖 Step 2 终止条件全矩阵。

### 1.3 边界

- 只模拟「模型输出」，不模拟工具执行结果（工具由真实 `ToolRegistry` 执行，保持结果真实）；
- 不影响 Step 1~3 已交付契约（`ReActAgent` / `MemoryStore` / `AgentTool` 接口不变）；
- 不改动既有测试；新增独立 mock 测试与端到端集成测试。

---

## 2. 设计决策

| 决策点 | 方案 | 理由 |
| --- | --- | --- |
| 开关形态 | 配置开关 `cosy.agent.mock.enabled`（默认 false），`@ConditionalOnProperty` 装配 | 关闭时零开销、零侵入；开启时替换模型 Bean |
| 接入方式 | **ChatModel 装饰器** `MockChatModelDecorator implements ChatModel`，持有真实 delegate | ReAct 编排/工具桥接/记忆链路不改一行；保留真实模型引用，支持运行时透传 |
| 随机性架构 | 「剧本（script）主干 + 随机注入」双层：剧本定义任务语义回合，随机源负责选择/变体/概率注入 | 主干保证任务语义成立（工具调用必须真实可执行），随机性保证行为非确定 |
| 可复现性 | `mode=scripted` + `seed`：确定性输出（CI 回归）；`mode=random`：无种子真随机（演示/演练） | 测试与演示需求相反，必须双模式 |
| 工具联动 | 剧本候选动作取自 `ToolRegistry` 已注册工具，保证 mock 生成的调用可被真实执行 | 避免 mock 造出不可执行的工具调用，破坏端到端 |

---

## 3. 模块结构与核心类

```
src/main/java/com/cosy/agent/agent/mock/
├── MockChatModelDecorator.java   # ChatModel 装饰器：mock 开关路由（开启→引擎；关闭→透传 delegate）
├── MockScriptEngine.java         # 引擎：按剧本执行回合，注入随机性，产出 ChatResponse
├── MockScript.java               # 剧本：任务语义回合序列（id/description/turns/随机附加轮）
├── MockTurn.java                 # 回合：TOOL_CALL | FINAL_ANSWER | RAISE_ERROR
├── MockScriptLibrary.java        # 剧本库：预置场景剧本（含未知工具/超迭代专用剧本）
├── MockRandomSource.java         # 随机源：Random(seed) 可复现；seed 为空则系统随机
└── MockChatConfig.java           # 条件装配：mock.enabled=true 时注册 @Primary ChatModel
```

### 3.1 MockChatModelDecorator（接入核心）

```
class MockChatModelDecorator implements ChatModel
  - delegate : ChatModel            # 真实模型引用（开关关闭时透传）
  - engine   : MockScriptEngine     # mock 模式下的输出生成器
  call(Prompt):
    enabled ? engine.generate(prompt) : delegate.call(prompt)
```

装配（`MockChatConfig`）：

- `@ConditionalOnProperty(prefix="cosy.agent.mock", name="enabled", havingValue="true")`；
- `@Bean @Primary ChatModel mockChatModel(ChatModel delegate, MockScriptEngine engine)`——覆盖 Spring AI 自动配置的模型 Bean，其余注入点（`DefaultReActAgent` 等）无需改动；
- `delegate` 懒注入：mock 开启时仍可解析真实模型 Bean（保留透传与运行时切换能力），解析失败则 null（仅 mock 模式运行）。

### 3.2 MockScript / MockTurn（剧本数据）

```
MockScript { id, description, List<MockTurn> turns, int minExtraTurns, int maxExtraTurns }
MockTurn {
  type           : TOOL_CALL | FINAL_ANSWER | RAISE_ERROR
  candidateActions: List<CandidateAction>   # TOOL_CALL：候选工具（含参数模板/文案），引擎随机挑一
  answerVariants : List<String>             # FINAL_ANSWER：文案变体池，引擎随机挑一
}
CandidateAction { toolName, argumentTemplate(Map<String,Object>，支持 {random} 占位), callNote }
```

### 3.3 MockScriptEngine（随机性注入）

每轮生成逻辑：

1. 按剧本 `turns` 顺序推进回合；
2. `TOOL_CALL`：随机挑候选动作，按参数模板生成参数 JSON（`{random}` 占位替换为随机数）；
3. `FINAL_ANSWER`：随机挑文案变体；
4. **随机附加轮**：概率 `extra-turn` 在剧本末追加 1 次工具调用（复用候选动作），模拟模型"多考虑一轮"；
5. **行为注入**（每轮独立判定）：
   - `unknown-tool`：把本轮工具名替换为未注册名 → 触发编排层「未知工具作为 Observation 回传」自愈路径；
   - `multi-tool`：单轮返回多个工具调用 → 触发编排层多工具执行路径；
   - `error`：回合改为 `RAISE_ERROR` → 触发 `AgentState.FAILED` 异常终止路径；
6. 上限保护：mock 自身回合数 ≤ `cosy.agent.mock.max-turns`，配合编排层 `max-iterations` 兜底，不无限循环。

---

## 4. 预置剧本（MockScriptLibrary）

| id | 场景 | 回合主干 | 覆盖路径 |
| --- | --- | --- | --- |
| `time` | 查询当前时间 | 调 `get_server_time` → 回答 | 单工具闭环 |
| `server-info` | 查询服务器信息 | 调 `get_server_info` → 回答 | 单工具闭环（不同工具） |
| `combined` | 综合巡检 | 调 `get_server_time` → 调 `get_server_info` → 综合回答 | 多轮多工具、长链路 |
| `self-heal` | 工具不存在自愈 | 调未注册工具 → 观察「未知工具」→ 改调 `get_server_time` → 回答 | 未知工具自愈（确定性剧本，默认概率注入关闭） |
| `loop-limit` | 持续调用直至超限 | 连续调 `get_server_time` 直至 `max-iterations` | `TIMEOUT` 超迭代终止 |

各剧本 `FINAL_ANSWER` 文案变体池 ≥ 3 条（措辞、时间/数值表述不同），保证同一剧本多次运行输出可感知随机性。`scripted` 模式下指定剧本 id；`random` 模式从剧本库随机选择（`loop-limit` 默认低权重）。

---

## 5. 配置设计

```yaml
cosy:
  agent:
    mock:
      enabled: false          # 总开关：true 走模拟模型，false 走真实 LLM
      mode: random            # random：全随机（演示）；scripted：固定剧本+种子（CI 可复现）
      script: time            # scripted 模式指定剧本 id（random 时忽略）
      seed: 42                # scripted 模式随机种子
      max-turns: 8            # mock 自身回合上限
      probability:            # 行为随机概率（0~1）
        extra-turn: 0.4       # 追加一轮工具调用
        unknown-tool: 0.1     # 工具名替换为未注册名（自愈路径）
        multi-tool: 0.1       # 单轮多工具调用
        error: 0.05           # 模型异常（FAILED 路径）
```

实现：`AgentProperties` 增加嵌套 `MockProperties`（`enabled/mode/script/seed/maxTurns/probability`），`@ConfigurationProperties` 绑定，环境变量可覆盖（`COSY_AGENT_MOCK_ENABLED=true` 等）。

---

## 6. 与全链路各层的关系

| 层 | 交互方式 | mock 开启时的行为 |
| --- | --- | --- |
| `controller` / `AgentOrchestrator` | 不变 | 真实执行 |
| `DefaultReActAgent` | 注入 `ChatModel`（mock 装饰器） | 编排逻辑全真实：解析 `getToolCalls()`、执行工具、回传 Observation、判定终止 |
| `ToolRegistry` / `AgentToolBridging` | 不变 | 工具定义桥接与执行全真实（含未知工具 Observation） |
| `MemoryStore` / `RedisMemoryStore` | 不变 | 记忆注入与持久化全真实（验证跨会话记忆） |
| 状态机与降级 | 不变 | `TIMEOUT` / `FAILED` 路径由行为注入触发，验证终止条件矩阵 |

---

## 7. 测试与验证计划

| 层级 | 内容 | 断言 |
| --- | --- | --- |
| 单元 | `MockScriptEngineTest` | 剧本回合推进；文案变体随机；seed 可复现（同 seed 两次输出一致）；概率注入（unknown-tool/error/multi-tool）各路径生效；max-turns 上限 |
| 单元 | `MockChatModelDecoratorTest` | enabled=true 走引擎；false 透传 delegate |
| 集成 | `MockE2eIntegrationTest`（`@SpringBootTest`，测试属性 `cosy.agent.mock.enabled=true`，**不** Mock ChatModel） | POST `/api/agent/chat` → `state=COMPLETED`、含工具调用轨迹；Redis 真实写入会话记录与工作状态（复用真实 Redis，`REDIS_IT=true` 时含 IT） |
| 集成 | 随机性验证 | 同剧本多次调用，回答文案存在变体差异；`scripted+seed` 重复运行输出一致 |
| 运行 | 真实启动验证（`SERVER_PORT=28080` + mock 开启） | health=UP；多次 curl 观察随机行为；关闭开关后透传真实模型（需 Key，未配置时仍为 FAILED 降级） |

---

## 8. 实施步骤（已全部完成）

| 步骤 | 内容 | 状态 |
| --- | --- | --- |
| M1 | `agent.mock` 核心：MockProperties 配置、MockRandomSource、MockTurn/MockScript、MockScriptEngine、MockChatModelDecorator、MockChatConfig 条件装配 | ✅ 已完成 |
| M2 | 剧本库 5 场景 + 随机性注入（附加轮/未知工具/多工具/异常） | ✅ 已完成 |
| M3 | 集成测试 + 真实运行验证（28080）+ 文档同步（主方案附录/README）+ commit 推送 GitHub | ✅ 已完成 |

### 8.1 落地说明

- 代码：`agent.mock` 6 个类 + `MockChatConfig` 条件装配 + `AgentProperties.Mock` 嵌套配置 + `application.yml`；
- 随机源修正：random 模式以「用户消息哈希 ^ 运行时刻」为锚，保证同问题多次运行输出不同；scripted 模式固定种子完全可复现；
- 剧本关键词优先级：时间 → 综合/巡检/多轮 → 服务器/信息 → 自愈 → 循环超限；
- 测试：`MockScriptEngineTest` 8 项（含 seed 可复现、unknown-tool/multi-tool/error/extra-turn 注入、self-heal、loop-limit）+ `MockChatModelDecoratorTest` 2 项 + `MockE2eIntegrationTest` 2 项（HTTP 全链路 + 自愈 + Redis 记忆持久化断言，`REDIS_IT=true` 时验证），全量 35 项通过；
- 真实运行验证（`COSY_AGENT_MOCK_ENABLED=true SERVER_PORT=28080`）：health=UP；同一问题多次调用输出文案/迭代数不同（随机性生效）；「综合巡检」走多工具剧本（time→info→time），「自愈演示」走未知工具自愈路径。

---

## 9. 风险与说明

| 风险 | 应对 |
| --- | --- |
| mock 与真实模型行为差异导致误判 | mock 只用于开发/CI/演示；生产 `enabled=false`；真实 LLM 验证仍需 Key |
| 随机性使测试不稳定 | `scripted+seed` 保证回归可复现；随机性断言只验「存在变体」，不依赖具体值 |
| 剧本工具调用与注册表脱节 | 候选动作取自 `ToolRegistry` 实时注册表；新增工具自动可被剧本引用 |
