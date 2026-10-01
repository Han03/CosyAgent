# CosyStudio 能力接入设计方案（B 级：长任务 / 流式 / 创作闭环）

> 目标：让 CosyAgent 的 Agent **直接用自然语言操作 CosyStudio 写小说、生成有声书**（选题材 → 建书 → 剧本 → 章节 → 台词 → 角色音色 → 整章合成 → 导出）。
> 范围：B 级接入（长任务异步化、SSE 流式收敛、创作流程编排）；复用已落地的能力注册中心（AP/CP）与 ReAct 循环。

---

## 1. 现状盘点与差距

### 1.1 CosyStudio 接口真实语义（已核实）

| 接口 | 真实语义 | 直接注册的问题 |
| --- | --- | --- |
| `POST /api/audio/synthesize-chapter` | **同步阻塞**，逐行 TTS，分钟级 | 能力超时（2s）必然失败；无任务句柄无法追踪 |
| `POST /api/audio/export-chapter` | 同上，长任务 | 同上 |
| `POST /api/books/scripts/chapters/generate` | `asyncio.create_task` 后台执行，返回"已启动"，**无 task_id** | 无法轮询结果；停止靠 `stop-script-generation` 全局信号 |
| `POST /api/text/chat_stream` | SSE（每行 JSON），流式 | 与能力协议（同步 HTTP JSON）不匹配 |
| `POST /transcribe`（ASR） | 长任务 + 文件上传 | 同上 |
| `GET /api/books/scripts`、`/webnovel-*` 等 | 同步 JSON 查询 | 可直接注册（A 级，见 5.1） |

**结论**：CosyStudio 自身有 `agent_task_manager`（章节台词生成在用），但**没有统一的"提交 → 轮询"对外出口**。B 级接入必须做两件事：

1. **CosyStudio 侧新增薄适配层 `agent_bridge`**：把长任务统一为 `submit → status → result` 三步协议，把 SSE 收敛为可轮询的结果（不改现有业务代码，新增独立 router）。
2. **CosyAgent 能力协议扩展**：`Capability` 增加异步语义（`endpointMode: sync|submit-poll` + 状态/结果路径），调用侧支持轮询；缺省 `sync` 完全向后兼容。

### 1.2 设计原则

- **不改造 CosyStudio 现有业务代码**：适配层独立新增，业务逻辑只被包一层壳。
- **复用已有资产**：任务状态机（CosyAgent `agent.task`）、容错（Resilience4j）、持久化（Redis/MySQL）、SSE 协议（`AgentStreamEvent`）。
- **协议向后兼容**：旧能力（`sync`）行为不变；新能力带 `endpointMode=submit-poll` 才启用轮询。
- **Agent 可见的是"创作能力"而非"接口"**：注册给 Agent 的工具名/描述用创作语义（`cosy_studio_write_chapter`、`cosy_studio_tts_chapter`）。

---

## 2. 总体架构

```
┌─────────────── CosyAgent (Spring Boot :8080) ───────────────┐
│  ReAct 循环 ──> CapabilityRegistry ──> CapabilityProxyTool   │
│     │                    │                                   │
│     │ sync 能力          │ submit-poll 能力（新）             │
│     ▼                    ▼                                   │
│  HTTP 直调           Submit→Poll 执行器（异步轮询 + 状态缓存）  │
└──────────────┬───────────────────────────────────────────────┘
               │ HTTP (adapter 端点)
┌──────────────▼─────────────── CosyStudio (FastAPI :<port>) ──┐
│  agent_bridge 适配层（新增，独立 router，不改业务代码）         │
│  ├─ POST /agent-bridge/tasks         统一提交长任务            │
│  ├─ GET  /agent-bridge/tasks/{id}    状态 + 进度               │
│  └─ GET  /agent-bridge/tasks/{id}/result  最终结果             │
│  └─（内部）复用现有 service：TTS / 章节生成 / 剧本服务          │
│  既有同步接口：/api/books/* /api/webnovel-* /api/media/*       │
└───────────────────────────────────────────────────────────────┘
```

**调用时序（整章合成示例）**

```
Agent: "把《xxx》第 3 章合成音频"
  1. ReAct 选工具 cosy_studio_tts_chapter（endpointMode=submit-poll）
  2. POST /agent-bridge/tasks  {type:"chapter_tts", script_id, chapter_index}
     → {task_id, status:"running"}
  3. 轮询 GET /agent-bridge/tasks/{id}（间隔 3s，最长 10min）
     → status:"success", progress:100, result_summary:"已生成 42 条音频"
  4. GET /agent-bridge/tasks/{id}/result → {audio_urls:[...], history_id}
  5. 向用户返回结果摘要（音频 URL / 章节合成历史）
```

---

## 3. 能力协议扩展（CosyAgent 侧）

### 3.1 Capability 模型新增字段（缺省向后兼容）

