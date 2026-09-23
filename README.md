# CosyAgent

基于 **Spring AI** 构建的企业级 **ReAct 智能体（Agent）** 系统。整合 Redis 多层记忆、PGVector 向量检索、Resilience4j 容错机制，实现模型自主任务规划、工具调用、多轮迭代与状态持久化。

> 完整设计请阅读 [docs/CosyAgent设计方案.md](docs/CosyAgent设计方案.md)

## 技术栈

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| Java | 17 | 运行时 |
| Spring Boot | 3.5.16 | 应用框架 |
| Spring AI | 1.1.8 | LLM 接入、ChatClient、工具调用、向量存储抽象 |
| Redis | 任意 6+ | 多层记忆（工作/会话/长期） |
| PostgreSQL + PGVector | 任意 | 知识库向量检索 |
| Resilience4j | 2.4.0 | Retry / CircuitBreaker / RateLimiter / TimeLimiter / Bulkhead |

## 模块结构（Step 1 ~ Step 2 已落地）

```
src/main/java/com/cosy/agent
├── common/          # 统一响应、错误码、全局异常
├── config/          # AgentProperties、OpenAiChatOptions（含工具定义）
├── agent/
│   ├── core/        # AgentState / AgentMessage / AgentContext / ReActAgent + DefaultReActAgent（Step 2）
│   ├── tool/        # AgentTool 契约 + ToolRegistry + AgentToolBridging（Step 2 桥接 FunctionTool）
│   ├── memory/      # MemoryLevel / MemoryStore 契约（Step 3 实现）
│   ├── vector/      # VectorKnowledgeStore 契约（Step 4 实现）
│   └── resilience/  # 容错设计说明（Step 5 启用）
├── service/         # AgentOrchestrator 编排入口
└── controller/      # /api/agent/** HTTP 接口
```

## 快速开始

```bash
# 1. 配置环境变量（或直接修改 application.yml 默认值）
export OPENAI_BASE_URL=https://api.openai.com
export OPENAI_API_KEY=sk-xxx
export OPENAI_CHAT_MODEL=gpt-4o-mini
export REDIS_HOST=localhost
export REDIS_PORT=6379

# 2. 启动（Step 1 无需外部依赖即可运行）
mvn spring-boot:run

# 3. 验证
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/agent/tools
curl -X POST http://localhost:8080/api/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"demo-1","message":"现在几点？"}'
```

> 说明：`/api/agent/chat` 需配置真实 `OPENAI_API_KEY` 才能走通 LLM 推理；无 Key 时返回 `state=FAILED` 与错误信息（优雅降级）。Step 2 的 ReAct 循环行为已由单元测试（脚本化 ChatModel 桩）与集成测试（Mock LLM）覆盖验证。

## 分步路线图

| 步骤 | 内容 | 状态 |
| --- | --- | --- |
| Step 1 | 基础框架：工程骨架、分层契约、工具注册表、统一接口 | ✅ 已交付 |
| Step 2 | ReAct 编排：ChatModel 手动循环、工具桥接（FunctionTool）、Thought→Action→Observation、终止条件 | ✅ 已交付 |
| Step 3 | Redis 多层记忆：工作/会话/长期记忆读写、摘要与遗忘 | 待实施 |
| Step 4 | PGVector 知识检索：文档切分、向量化、RAG 检索 | 待实施 |
| Step 5 | Resilience4j 容错：重试/熔断/限流/超时/舱壁与降级 | 待实施 |
| Step 6 | 状态持久化与生产化：任务状态机、轨迹存储、安全与可观测性 | 待实施 |

## 测试

```bash
mvn test   # 11 项：上下文加载 + 工具注册表 + ReAct 循环单测 + Agent HTTP 接口集成测试（Mock LLM）
```
