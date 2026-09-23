# CosyAgent Flutter 客户端设计方案

> 版本：v0.1（设计稿） · 服务端：CosyAgent（Spring AI ReAct Agent，接口见 [`接口文档.md`](接口文档.md)）
> 目标：以 CosyAgent 为后端，构建跨平台（Android / iOS / Windows / macOS / Web）的企业级 AI 助手客户端。

## 1. 背景与目标

### 1.1 背景

CosyAgent 服务端已提供完整的 Agent 能力：

- **对话**：ReAct 全链路（规划 → 工具调用 → 多轮迭代 → 回答），返回回答 + 推理轨迹 + 任务 ID；
- **任务**：状态机（INIT→RUNNING→COMPLETED/FAILED/TIMEOUT）、任务/轨迹持久化、会话列表、断点恢复（resume）；
- **知识库**：文档入库（切分/向量化）与向量检索（RAG），命名空间隔离；
- **运维**：统一响应结构、X-API-Key 鉴权、健康检查、Mock 模式（无 Key 可全链路演示）。

### 1.2 目标

| 目标 | 说明 |
| --- | --- |
| 多端覆盖 | 移动端（Android/iOS）+ 桌面端（Windows/macOS/Linux）+ Web，一套代码 |
| 对话体验 | 消息流展示、工具调用轨迹可视化、多轮会话、断点恢复 |
| 任务治理 | 任务列表/详情/轨迹时间线、失败重试、会话维度管理 |
| 企业可用 | 服务地址与密钥可配置、安全存储、统一错误处理、离线缓存 |
| 可测试 | 仓库/页面分层清晰，单测与集成测试可跑（可对接本机 Mock 冒烟） |

### 1.3 非目标（本期不做，列为演进）

- SSE / WebSocket 流式输出（服务端当前为同步 HTTP，演进项见 §10）；
- 语音对话、多模态输入；
- 复杂多 Agent 协作界面。

## 2. 总体架构

```
┌─────────────────────────────────────────────────────────────┐
│  UI 层（Flutter Widgets）                                     │
│  对话页 / 任务页 / 任务详情页 / 知识库页 / 设置页 / 会话列表     │
├─────────────────────────────────────────────────────────────┤
│  状态管理层（Riverpod）                                       │
│  ChatController / TaskListController / TaskDetailController  │
│  KnowledgeController / SettingsController / AuthController   │
├─────────────────────────────────────────────────────────────┤
│  应用服务层（UseCase）                                         │
│  ChatUseCase / TaskUseCase / KnowledgeUseCase / AuthUseCase  │
├─────────────────────────────────────────────────────────────┤
│  数据层（Repository）                                         │
│  ChatRepository / TaskRepository / KnowledgeRepository /     │
│  SystemRepository / SessionLocalRepository                   │
├─────────────────────────────────────────────────────────────┤
│  网络与基础设施                                               │
│  Dio（拦截器链：Auth/Error/Log） + Flutter Secure Storage     │
│  + Hive（本地缓存） + go_router（路由）                        │
└─────────────────────────────────────────────────────────────┘
                    │ HTTP JSON（见接口文档 §5）
              CosyAgent 服务端
```

设计原则：

- **单向依赖**：UI → 状态管理 → UseCase → Repository → 网络，禁止反向；
- **接口驱动**：数据模型与 `接口文档.md` 的 DTO 一一对应，字段级对齐；
- **可配置**：服务地址 / API Key / Mock 开关均可在设置页调整，经 `--dart-define` 提供默认值；
- **失败可见**：所有网络错误映射为统一的客户端错误模型（对应服务端错误码表）。

## 3. 技术选型

| 领域 | 选型 | 理由 |
| --- | --- | --- |
| 语言/框架 | Flutter 3.32+ / Dart 3.8 | 一套代码覆盖移动+桌面+Web |
| 状态管理 | Riverpod 2.x | 编译期安全、易测试、依赖注入与异步状态天然契合 |
| 网络 | Dio 5.x | 拦截器链（鉴权/错误/日志）、超时与取消、适配 SSE 演进 |
| 路由 | go_router | 声明式路由、深链、Web 友好 |
| 本地存储 | Hive 4 + flutter_secure_storage | 轻量 KV 缓存；API Key 等高敏数据走安全存储 |
| 时间线 UI | 自绘 + flutter_animate | 轨迹时间线、状态动画轻量可控 |
| 测试 | mockito / dio adapter mock + flutter_test | Repository 单测不依赖真实网络 |
| 平台状态 | Windows/macOS 原生窗口 + 移动端适配（ResponsiveBuilder） | 桌面/移动同一套响应式布局 |

## 4. 网络层设计

### 4.1 ApiClient