```java
// capability 注册 JSON 新增（可选）
{
  "endpointMode": "sync" | "submit-poll",   // 缺省 sync
  "statusPath": "/agent-bridge/tasks/{id}", // submit-poll 必填
  "resultPath": "/agent-bridge/tasks/{id}/result",
  "pollIntervalMs": 3000,                    // 轮询间隔，缺省 3000
  "pollTimeoutMs": 600000                    // 最大等待，缺省 10min
}
```

- `sync`：现有行为不变（单次 HTTP，候选链降级）。
- `submit-poll`：`endpointPath` 为**提交端点**（POST，body=参数），返回 `{task_id}`；随后按 `pollIntervalMs` 轮询 `statusPath`（用 provider 的 baseUrl 拼接，`{id}` 替换为 task_id），`success/failed` 终态后取 `resultPath`；轮询期失败/超时走候选链降级（与 sync 一致）。
- 调用超时放宽：submit 用 5s；单次 poll 用 5s；总时长由 `pollTimeoutMs` 控制（不受全局 call-timeout 限制）。

### 3.2 实现位置

- `Capability` record 加 3 个字段（`endpointMode`、`statusPath`、`resultPath`、`pollIntervalMs`、`pollTimeoutMs`）。
- `CapabilityProxyTool.execute` 内：`sync` 走现逻辑；`submit-poll` 走新增 `PollingExecutor`（RestClient 提交 → ScheduledExecutor 轮询 → 聚合结果），仍包在 `ResilienceTarget.TOOL` 容错内（候选链降级语义不变）。

---

## 4. CosyStudio 适配层设计（agent_bridge）

### 4.1 任务模型

```
Task {
  task_id: uuid,
  type: chapter_tts | line_tts | script_generate | ebook_import | asr | chat_complete,
  status: pending → running → success | failed | cancelled,
  progress: 0-100（可选，长任务上报）,
  params: {...原始参数...},
  result: {摘要 + 完整结果引用},   // 完成时写入
  error: 失败原因,
  created_at / updated_at
}
```

- 持久化：内存 `dict + asyncio.Lock` 即可（CosyStudio 为单机常驻应用；重启后任务丢失可接受——CosyAgent 侧会话状态仍在，可重新提交）。若需跨重启，可落 SQLite（CosyStudio 已有 `data/` 目录惯例）。

### 4.2 端点

| 端点 | 语义 |
| --- | --- |
| `POST /agent-bridge/tasks` | 提交长任务。body：`{type, params...}`；校验 `X-Capability-Call-Token`（可选，配置开启）。返回 `{task_id, status:"pending"}`。内部 `asyncio.create_task` 执行 |
| `GET /agent-bridge/tasks/{id}` | 状态轮询：`{task_id, status, progress, error?, result_summary?}` |
| `GET /agent-bridge/tasks/{id}/result` | 成功后的完整结果（成功即返回；非成功 409） |
| `GET /agent-bridge/healthz` | 探活（供 CP probe 用；直接代理 `/api/resources/health` 语义） |

### 4.3 任务实现（壳层，调用现有 service）

| type | 内部执行 |
| --- | --- |
| `chapter_tts` | `audio_synthesize` 的整章合成逻辑（逐行 TTS → 保存 → 返回 history_id + audio 列表） |
| `line_tts` | 单行合成（快任务，也可用 sync 直调，视耗时取舍） |
| `script_generate` | `books.generate_chapter_script` 的**同步版**（把 `asyncio.create_task` 改为 await 完成，捕获全部进度 → 状态上报） |
| `ebook_import` | `books.upload_ebook`（文件路径参数化，从 CosyAgent 上传暂缓——首期支持 CosyStudio 本地路径） |
| `asr` | `asr.transcribe_audio`（文件路径参数化） |
| `chat_complete` | 把 `text_chat.chat_stream` 的 SSE **聚合成完整文本**（await 流完）→ 返回全文（供 Agent 调用 CosyStudio 角色对话后取整段结果） |

- 全部在 `asyncio.to_thread` / `asyncio.create_task` 中运行，避免阻塞事件循环；失败捕获 → `status=failed + error`。

### 4.4 安全

- `X-Capability-Call-Token` 校验：adapter 读环境变量/配置，与 CosyAgent 注册时下发的 `callToken` 一致；开启校验则未带/错 token 拒绝（403）。
- Agent 可操作面收敛：**只暴露 adapter 的 4 个端点 + A 级只读查询**；`system/shutdown`、`models/*`、`clear-*` 等管理面**不注册**。

---

## 5. 接入能力清单（首批）

### 5.1 A 级：同步直调（第 1 批，随 adapter 一起注册）

| 能力全名 | 端点 | 说明 |
| --- | --- | --- |
| `cosy_studio_list_scripts` | `GET /api/books/scripts` | 剧本列表 |
| `cosy_studio_script_detail` | `GET /api/books/scripts/{id}` | 剧本详情（章节/角色） |
| `cosy_studio_get_lines` | `GET /api/books/scripts/lines` | 台词行（供 Agent 审阅内容） |
| `cosy_studio_gen_outline` | `GET /webnovel-outline` | 大纲生成（同步 LLM，秒级） |
| `cosy_studio_gen_character_cards` | `GET /webnovel-character-cards` | 角色卡 |

