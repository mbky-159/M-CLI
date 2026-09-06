# AGENTS Reference: Detailed Feature Behavior

This document contains detailed feature behavior descriptions, configuration reading orders, and implementation notes that were previously in `AGENTS.md`. Consult this when working on specific modules.

For the primary entry point, see `/AGENTS.md`.

---

## Configuration Reading Orders

### API Key

1. `~/.paicli/config.json` 中对应 provider 的 `apiKey`
2. 环境变量：`GLM_API_KEY` / `DEEPSEEK_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY` / `FREELLMAPI_API_KEY` / `XFYUN_MAAS_API_KEY` / `AGNES_API_KEY`（Kimi 兼容 `MOONSHOT_API_KEY`，讯飞 MaaS 兼容 `XFYUN_API_KEY`）
3. 仓库当前目录下的 `.env`
4. 用户主目录下的 `.env`

### Persistence Locations

| 数据 | 默认路径 | 覆盖方式 |
|------|----------|----------|
| 长期记忆 | `~/.paicli/memory/long_term_memory.json` | `-Dpaicli.memory.dir` |
| 项目级记忆 | `PAI.md` / `.paicli/PAI.md` / `PAI.local.md` | 用户级稳定偏好：`~/.paicli/PAI.md` |
| 原始会话账本 | `~/.paicli/history/raw/session-*.jsonl` | 每个进程会话自动生成 |
| RAG 索引 | `~/.paicli/rag/codebase.db` | `-Dpaicli.rag.dir` |
| 审计日志 | `~/.paicli/audit/audit-YYYY-MM-DD.jsonl` | `PAICLI_AUDIT_DIR` / `-Dpaicli.audit.dir` |
| Side-Git 快照 | `~/.paicli/snapshots/<project_hash>/<worktree_hash>/.git` | `PAICLI_SNAPSHOT_DIR` / `-Dpaicli.snapshot.dir` |
| 后台任务 | `~/.paicli/tasks/tasks.db` | — |
| Better Harness 报告 | `<project>/.paicli/better-harness/<run-id>/` | `/better-harness --inline` 禁止写文件 |

### Snapshot Config

系统属性 > 环境变量 > 默认值：`paicli.snapshot.enabled`(true) / `paicli.snapshot.max`(50) / `paicli.snapshot.excludes`(.git,.paicli/snapshots,target,node_modules,dist,.idea,*.class,*.jar) / `paicli.snapshot.dir`(~/.paicli/snapshots)

### Embedding Config