- `baseUrl`：默认 `http://localhost:8080`（移动端真机需指向局域网/线上地址），设置页可改；
- 超时：连接 10s / 读取 30s（Agent 长任务场景放宽读取超时）；
- 统一返回 `Result<T>` 解包：`code == 0` 取 `data`；否则抛 `ApiException(code, message)`。

拦截器链（Dio Interceptor）：

```
AuthInterceptor → ErrorInterceptor → LogInterceptor
```

| 拦截器 | 职责 |
| --- | --- |
| AuthInterceptor | 从 secure storage 读取 API Key，注入 `X-API-Key` 头（未配置不注入）；401 时通知 AuthController 触发跳转设置页 |
| ErrorInterceptor | 网络错误→`NetworkError`；超时→`TimeoutError`；HTTP 5xx→`ServerError`；业务 code≠0→`ApiException(code,message)` |
| LogInterceptor | debug 模式打印请求/响应（脱敏：不打印 X-API-Key） |

### 4.2 数据模型（与服务端 DTO 对齐）

| 客户端模型 | 服务端 | 关键字段 |
| --- | --- | --- |
| `AgentResult` | AgentResult | sessionId, answer, state, trace, iterations, costMs, errorMessage, taskId |
| `AgentMessage` | AgentMessage | role, content, toolCallId, toolName, toolArguments, timestamp |
| `AgentTask` | AgentTask | taskId, sessionId, userId, state, input, output, iterations, costMs, errorMessage, 时间戳×3 |
| `TaskDetail` | TaskDetail | task + trace |
| `KnowledgeHit` | KnowledgeHit | docId, content, score |
| `AgentState`（枚举映射） | AgentState | INIT/PLANNING/RUNNING/TOOL_CALLING/COMPLETED/FAILED/TIMEOUT/CANCELLED |

### 4.3 Repository 接口

```dart
abstract class ChatRepository {
  Future<AgentResult> chat(String sessionId, String message);
  Future<AgentResult> resume(String taskId, String message);
}
abstract class TaskRepository {
  Future<TaskDetail> getTask(String taskId);
  Future<List<AgentTask>> listTasks({String? sessionId, int limit = 20});
}
abstract class KnowledgeRepository {
  Future<KnowledgeUpsertResult> upsert({String? namespace, String? docId, required String content});
  Future<List<KnowledgeHit>> search({String? namespace, required String query, int? topK});
}
abstract class SystemRepository {
  Future<StatusInfo> status();
  Future<List<String>> tools();
  Future<HealthInfo> health();
}
```

## 5. 页面与交互设计

### 5.1 页面清单

| 页面 | 路由 | 核心能力 |
| --- | --- | --- |
| 会话列表页 | `/` | 本地会话分组、新建会话、进入对话 |
| 对话页 | `/chat/:sessionId` | 消息流、输入框、工具调用轨迹、断点恢复入口 |
| 任务页 | `/tasks` | 按会话/全部查看任务、状态筛选 |
| 任务详情页 | `/tasks/:taskId` | 主记录 + 轨迹时间线、失败重试、resume |
| 知识库页 | `/knowledge` | 文档入库（namespace/docId/content）、检索、结果列表 |
| 设置页 | `/settings` | 服务地址、API Key、Mock 开关、清除本地缓存 |

### 5.2 对话页交互

- 消息气泡：USER 右、ASSISTANT 左；TOOL 消息折叠展示（工具名 + 参数 + 结果，可展开）；
- 发送中状态：`AgentState.RUNNING` 显示思考动画 + 实时迭代计数（`iterations`）；
- 回答完成后：展示 `taskId`，提供「查看任务详情」「继续对话（resume）」操作；
- 失败态：`state=FAILED` 显示 `errorMessage` + 重试按钮；
- 会话切换：右侧/底部抽屉切换会话，会话元数据（标题=首条消息摘要、更新时间）本地缓存。

### 5.3 任务详情页（轨迹时间线）

- 顶部：任务状态徽章 + 主记录（输入/输出/迭代数/耗时/时间）；
- 中部：`trace` 时间线——USER/ASSISTANT/TOOL 三类节点按序渲染，TOOL 节点展示 `toolName` 与结果；
- 底部：状态为终态时提供「继续执行（resume）」；`resume` 成功则跳转新任务详情。

### 5.4 知识库页

- 入库表单：namespace（可选，默认服务端配置）、docId（可选）、content（必填）；
- 返回展示 `chunks` 数量；检索框输入 query/topK，结果列表展示 `docId`/`content`/`score`，按分数降序。

### 5.5 设置页

- 服务地址（`baseUrl`）、API Key（安全存储写入）、Mock 模式开关提示（服务端环境变量）、测试连通（调 `/actuator/health`）；
- 401 触发：全局提示「API Key 无效」并引导至本页。