### 5.2 B 级：submit-poll（第 1 批）

| 能力全名 | 提交端点 | 语义 |
| --- | --- | --- |
| `cosy_studio_tts_chapter` | `POST /agent-bridge/tasks {type:"chapter_tts", script_id, chapter_index}` | 整章合成 |
| `cosy_studio_generate_chapter` | `POST /agent-bridge/tasks {type:"script_generate", script_id, chapter_index}` | 章节台词生成 |
| `cosy_studio_import_ebook` | `POST /agent-bridge/tasks {type:"ebook_import", path}` | 导入电子书 |
| `cosy_studio_asr` | `POST /agent-bridge/tasks {type:"asr", path}` | 音频转写 |
| `cosy_studio_chat_agent` | `POST /agent-bridge/tasks {type:"chat_complete", agent_id, text}` | 与 CosyStudio 角色对话取整段结果 |

---

## 6. Agent 创作闭环编排（CosyAgent 侧）

### 6.1 创作工作流（状态机，复用 CosyAgent `agent.task` 状态机）

```
draft_book → create_script → generate_chapter(逐章) → configure_voice(角色音色)
           → tts_chapter(合成) → export/交付
```

- 每个阶段 = 一个（或一组）能力调用；阶段状态挂到当前会话的任务状态机上（`PENDING → RUNNING → DONE/FAILED`，断点续跑语义已有）。
- Agent 通过 ReAct 自主规划阶段顺序；进度与中间产物写入会话记忆（Redis 多层记忆），支持"接着上次继续"。

### 6.2 提示词/工具组织（去噪原则）

- 工具描述用创作语义一句话：`cosy_studio_tts_chapter` → "将指定剧本章节合成为有声书音频（长任务，约 1-5 分钟）"——模型据此知道选它并等待轮询。
- 不把 adapter 内部字段暴露给模型；模型只见能力名、参数、结果摘要。

---

## 7. 容错 / 超时 / 观测

- **候选链降级**：submit-poll 全程包在 `ResilienceTarget.TOOL` 内；轮询期间网络抖动自动重试下一候选（同能力多实例场景）。
- **超时**：submit 5s / 单次 poll 5s / 总轮询 `pollTimeoutMs`（10min 默认）；超时 → 状态为"超时但任务可能仍在跑"，返回给 Agent 提示重查（不做盲目重提交，避免重复合成——CosyStudio 侧幂等：同 task 重复 submit 返回同一 task_id）。
- **幂等**：adapter 按 `type+关键参数 hash` 去重（可选开关），防 Agent 重试导致重复合成。
- **观测**：adapter 打 `utils.logger` 日志；CosyAgent 侧轮询进度可走 SSE 的 `toolResult`（带上 `progress`）。

---

## 8. 实施步骤

- **A. CosyStudio adapter**（FastAPI 新增 `backend/api/agent_bridge.py` + 路由注册到 `api/__init__.py`）：
  1. 任务模型 + 内存存储 + `POST/GET tasks`、`GET result`、`GET healthz`
  2. 壳层逐个接：`chapter_tts` → `script_generate` → `chat_complete` → `ebook_import` → `asr`
  3. 令牌校验（配置开关）+ 幂等去重
- **B. CosyAgent 协议扩展**：
  1. `Capability` 加 `endpointMode/statusPath/resultPath/pollIntervalMs/pollTimeoutMs`
  2. `CapabilityProxyTool` 加 `submit-poll` 分支（PollingExecutor：提交→轮询→取结果，套候选链容错）
  3. 注册 API 兼容（缺省 sync，老注册体不变）；目录 API 展示 endpointMode
- **C. 注册与冒烟**：
  1. 启动 CosyStudio（现有启动方式）；`GET /agent-bridge/healthz` 探活
  2. CosyAgent 以 `COSY_AGENT_CAPABILITY_ENABLED=true` 注册 5+5 个能力（CP 模式）
  3. 冒烟：`chat` 问"列出所有剧本"→ 直调 sync；"把第 3 章合成音频"→ submit-poll 轮询到结果
- **D. 客户端联动**：
  1. 会话模型选择器旁可加"能力"入口（只读目录 `GET /api/agent/capabilities`）——本期可选
  2. Agent 回答中音频结果以 markdown 链接/卡片呈现（SSE `answer` 已支持富文本）

---

## 9. 验证清单

- [ ] `GET /agent-bridge/healthz` 返回 UP；CosyAgent 目录中 10 个能力 status=UP
- [ ] sync 能力：`cosy_studio_list_scripts` 一次调用返回剧本列表
- [ ] submit-poll：`cosy_studio_tts_chapter` 提交 → 轮询 → `success` → result 含 history_id
- [ ] 失败路径：CosyStudio 停服 → 能力候选链报 CAPABILITY_ALL_FAILED（不挂 Agent）
- [ ] 幂等：同参数重复提交返回同一 task_id
- [ ] 安全：开令牌校验后未带 token 注册/调用被拒
- [ ] Agent 端到端：一条自然语言指令完成"选题材→建书→剧本→生成章节→整章合成"闭环