环境变量 > 系统属性 > 默认值：`EMBEDDING_PROVIDER`(ollama) / `EMBEDDING_MODEL`(nomic-embed-text:latest) / `EMBEDDING_BASE_URL`(http://localhost:11434)

### Log Config

系统属性 > 环境变量/.env > 默认值：`PAICLI_LOG_DIR`(~/.paicli/logs) / `PAICLI_LOG_LEVEL`(INFO) / `PAICLI_LOG_MAX_HISTORY`(7) / `PAICLI_LOG_MAX_FILE_SIZE`(10MB) / `PAICLI_LOG_TOTAL_SIZE_CAP`(100MB)

### Agent Loop Budget Config

ReAct、Plan 单任务与 SubAgent 共用 `AgentBudget`。系统属性 > 默认值：`paicli.react.token.budget`(Integer.MAX_VALUE) / `paicli.react.stagnation.window`(3) / `paicli.react.hard.max.iterations`(不限制)。

设计取舍：交互式 Agent 默认由模型在不再调用工具时自然结束，不设置固定 50 轮天花板；长上下文模型也不再以 80% x window 作为执行硬限。默认安全阀是 stagnation 检测（连续 3 轮相同工具调用）、上下文压缩、取消和 LLM 超时。CI、Runtime API、微信无人值守或严格成本控制场景可用 `-Dpaicli.react.hard.max.iterations=N` 显式设置正整数上限，也可用 `-Dpaicli.react.token.budget=N` 设置 Token 上限。任一预算命中后，执行路径都会关闭工具并额外进行一次最佳努力收尾，以“部分完成”交付已有结果；收尾调用不会重新进入 Agent 循环。Token 显示行 `📊 Token: 已用 X / Y` 的 Y 是软提示，不代表强制限制。

### LLM HTTP Timeout / Retry Config

系统属性 > 默认值：`paicli.llm.connect.timeout.seconds`(60) / `paicli.llm.read.timeout.seconds`(300) / `paicli.llm.write.timeout.seconds`(60) / `paicli.llm.call.timeout.seconds`(600)

SSE 流式下 readTimeout 是两次 read 间最大间隔，GLM-5.1 生成大段 reasoning 时可能长时间静默，所以放宽到 300 秒。
统一重试默认值：`paicli.llm.retry.max-attempts`(3，含首次请求) / `paicli.llm.retry.base-delay.millis`(500) / `paicli.llm.retry.max-delay.millis`(30000)。仅重试 `408`、`429`、`500`、`502`、`503`、`504` 和瞬时连接 / 读取故障，使用指数退避 + jitter，并在上限内尊重 `Retry-After`；`400`、`401` 等确定性错误不重试。SSE 必须看到 `[DONE]` 或非空 `finish_reason` 才视为完整；没有流式消费者时可丢弃半截结果重发，真实消费者已收到 reasoning/content 后禁止自动重放，避免终端出现重复内容。
DeepSeek 流式调用默认使用 HTTP/1.1，避免部分 HTTP/2 网关在长 SSE 响应中重置 stream，表现为 `stream was reset: INTERNAL_ERROR`。
DeepSeek 当前不发送图片输入：`supportsImageInput()` 返回 false，含图片的 `ContentPart` 会在 OpenAI-compatible 请求序列化时替换成文本提示，避免不支持多模态的 DeepSeek API 收到 `image_url` block。

### Web Search Provider Config

1. `SEARCH_PROVIDER` 显式指定 `zhipu` / `serpapi` / `searxng`
2. 未指定时按 Key 自动判断：`GLM_API_KEY` → zhipu / `SERPAPI_KEY` → serpapi / `SEARXNG_URL` → searxng
3. 都没有 → zhipu 占位

各 provider：zhipu(`GLM_API_KEY` + 可选 `ZHIPU_SEARCH_ENGINE`) / serpapi(`SERPAPI_KEY`) / searxng(`SEARXNG_URL`)

### Web Fetch Security (NetworkPolicy)

scheme 白名单(http/https) / 主机黑名单(localhost/loopback/link-local/site-local) / 响应体上限 5MB / 超时 30s / 限流 30次/60s

### MCP Config

1. 用户级：`~/.paicli/mcp.json`
2. 项目级：`.paicli/mcp.json`
3. 按 server 名 merge，项目级覆盖用户级

格式兼容 Claude Code：`command` + `args` = stdio，`url` + `headers` = Streamable HTTP。内置变量：`${PROJECT_DIR}`、`${HOME}`；其他 `${VAR}` 从系统环境变量、系统属性、项目 `.env`、用户 `~/.env` 读取。
检测到 `STEP_API_KEY` 时自动内置 `step_search` 远程 MCP（显式同名配置优先），用于 Step 3.7 Flash 的 `web_search` / `web_fetch` 优先代理。

---

## Detailed Feature Behavior

### ReAct Mode

- 主入口：`Agent.java`
- 退出条件由 LLM 自决（不返回 tool_calls 即结束）
- `AgentBudget` 默认不限制 ReAct、Plan 单任务和 SubAgent 的迭代轮数；显式 token / 轮数预算或连续 3 轮相同调用命中后，禁用工具并做一次部分结果收尾
- 流式输出 reasoning_content + content；inline ReAct 用固定高度 live thinking 区动态预览 reasoning，同一次输入只把完整 reasoning 引用块落到 transcript 一次；live 区只允许清理自己占用的行，避免覆盖旧输出
- inline 流式回答用低调 `▪` 标记起始，不再输出强标题；plain / 非流式兜底仍可使用传统 reasoning + answer 文本
- `TerminalMarkdownRenderer` 渲染 Markdown 表格时按终端列宽分配列宽，长内容在单元格内部换行；CJK 字符按显示宽度计算，避免表格行被终端自动折断后错位

### Long Context Engineering

- `ContextProfile` 按模型 window 连续计算预算和自动压缩阈值，不再用 short/balanced/long 控制压缩行为
- GLM-5.1: 200k / DeepSeek V4: 1M / Agnes: 1M / StepFun: 256k / Kimi K2.6: 256k / FreeLLMAPI: 128k
- 大窗口模型仍启用自动摘要；window ≥ 32k 时可注入 MCP resource 索引，精确代码定位仍优先实时 glob/grep/read
- prompt caching：能力声明 + cached usage 解析
- 自动压缩阈值按 Claude Code 风格预留空间：`maxContextWindow - min(20k, window/4) - min(13k, window/8)`；200k 窗口约 167k 触发，1M 窗口约 967k 触发，小窗口会按比例缩小预留。

### Memory System

- 短期上下文只有各 Agent 实际发送的 conversationHistory；`MemoryManager` 只管理长期记忆检索和 token 统计，不再复制用户、助手与工具消息。
- `AutoCompactionManager` 统一协调两条自动压缩路径：
  1. `PAICLI_SESSION_MEMORY_COMPACTION_ENABLED=true` 时，`SessionMemoryCompactor` 在阈值前异步维护每份 history 独立的增量会话摘要，到阈值后优先使用摘要并保留最近原始消息。
  2. 摘要未就绪、边界失效、压缩后仍超阈值或功能关闭时，回退 `ConversationHistoryCompactor` 完整摘要。
- 两条路径都在 user message 边界切割，直接原地重建真实 conversationHistory；Plan 并行任务和 Team worker 的预计算状态互相隔离。
- ReAct、Plan、SubAgent 都接入同一个协调器；`/compact` 始终走稳定的完整摘要路径并保留最近 1 个 user 轮次。
- `ConversationLedger` 与可变 conversationHistory 解耦：默认 CLI 的 ReAct、Plan、Team（含 planner / worker / reviewer）共享一个 append-only JSONL。每行带 schemaVersion / sessionId / sequence / timestamp / event / mode / actor / source，并保留完整 `LlmClient.Message`；最终响应的 reasoning 也只在账本中完整保留，不改变 provider 的发送视图语义
- `/clear`、历史图片 payload 裁剪和 conversationHistory 压缩只能追加 boundary event，不能覆盖或删除账本旧行。原始工具参数、工具结果和图片 payload 可能敏感；POSIX 下 `history/raw` 为 0700、账本文件为 0600
- 长期记忆只通过 `/save` 或用户明确要求保存
- 长期记忆只保存跨会话稳定事实，不保存临时指令；默认项目级作用域，跨项目通用偏好才用 global
- 长期记忆去重以 `type + scope + project` 为边界，内容使用确定性的 Unicode/格式规范化和保守语法助词近似匹配；不同数字、代码符号或实质内容不会自动覆盖，当前不做事实冲突消解
- 长期记忆管理命令：`/memory list`、`/memory search <关键词>`、`/memory delete <id>`、`/memory clear`
- `PAI.md` 不是 `/save` 长期记忆：它是启动时注入 system prompt 的项目指令文件，适合团队共享、长期稳定、可进 git 的规则
- 加载顺序：`~/.paicli/PAI.md` → `PAI.md` → `.paicli/PAI.md` → `PAI.local.md` → `.paicli/PAI.local.md`
- `PAI.md` 中独占一行的 `@relative/path.md` 会被展开；导入路径必须留在用户配置目录或项目根内，总注入内容按预算截断
- `/init` 生成精简 `PAI.md`，只写 commands / project positioning / architecture / pitfalls / don'ts；已有文件默认不覆盖，`/init --force` 重写

### Multi-Agent

- 三角色：Planner / Worker(默认 2 个) / Reviewer
- 流程：规划 → 按依赖分配 Worker → Reviewer 审查 → 未通过重试(最多 2 次)
- SubAgent IOException 返回 ERROR 类型
- 所有子代理共享 ToolRegistry 和 MemoryManager

### HITL System

- 危险工具：write_file(中) / execute_command(高) / create_project(中) / revert_turn(高)
- 审批选项：y(批准) / a(全部放行) / n(拒绝) / s(跳过) / m(修改参数)
- fail-safe：连续 5 次无效输入判为 REJECTED
- 并发：requestApproval 整体 synchronized

### HITL Enhancement (Policy Layer)

- `PathGuard`：路径限定在项目根内（绝对路径外逃 / `..` 穿越 / 符号链接逃逸）
- `CommandGuard`：fast-fail 黑名单（sudo/rm -rf/mkfs/dd/fork bomb/curl|sh 等）
- `ResourceLimit`：write_file 5MB / execute_command 60s + 8KB 输出
- `AuditLog`：JSONL 字段 timestamp/tool/args/outcome/reason/approver/durationMs
- 拦截顺序：HitlToolRegistry → ToolRegistry → 策略层。用户无法批准策略拒绝的请求

### Parallel Tool Execution

- `executeTools()` 固定线程池并行，默认最多 4 个并发
- 返回结果保持原始顺序
- Agent/PlanExecuteAgent/SubAgent 三条路径都走 executeTools()

### Web Capabilities

- `web_search`：SearchProvider 接口，返回 SearchResult 列表
- `web_fetch`：NetworkPolicy → WebFetcher → HtmlExtractor，SPA/防爬墙返回空正文 + 边界提示
- Prompt 不包含 Freshness Policy，也不对“最新/当前/今天”等关键词做自动 `web_search` 预检。模型只能在顶层用户目标明确时主动选择联网工具；明确“不要联网”始终优先。
- 顶层输入只是裸标题、主题或摘录，且无动作、问题或目标时，当前轮只做澄清，不调用任何工具。模型不得根据标题、记忆或自己的 reasoning 猜测 URL。
- 用户明确要求查找但没有 URL 时，先 `web_search`；`web_fetch` 或 Chrome / MCP 导航 URL 只能来自用户实际提交的顶层原文（不能是 `@path` / MCP resource 展开正文），或同一执行分支由搜索 provider 返回的结构化 `discoveredUrls`。搜索正文、snippet、query 回显、错误提示、`web_fetch` 正文、浏览器导航/快照/网络列表、普通本地工具结果、assistant reasoning、回复文本和 tool arguments 都不能建立 URL provenance；当前 StepSearch MCP 的非结构化文本不会生成凭据。
- `TurnToolPolicy` 是运行时确定性边界：ReAct / Plan / Team 都必须单独传入用户提交原文与展开后的执行内容，不能让 planner / worker 派生的“搜索”子任务自行获得联网授权。Plan 审阅补充会重建策略；Plan 并行任务和 Team worker 使用 fork 后的独立 URL 集合，避免跨分支扩权。只有 DAG 中声明的后继依赖会继承前置分支不可伪造的 `TrustedUrlContext`；任务结果文本不作为授权来源。grounded URL 先只曝光导航，成功导航只建立当前页读取上下文，读取结果不产生新 URL 授权；交互工具必须有顶层原文明确授权。shared Chrome 的真实模式与 PaiCLI-owned 当前页从 `BrowserSession` 跨轮注入策略；非 owned 标签页只在用户明确要求时开放只读，导航/写入/关闭会硬拒绝，导航结果的全量 `# Pages` 会在回灌模型前裁成单页回执。策略在 StepSearch、内置 SearchProvider / WebFetcher 和 Chrome / MCP 路由之前执行，拒绝结果不得用 fallback 绕过。
- StepSearch 优先级：通过 `TurnToolPolicy` 后，当前模型 provider=`step` 且 model 以 `step-3.7-flash` 开头，并且自动/显式 `mcp__step_search__web_search` / `mcp__step_search__web_fetch` 已注册时，内置 `web_search` / `web_fetch` 会先代理到 StepSearch MCP；MCP 未就绪或返回不可用结果时回退原实现。
- 本地“当前项目/当前 README/当前文件/当前代码”属于代码库任务，应选择 `glob_files` / `grep_code` / `read_file`，而不是联网工具。
- JS 渲染 fallback 到 Chrome DevTools MCP

### MCP Protocol

- stdio + Streamable HTTP 双 transport
- 工具注册为 `mcp__{server}__{tool}`
- McpSchemaSanitizer 清洗 inputSchema
- 所有 mcp__ 工具默认走 HITL + AuditLog
- resources 双轨：虚拟工具 + @-mention 输入层
- CLI 首屏默认只等待 MCP 启动 8 秒，慢 server 后台继续初始化并保持 `starting`，用 `/mcp` / `/mcp logs <name>` 追踪
- notifications 路由：tools/list_changed → 工具全量替换，resources 变化 → cache 失效

### Chrome DevTools MCP

- 默认 server：chrome-devtools，`npx -y chrome-devtools-mcp@latest --isolated=true`
- `/browser connect`：切到 --autoConnect 复用登录态 Chrome
- `/browser connect <port>`：旧式 CDP 端口路径
- `/browser disconnect`：切回 isolated
- 敏感页面策略：改写型工具必须单步 HITL，不复用全部放行
- shared 模式 close_page 只允许关闭 PaiCLI 创建的 tab

### Skill System

- 三层加载：jar 内置 < 用户级 ~/.paicli/skills/ < 项目级 .paicli/skills/
- frontmatter：name(必填) / description(必填,<=500) / version / author / tags
- system prompt 索引段注入到三处提示词末尾，上限 20 个 / 4KB
- load_skill 工具把 SKILL.md 正文(5KB 截断)写入 SkillContextBuffer
- buffer 一次性消费，最多 3 个 skill body

### Better Harness

- `/better-harness [quick|normal] [--inline]` 是 PaiCLI 原生命令，不调用 Node sidecar
- `BetterHarnessEvidenceCollector` 先冻结三路证据：当前 ConversationLedger 脱敏元数据、Project Harness、Agent Customize
- 三个 specialist 使用同一 LLM 并行调用，但不暴露任何工具；它们只能分析各自证据 lane
- 进度以 5 个确定性工作单元展示：证据冻结 1 个、三个 specialist 各 1 个、Lead 汇总 1 个；并行结果按完成顺序收集，谁先完成谁先刷新活动面板
- 活动面板展示真实完成数、当前阶段、累计耗时和 ESC 取消提示；plain renderer 降级为逐行进度文本，不得用仅按时间增长的百分比冒充完成度
- 最终报告通过 `TerminalMarkdownRenderer` 按 `Renderer.terminalColumns()` 渲染，标题、强调、列表、表格和代码块不得以 Markdown 源码形式直接刷到终端
- lead 只接收三个 specialist 结果，输出结构化 report + findings；Java 负责写 `report.md`、自包含 `report.html` 和 `findings.json`
- 默认不序列化 user/assistant 正文、reasoning、tool 参数/结果、图片、Memory 正文、用户目录配置或 MCP secret
- 当前 session evidence 只覆盖活动 ledger；旧 ledger 尚未具备稳定 workspace identity 时不得混入

### TUI (v16.1 Renderer Architecture)

- 三个实现：InlineRenderer(默认) / LanternaRenderer / PlainRenderer
- 环境变量：`PAICLI_RENDERER=inline|lanterna|plain`
- `PAICLI_TUI=true`(旧) → lanterna + deprecation 提示
- `PAICLI_NO_STATUSBAR=true`：禁用底部状态栏
- `NO_COLOR=1`：禁用 ANSI 颜色
- 当前开屏 Banner 是无右侧盒线边框的简洁布局，避免 ANSI/CJK 字宽导致竖线错位
- InlineRenderer 复用 JLine 4 的编辑能力，默认提示符是 `* `，右提示显示 `message / @path / @image`
- BottomStatusBar 是 JLine `Status` 托管的底部 dock：由 JLine 负责滚动区域和状态行位置，不再手写 `\n`、`moveUp`、`CLEAR_TO_EOS` 或绝对光标行号；dock 上层展示 YOLO/HITL 与 MCP/Skill 摘要，下层展示 model、phase、ctx、token、cost、elapsed 与 cwd。关键字段可用 JLine `AttributedString` 做克制彩色高亮，但纯文本格式和列宽裁剪仍要稳定。`ctx` 只表示当前仍会带入下一轮请求的上下文估算，`in/out/cache` 表示最近任务调用统计。
- `/clear` 清空当前 ReAct conversationHistory、对应的 Session Memory 预计算状态和待注入 SkillContextBuffer，并重建不含上一轮检索记忆的 system prompt；长期记忆条目保留，后续只会按新查询重新检索注入。
- `/compact` 手动以完整摘要压缩当前 ReAct conversationHistory，压缩期间显示动态 activity 面板，成功后刷新底部 ctx；不会清空长期记忆或待注入 SkillContextBuffer。
- `/export` 导出当前 ReAct conversationHistory 为 Markdown 到 `~/.paicli/exports/session-*.md`；包含完整 system prompt，便于检查 LLM 实际接收前的指令，命令不接受路径参数。
- 普通任务和斜杠命令提交后都会以 `>` 暗色整行块回写原始输入，避免 JLine accept 后清掉编辑行导致结果区看不到刚执行的命令
- InlineRenderer 不使用独立 JLine `Display.update()` 维护 thinking 临时区；真实终端验证发现独立 Display 会在 transcript/status 输出后从错误位置向上清屏。当前实现用固定高度 live 区重写自身行，content/tool 边界先清理 live 区再追加 transcript。
- 交互期输出优先走 `Renderer.stream()`；`Main`、`PlanExecuteAgent`、`Planner`、`AgentOrchestrator` 都可接收同一个 renderer 输出流，避免绕过 inline renderer 直接写 stdout
- `CodeIndex` 通过 `ProgressListener` 上报索引开始 / 文件数量 / 进度 / 完成或失败，`/index` 绑定当前 renderer 输出流；内部异常细节写 logger

### LSP Diagnostics (Phase 17)

- write_file 成功后对 Java 文件做 JavaParser 语法诊断
- 诊断作为合成 user message 注入下一轮 LLM 请求
- `PAICLI_LSP_ENABLED=false` 关闭

### Git Side-History Snapshot (Phase 18)

- side-git 在 ~/.paicli/snapshots/ 维护独立仓库（JGit，不依赖系统 git）
- pre-turn 同步，post-turn 异步
- revert_turn 纳入 HITL/AuditLog，恢复前先创建 pre-restore 快照

### Prompt Layering (Phase 19)

- 组装顺序：base → personality → mode → approval → runtime_context → project_context → skills → context_mgmt → handoff
- runtime_context 每轮注入当前日期和系统时区，供相对日期理解使用
- project_context 顺序：`PAI.md` 项目记忆 → 相关长期记忆 → MCP resources 索引
- 覆盖优先级：jar 内置 < 用户级 ~/.paicli/prompts/ < 项目级 .paicli/prompts/
- 必要校验：base.md 和最终 prompt 必须包含 `## Language`

### Async Tasks + Runtime API (Phase 20)

- DurableTaskManager(SQLite) / CLI: /task, /task list, /task add, /task cancel, /task log
- Runtime API: `serve --http --port 8080`，仅 127.0.0.1，需 API Key
- 端点：POST /v1/threads / POST /v1/threads/{id}/turns / GET /v1/threads/{id}/events

### Image Input (Phase 21)

- ContentPart 支持图片 block（base64 + mimeType）
- ImageProcessor：铺白底/缩放 2000x2000/压缩 5MB
- 输入：`@image:file:///path.png` / `@image:/path.png` / `@image:relative.png`
- GLM-5V-Turbo 通过 `/model glm-5v-turbo` 切换
- Provider 通过 `supportsImageInput()` 声明是否接收图片；不支持时保留文字上下文并省略图片 payload
- 历史 image payload 替换为文本占位，避免旧截图消耗上下文

---

## Core File Descriptions

### Main.java
CLI 入口 / Banner / .env 读取 / 日志初始化 / 模式切换 / JLine raw mode

### Agent.java
ReAct 主循环 / 对话历史 / 工具调用与结果回灌

### ConversationLedger.java
system / user / assistant / tool_call / tool_result 原始消息的 append-only JSONL 账本 / mode-actor-source 归因 / POSIX 权限收紧

### PlanExecuteAgent.java
规划后执行 / 计划审阅 / DAG 任务执行 / 并行批次 / 失败重规划

### AgentOrchestrator.java
Multi-Agent 编排器 / 三角色管理 / 按依赖分配 / 审查重试

### SubAgent.java
可配置角色子代理 / 独立对话历史 / Worker 用工具、Planner/Reviewer 不用

### Planner.java
LLM 生成计划 JSON / 简单任务最小计划 / 重编号 task_1..N / 依赖计算

### ExecutionPlan.java
DAG 拓扑排序 / 可执行任务判定 / 进度可视化

### ToolRegistry.java
11 个核心内置工具 + MCP 动态工具 / executeTools() 并行入口 / ToolInvocation / ToolExecutionResult。代码理解默认路径是 `glob_files` / `grep_code` / `read_file` 现用现查，`grep_code` 优先走 ripgrep 并按 `max_results` / `head_limit` / `max_chars` 渐进返回，`search_code` 保留为 RAG 语义辅助。确定性搜索链路的回归样例见 `docs/code-search-golden-set.md`。

### MCP Package
McpServerManager / McpClient / JsonRpcClient / StdioTransport / StreamableHttpTransport / McpSchemaSanitizer / resources/ / mention/ / notifications/

### TUI Package
TuiBootstrap / LanternaWindow / TuiSessionController / pane/ / hitl/ / history/ / highlight/

### LLM Clients
- GLMClient：glm-5.1，glm-5v 开头切多模态接口
- DeepSeekClient：deepseek-v4-flash，thinking + tool calls 带回 reasoning_content
- StepClient：step-3.5-flash，可通过 STEP_BASE_URL 切通道
- KimiClient：kimi-k2.6，thinking + tool calls 带回 reasoning_content
- FreeLlmApiClient：auto，默认 http://localhost:5173/v1，OpenAI-compatible 本地网关；可用 `/config provider freellmapi ...` 写入配置后 `/model freellmapi` 切换
- XfyunMaaSClient：Qwen3.6-35B-A3B，默认 https://maas-api.cn-huabei-1.xf-yun.com/v2，OpenAI-compatible 讯飞星辰 MaaS；可用 `/config provider xfyun ...` 写入配置后 `/model xfyun` 切换。`model` 必须使用 MaaS 服务管控页展示的 modelId；微调模型可配置 `--lora-id <resourceId>`，作为 HTTP header `lora_id` 发出；该 provider 不发送 PaiCLI 内置 tools。
- AgnesClient：agnes-2.0-flash，默认 https://apihub.agnes-ai.com/v1，OpenAI-compatible Agnes AI，默认 1M context window；可用 `/config provider agnes ...` 写入配置后 `/model agnes` 切换，支持流式输出和 tools。

---

## .env.example Reference

```bash
GLM_API_KEY=your_api_key_here
# GLM_MODEL=glm-5.1
# GLM_MODEL=glm-5v-turbo
# DEEPSEEK_API_KEY=your_deepseek_api_key_here
# DEEPSEEK_MODEL=deepseek-v4-flash
# STEP_API_KEY=your_step_api_key_here
# STEP_MODEL=step-3.5-flash
# STEP_BASE_URL=https://api.stepfun.com/v1
# KIMI_API_KEY=your_kimi_api_key_here
# MOONSHOT_API_KEY=your_moonshot_api_key_here
# KIMI_MODEL=kimi-k2.6
# KIMI_BASE_URL=https://api.moonshot.ai/v1
# FREELLMAPI_API_KEY=your_freellmapi_unified_key_here
# FREELLMAPI_MODEL=auto
# FREELLMAPI_BASE_URL=http://localhost:5173/v1
# AGNES_API_KEY=your_agnes_api_key_here
# AGNES_MODEL=agnes-2.0-flash
# AGNES_BASE_URL=https://apihub.agnes-ai.com/v1
# XFYUN_MAAS_API_KEY=your_xfyun_maas_api_key_here
# XFYUN_MAAS_MODEL=Qwen3.6-35B-A3B
# XFYUN_MAAS_BASE_URL=https://maas-api.cn-huabei-1.xf-yun.com/v2
# XFYUN_MAAS_LORA_ID=0
EMBEDDING_PROVIDER=ollama
EMBEDDING_MODEL=nomic-embed-text:latest
EMBEDDING_BASE_URL=http://localhost:11434
# EMBEDDING_API_KEY=your_api_key_here
# PAICLI_LOG_LEVEL=INFO
# PAICLI_LOG_DIR=/Users/yourname/.paicli/logs
# PAICLI_LOG_MAX_HISTORY=7
# PAICLI_LOG_MAX_FILE_SIZE=10MB
# PAICLI_LOG_TOTAL_SIZE_CAP=100MB
# PAICLI_SNAPSHOT_ENABLED=true
# PAICLI_SNAPSHOT_MAX=50
# PAICLI_SNAPSHOT_EXCLUDES=.git,.paicli/snapshots,target,node_modules,dist,.idea,*.class,*.jar
# PAICLI_SNAPSHOT_DIR=/Users/yourname/.paicli/snapshots
# PAICLI_TUI=true
# NO_TUI=true
```

---

## Test Coverage Summary

测试覆盖偏向：解析、计划结构、RAG 核心、Multi-Agent 编排、HITL 策略、策略层拦截、MCP 协议、资源输入层、长上下文策略与 Skill 加载。

不覆盖：真实 LLM 联调、真实 Embedding API、真实 MCP server 联调、终端完整手工体验。

完整测试类列表：CliCommandParserTest / MainBrowserCommandTest / PlanReviewInputParserTest / MainInputNormalizationTest / ExecutionPlanTest / MemoryEntryTest / SessionMemoryCompactorTest / AutoCompactionManagerTest / ConversationHistoryCompactorTest / LongTermMemoryTest / MemoryRetrieverTest / MemoryManagerTest / ExplicitMemoryHintsTest / ContextProfileTest / PlanExecuteAgentTest / AgentMemoryHintTest / AgentWebSearchDecisionTest / AgentRoleTest / AgentMessageTest / AgentOrchestratorTest / EmbeddingClientTest / SearchResultTest / NetworkPolicyTest / HtmlExtractorTest / WebFetcherTest / SearchProviderFactoryTest / ZhipuSearchProviderTest / VectorStoreTest / CodeChunkerTest / CodeAnalyzerTest / CodeIndexTest / ApprovalPolicyTest / ApprovalResultTest / HitlToolRegistryTest / TerminalHitlHandlerTest / ToolRegistryTest / TurnToolPolicyTest / BrowserSessionTest / BrowserConnectivityCheckTest / SensitivePagePolicyTest / BrowserGuardTest / McpSchemaSanitizerTest / McpConfigLoaderTest / JsonRpcClientTest / McpToolBridgeTest / McpResourceCacheTest / AtMentionParserTest / AtMentionExpanderTest / AtMentionCompleterTest / NotificationRouterTest / PathGuardTest / CommandGuardTest / AuditLogTest / SkillFrontmatterParserTest / SkillRegistryTest / SkillStateStoreTest / SkillBuiltinExtractorTest / SkillContextBufferTest / SkillIndexFormatterTest / LoadSkillToolTest / SkillCommandHandlerTest / BetterHarnessOptionsTest / BetterHarnessEvidenceCollectorTest / BetterHarnessRunnerTest