## 6. 本地状态与离线策略

- **会话列表**：Hive 缓存（会话 id、标题、更新时间、未读标记），对话历史按需增量拉取；
- **任务缓存**：最近 50 条任务本地缓存，断网可看；联网后下拉刷新；
- **断点恢复离线感知**：resume 失败（网络错误）保留输入草稿，恢复后重试；
- **配置缓存**：baseUrl/API Key 仅存安全存储，Mock 开关为服务端配置（客户端只展示状态，不控制）。

## 7. 安全与配置

- API Key 使用 `flutter_secure_storage`（Android Keystore / iOS Keychain / Windows Credential Manager / macOS Keychain）；
- 日志脱敏：禁止打印 X-API-Key 与完整 toolArguments（敏感工具参数）；
- 构建配置：`--dart-define=COSY_BASE_URL=...`、`--dart-define=COSY_API_KEY=...` 提供默认值，运行时可在设置页覆盖；
- 桌面端（Windows）提示：连接远程服务时需在防火墙放行出站端口。

## 8. 测试与质量

| 层级 | 内容 |
| --- | --- |
| 单元测试 | Repository（dio mock adapter 模拟各接口与错误码）、状态机映射（AgentState→UI 状态） |
| Widget 测试 | 消息气泡渲染、TOOL 折叠、任务时间线、表单校验 |
| 集成冒烟 | 连接本机 Mock 模式服务（`COSY_AGENT_MOCK_ENABLED=true`，端口 28080）：chat → 任务详情 → resume 全链路 |
| CI | GitHub Actions：`flutter analyze && flutter test`（Linux runner 即可） |

## 9. 目录结构（建议）

```
cosy_agent_app/
├── lib/
│   ├── main.dart                # 入口（ProviderScope + MaterialApp.router）
│   ├── app/                     # 路由表、主题、常量
│   ├── core/
│   │   ├── network/             # dio client、拦截器、ApiException
│   │   ├── storage/             # secure storage、hive box 封装
│   │   └── error/               # 错误码→文案映射
│   ├── models/                  # 与服务端 DTO 对齐的模型（含 fromJson）
│   ├── data/
│   │   └── repositories/        # Chat/Task/Knowledge/System Repository
│   ├── domain/                  # UseCase（可选精简层）
│   ├── providers/               # Riverpod providers
│   ├── features/
│   │   ├── chat/                # 会话列表 + 对话页 + 消息组件
│   │   ├── tasks/               # 任务列表 + 详情时间线
│   │   ├── knowledge/           # 知识库页
│   │   └── settings/            # 设置页
│   └── widgets/                 # 通用组件（状态徽章、错误视图、加载态）
└── test/
    ├── unit/                    # repository、状态映射
    └── widget/                  # 页面组件测试
```

## 10. 与接口的映射关系

| 服务端接口（接口文档 §5） | 客户端使用方 |
| --- | --- |
| POST `/api/agent/chat` | ChatRepository.chat → 对话页发送 |
| POST `/api/agent/tasks/{taskId}/resume` | ChatRepository.resume → 对话页/任务详情页 |
| GET `/api/agent/tasks/{taskId}` | TaskRepository.getTask → 任务详情页 |
| GET `/api/agent/tasks` | TaskRepository.listTasks → 任务页 |
| POST `/api/agent/knowledge/upsert` | KnowledgeRepository.upsert → 知识库页 |
| POST `/api/agent/knowledge/search` | KnowledgeRepository.search → 知识库页 |
| GET `/api/agent/status` | SystemRepository.status → 设置页连通性 |
| GET `/api/agent/tools` | SystemRepository.tools → 设置页展示能力 |
| GET `/actuator/health` | SystemRepository.health → 设置页连通性测试 |

## 11. 演进路线

| 阶段 | 内容 |
| --- | --- |
| P0（本期） | 上述全部页面 + 网络层 + 单测 + 本机 Mock 冒烟 |
| P1 | SSE 流式输出（服务端加流式端点，客户端事件源解析、打字机效果） |
| P2 | 实时任务进度（WebSocket 推送状态机迁移）、语音对话、离线完整缓存 |
| P3 | 多会话分组/搜索、企业 SSO 接入、消息富媒体（图表/文档预览） |

## 12. 附录：与服务端联调姿势

```bash
# 服务端（本机 Mock 模式，无 Key 全链路）
cd C:\MyProjects\CosyAgent
$env:COSY_AGENT_MOCK_ENABLED='true'; $env:SERVER_PORT='28080'
java -jar target\cosy-agent-0.1.0-SNAPSHOT.jar

# 客户端（Windows 桌面）
flutter run -d windows --dart-define=COSY_BASE_URL=http://localhost:28080
```
