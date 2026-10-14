# CosyAgent

基于 **Spring AI** 的企业级 **ReAct 智能体（Agent）** 系统，配 Flutter 桌面/移动客户端。多模块微服务架构（agent / gateway / knowledge / capability），Nacos 注册与配置中心、RocketMQ 异步解耦、SkyWalking + ELK 全链路可观测，实现模型自主任务规划、工具调用、多轮迭代与状态持久化。

## 核心能力

- **ReAct 编排**：思考 → 工具调用 → 观察 → 收敛的多轮自主执行，工具桥接复用 Spring AI 函数调用机制
- **模型路由**：模型平台与路由规则前端可视化配置，Auto 决策层按任务标签与调用统计自动选路，支持按序降级
- **LLM Mock**：开关式端到端模拟，无需任何 API Key 即可跑通全链路（含随机性与延迟模拟，请求头 `X-Cosy-Mock` 可请求级切换）
- **微服务化**：Gateway 统一网关、Nacos 注册/配置中心、RocketMQ 异步事件管道、SkyWalking 链路追踪 + ELK 日志聚合
- **多层记忆 / 向量知识库 / 容错**：Redis（Redisson，工作/会话/长期）、cosy-knowledge 知识检索、Resilience4j（Retry / CircuitBreaker / RateLimiter / TimeLimiter / Bulkhead）
- **调用留痕**：每次 LLM 调用的请求、响应与工具注入全量记录（RocketMQ 异步落库），用于问题排查
- **双持久化**：`memory | mysql` 一键切换，MyBatis-Plus 收口业务 CRUD，Schema 自动迁移

## 技术栈

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| Java | 25 | 后端运行时（SkyWalking webapp 需 JDK21） |
| Spring Boot | 4.0.8 | 应用框架 |
| Spring AI | 2.0.1 | LLM 接入（OpenAI 协议）、ChatClient、工具调用、向量存储抽象 |
| Spring Cloud | 2025.1.3 | OpenFeign / LoadBalancer / Gateway 网关 |
| Spring Cloud Alibaba | 2025.1.0.0 | Nacos 注册与配置中心、RocketMQ 绑定 |
| Redis | 7.x | 多层记忆（Redisson 4.8.0 分布式化） |
| PostgreSQL + PGVector | 16+（可选） | 知识库向量检索（knowledge 服务 store=pgvector） |
| MySQL | 8.x | 业务数据持久化（MyBatis-Plus） |
| MyBatis-Plus | 3.5.17 | 业务 CRUD 统一收口 |
| RocketMQ | 5.3.x | LLM 调用记录 / 能力变更异步事件 |
| SkyWalking | 9.8.0 | 分布式链路追踪（agent 注入 + OAP + UI） |
| ELK | 8.17.4 | 日志聚合与检索（Filebeat → ES → Kibana） |
| Resilience4j | 2.4.0 | Retry / CircuitBreaker / RateLimiter / TimeLimiter / Bulkhead |
| Flutter | 3.x | 桌面端 + 移动端客户端（CosyAgentClient） |

## 快速启动（Mock 模式）

无需 API Key 即可体验完整 Agent 流程（LLM Mock 模块，路由配置 mock 平台）：

```powershell
# 1. 一键启动（中间件 + 构建 + 4 个微服务 + 健康探测）
powershell -ExecutionPolicy Bypass -File C:\MyProjects\CosyAgent\start.ps1

# 2. 启动客户端（另开终端）
cd C:\MyProjects\CosyAgentClient
flutter run -d windows
```

连接真实模型与数据库：客户端「设置」中配置模型平台 API Key 与路由；业务数据持久化设置 `COSY_AGENT_PERSISTENCE=mysql`（start.ps1 已默认开启）。
