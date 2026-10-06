# CosyAgent

基于 **Spring AI** 的企业级 **ReAct 智能体（Agent）** 系统，配 Flutter 桌面/移动客户端。整合 Redis 多层记忆、PGVector 向量检索、Resilience4j 容错机制，实现模型自主任务规划、工具调用、多轮迭代与状态持久化。

## 核心能力

- **ReAct 编排**：思考 → 工具调用 → 观察 → 收敛的多轮自主执行，工具桥接复用 Spring AI 函数调用机制
- **模型路由**：模型平台与路由规则前端可视化配置，Auto 决策层按任务标签与调用统计自动选路，支持按序降级
- **LLM Mock**：开关式端到端模拟，无需任何 API Key 即可跑通全链路（含随机性与延迟模拟）
- **多层记忆 / 向量知识库 / 容错**：Redis（工作/会话/长期）、PGVector 检索、Resilience4j（Retry / CircuitBreaker / RateLimiter / TimeLimiter / Bulkhead）
- **调用留痕**：每次 LLM 调用的请求、响应与工具注入全量记录，用于问题排查
- **双持久化**：`memory | mysql` 一键切换，MyBatis-Plus 收口业务 CRUD，Schema 自动迁移

## 技术栈

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| Java | 17 | 运行时 |
| Spring Boot | 3.5.16 | 应用框架 |
| Spring AI | 1.1.8 | LLM 接入（OpenAI 协议）、ChatClient、工具调用、向量存储抽象 |
| Redis | 6+ | 多层记忆（工作 / 会话 / 长期） |
| PostgreSQL + PGVector | 16+ | 知识库向量检索 |
| MySQL | 8.x | 业务数据持久化（MyBatis-Plus） |
| MyBatis-Plus | 3.5.17 | 业务 CRUD 统一收口 |
| Resilience4j | 2.4.0 | Retry / CircuitBreaker / RateLimiter / TimeLimiter / Bulkhead |
| Flutter | 3.x | 桌面端 + 移动端客户端（CosyAgentClient） |

## 快速启动（Mock 模式）

无需 API Key，也无需 Redis / PG / MySQL，一条命令即可体验完整 Agent 流程：

```bash
# 1. 启动后端（memory 模式，零外部依赖）
mvn spring-boot:run

# 2. 启动客户端（另开终端）
cd ../CosyAgentClient
flutter run -d windows
```

连接真实模型与数据库：客户端「设置」中配置模型平台 API Key 与路由；业务数据持久化设置 `COSY_AGENT_PERSISTENCE=mysql`。

