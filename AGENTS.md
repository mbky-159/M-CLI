# AGENTS.md

仓库给 Agent / 新线程使用的首读入口。详细行为描述见 `docs/agents-reference.md`。`README.md` 聚焦使用方式，分期建设记录集中在 `docs/project-progress.md`，未来规划仍放在 `ROADMAP.md`。

## 信息优先级

1. 代码实际行为 > 2. `AGENTS.md` > 3. `PAI.md` > 4. `README.md` > 5. `ROADMAP.md` > 6. `CLAUDE.md`

`ROADMAP.md` 代表演进方向，不代表已交付。

## 项目快照

- 项目名：`M-CLI`
- 产品品牌与 JAR 名称使用 M-CLI / m-cli；兼容标识 `com.paicli`、`PAICLI_*`、`paicli.*`、`.paicli`、`PAI.md`、`X-PaiCLI-API-Key` 保留，避免破坏已有配置与数据。
- 定位：面向商业使用的 Java Agent CLI 产品，对标 Claude Code
- 已交付 23 期（ReAct → Plan+DAG → Memory → RAG → Multi-Agent → HITL → 并行工具 → 多模型 → 联网 → MCP 核心 → MCP 高级 → 长上下文 → Chrome DevTools → CDP 会话复用 → Skill → TUI → LSP 诊断 → Side-Git 快照 → Prompt 分层 → Runtime API → 图片输入 → 微信 iLink 通道文本 MVP）
- `PAI.md` 是 M-CLI 的项目级记忆文件：启动时自动注入 system prompt，适合团队共享的长期稳定规则；个人/会变化的经验继续用 `/save` 长期记忆。
- 内置 `/better-harness [quick|normal] [--inline]`：并行审查 Session / Project Harness / Agent Customize 三路脱敏证据，默认输出到 `.paicli/better-harness/<run-id>/`。
- `eval/` 提供 LLM-as-a-Judge 核心库：Rubric 逐维评分由 Java 计算加权总分，Pairwise 通过交换 A/B 位置复评来暴露位置偏见；当前没有 `/eval` 命令、批量 Runner 或人工一致率数据。
- 下一步：OAuth / sampling / recovery 作为后续 MCP 增强
- Banner 版本：`v16.1.0`，Maven 产物：`m-cli-1.0-SNAPSHOT.jar`（两者不一致是正常状态）

## 运行前提

- Java 17+ / Maven
- 可选：`ripgrep`（`grep_code` 会优先使用；未安装时自动回退 Java 扫描）
- 至少一个 API Key：`GLM_API_KEY` / `DEEPSEEK_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY` / `FREELLMAPI_API_KEY` / `XFYUN_MAAS_API_KEY` / `AGNES_API_KEY`

## 常用命令

```bash
cp .env.example .env
mvn clean package        # 默认跳过测试，优先产出可手工验收 jar
java -jar target/m-cli-1.0-SNAPSHOT.jar
java -jar target/m-cli-1.0-SNAPSHOT.jar wechat setup   # 主动绑定微信 iLink 通道，默认不开启
java -jar target/m-cli-1.0-SNAPSHOT.jar wechat start   # 前台启动微信通道
/wechat                   # 交互式 CLI 内扫码绑定并后台启动微信通道
mvn test -Pquick          # 常规回归
mvn test -Pphase16-smoke  # TUI 相关
mvn test -Dtest=XxxTest -DskipTests=false   # 针对性
mvn test -DskipTests=false                  # 全量回归
/init                    # 生成精简项目级记忆 PAI.md；已有文件不覆盖，/init --force 可重写
/export                  # 导出当前 ReAct 会话为 Markdown，包含完整 system prompt
/better-harness          # 审查当前项目的 AI 编码工作流并生成持久化报告
```

## 架构概览

三条主执行路径，共享 ToolRegistry / MemoryManager / SnapshotService：

| 路径 | 入口 | 触发 |
|------|------|------|
| ReAct | `Agent.java` | 默认模式 |
| Plan-and-Execute | `PlanExecuteAgent.java` | `/plan` |
| Multi-Agent | `AgentOrchestrator.java` | `/team` |

核心内置工具 11 个：`read_file` / `write_file` / `list_dir` / `glob_files` / `grep_code` / `execute_command` / `create_project` / `search_code` / `web_search` / `web_fetch` / `revert_turn`

代码库理解默认走 Claude Code 式实时探索：`glob_files` 找候选文件、`grep_code` 精确定位符号或字符串、`read_file` 按需读取具体行段。`grep_code` 优先使用本机 `ripgrep`，不可用时回退到 Java 扫描；结果受 `max_results` / `head_limit` / `max_chars` 预算约束，返回 `partial: true` 或 `suggested_reads` 时应继续缩小搜索范围或按建议读取行段。`search_code` 是 RAG 语义辅助，适合模糊自然语言、关键词不明确、常规搜索无果、巨型/跨知识检索场景，不作为精确代码定位的首选。

MCP 动态工具：`mcp__{server}__{tool}`（+ resources 虚拟工具）

MCP 配置会合并用户级 `~/.paicli/mcp.json` 与项目级 `.paicli/mcp.json`；`${VAR}` 支持系统环境变量、系统属性、项目 `.env`、用户 `~/.env`。检测到 `STEP_API_KEY` 时会自动内置 `step_search` 远程 MCP（显式同名配置优先）。

DeepSeek V4 / Kimi thinking 模式下，assistant tool-call 消息的 `reasoning_content` 必须随下一轮请求历史带回；其他 provider 默认只把 reasoning 写日志 / 展示。
DeepSeek SSE 调用默认强制 HTTP/1.1，避免部分网络/网关下 HTTP/2 长流被远端重置成 `stream was reset: INTERNAL_ERROR`。
DeepSeek 当前按文本 provider 处理：`supportsImageInput()` 返回 false，历史或工具回灌里的图片 `ContentPart` 会在请求序列化时替换为文本提示，不能把 `image_url` block 发给 DeepSeek API。
OpenAI-compatible LLM 请求统一由 `LlmRetryPolicy` 做有限重试：默认总尝试 3 次，仅覆盖 `408` / `429` / 可恢复 `5xx` 和瞬时连接 / 读取故障，指数退避 + jitter，并在等待上限内读取 `Retry-After`；`400` / `401` 等确定性错误直接失败。SSE 未出现 `[DONE]` 或非空 `finish_reason` 视为中断；尚未向 `StreamListener` 交付内容时可重发，已交付 reasoning/content 后不得自动重放，避免重复输出。相关系统属性见 `.env.example`。

讯飞星辰 MaaS provider 名为 `xfyun`，默认 Base URL 为 `https://maas-api.cn-huabei-1.xf-yun.com/v2`。`model` 必须使用服务管控页展示的 `modelId`；公开模型名 / Hugging Face 仓库名不一定可直接调用。微调模型用 `/config provider xfyun --lora-id <resourceId>` 配置服务卡片上的 resourceId，M-CLI 会作为 HTTP header `lora_id` 发出。`xfyun` 当前按 MaaS 文档走纯对话请求，不向上游发送 M-CLI 内置工具列表。
Agnes provider 名为 `agnes`，默认 Base URL 为 `https://apihub.agnes-ai.com/v1`，默认模型 `agnes-2.0-flash`，走 OpenAI-compatible Chat Completions，默认 1M context window，支持流式输出和 tools。

## 仓库结构

```
src/main/java/com/paicli/
├── agent/       Agent.java, PlanExecuteAgent.java, SubAgent.java, AgentOrchestrator.java
├── cli/         Main.java, CliCommandParser.java, PlanReviewInputParser.java
├── browser/     BrowserSession, BrowserGuard, SensitivePagePolicy
├── llm/         GLMClient, DeepSeekClient, StepClient, KimiClient, FreeLlmApiClient, AgnesClient
├── context/     ContextProfile, ContextMode, TokenUsageFormatter
├── history/     ConversationLedger（原始会话 append-only JSONL 账本）
├── memory/      MemoryManager, ConversationHistoryCompactor, LongTermMemory
├── plan/        Planner, ExecutionPlan, Task
├── rag/         CodeIndex, CodeRetriever, VectorStore, CodeChunker
├── lsp/         LspManager, LspDiagnosticFormatter
├── prompt/      PromptAssembler, PromptContext, PromptRepository
├── image/       ImageReferenceParser
├── runtime/     api/ (RuntimeApiServer) + task/ (DurableTaskManager)
├── snapshot/    SideGitManager, SnapshotService
├── tool/        ToolRegistry
├── wechat/      iLink client, account store, message loop, non-interactive policy
├── mcp/         McpClient, McpServerManager, transport/, resources/, mention/
├── hitl/        HitlToolRegistry, ApprovalPolicy, TerminalHitlHandler
├── web/         SearchProvider, WebFetcher, HtmlExtractor, NetworkPolicy
├── policy/      PathGuard, CommandGuard, AuditLog
├── skill/       SkillRegistry, SkillContextBuffer, SkillIndexFormatter
├── harness/     BetterHarnessRunner, BetterHarnessEvidenceCollector
└── render/      Renderer, InlineRenderer, PlainRenderer, RendererFactory
```

启动与 inline 渲染当前约定：

- 开屏 Banner 使用无右边框的简洁布局，避免 CJK/ANSI 字宽导致右侧竖线错位；Phase 22 后默认是 M 主题彩色 logo + Qoder 风格首屏，只展示模型、MCP、Skill、ReAct 状态和三条 getting-started tips，不再把 MCP server 明细刷成启动日志。
- inline 模式使用 JLine 4 的 LineReader 编辑能力，默认提示符是 `* `，右提示显示 `message / @path / @image`。
- 默认 CLI 启动路径应先 `Renderer.start()` 并初始化底部 dock；inline 首屏不要在 `readLine` 前裸写 stdout，而是通过 `InlineRenderer.installStartupScreen(...)` 挂到 `LineReader.CALLBACK_INIT`，首次进入输入时用 `printAbove` 一次性显示完整 Banner + tips，避免 logo 被 LineReader 首次重绘滚出可视区域。
- `BottomStatusBar` 现在是 JLine `Status` 托管的底部 dock：由 JLine 维护滚动区域和状态行位置，不再手写 `\n` / `moveUp` / `CLEAR_TO_EOS` 清屏。输入期会把 LineReader 光标定位到 dock 上方一行，让 `*` 输入行和 Status 同处底部区域；dock 保留两类信息：上层模式 + MCP/Skill 摘要，下层 Auto Model / model / phase / ctx 百分比与 token / cost / elapsed / cwd。关键字段可用克制的 JLine `AttributedString` 彩色样式突出，但纯文本格式和宽度裁剪逻辑要保持稳定。`ctx` 表示当前仍会带入下一轮请求的上下文估算；`in/out/cache` 表示最近任务的 LLM 调用统计，二者不要混用。
- 普通任务和斜杠命令提交后，`Main` 会把本轮原始输入以暗色整行块写回 transcript：输入态左提示仍是 `* `，提交回显左提示改为 `>`；单行输入只占一行，不额外追加空白行。普通任务随后再展开 MCP resource / 本地 `@path` 并进入 Agent；不要只依赖 JLine 提交行残留，否则 activity 重绘或 dock 刷新可能让用户输入从可见历史里消失。`/clear` 清空 ReAct conversationHistory、对应的 Session Memory 预计算状态和待注入 Skill buffer，并重建不含上一轮检索记忆的 system prompt；长期记忆保留。`/compact` 会手动走完整摘要压缩当前 ReAct conversationHistory，不等待上下文阈值触发，保留最近 1 个 user 轮次和 tool_call/tool_result 边界。
- ReAct LLM 调用期间，inline renderer 使用固定高度 live thinking 区动态显示 `Thinking...` 和灰色竖线 reasoning 预览；该区域只能清理自己刚打印的几行，不能用独立 JLine `Display.update()` / `CLEAR_TO_EOS` 向上覆盖 transcript。content 或 tool call 开始前先清掉 live 区，再把完整 reasoning 引用块落到正文区，正文回答用低调标记起始，不再刷强标题。
- 交互期输出应优先走 `Renderer.stream()`；`Main`、`PlanExecuteAgent`、`Planner`、`AgentOrchestrator` 都支持把输出流接到 inline renderer，避免直接争抢 stdout。`CodeIndex` 的索引进度通过 `ProgressListener` 注入，`/index` 应绑定到当前 renderer 输出流。
- Phase 22 开始，`InlineRenderer` 可绑定当前 `LineReader`；当 `LineReader.isReading()` 为 true 时，`Renderer.stream()` 的完整行输出优先通过 `LineReader#printAbove` 显示在输入行上方，未绑定 / 非读取态 / 测试路径回退到原 `PrintStream`。
- Markdown 表格渲染要按当前终端列宽分配列宽；长内容在单元格内部换行，不能依赖终端自动折行把整行表格打散。
- ReAct 正常结束后不再把 `📊 Token: ...` 打进正文区；token/cost/elapsed 会保留在底部强状态行，phase 回到 `idle`。
- 默认 CLI 启动路径应尽早建立 `Terminal -> LineReader -> Renderer`，启动 Banner、模型加载、MCP 启动、Skill summary、ReAct 提示和退出提示都应走 `Renderer.stream()`；除 fatal bootstrap / runtime API / legacy TUI 降级外，不要在交互主路径新增裸 `System.out.println`。
- 启动期 MCP 不得阻塞首屏：CLI 默认最多等待 8 秒（`PAICLI_MCP_STARTUP_WAIT_SECONDS` / `-Dpaicli.mcp.startup.wait.seconds` 可调），超时后保留未完成 server 为 `STARTING` 并后台继续初始化；`/mcp` 查看最新状态。
- `LineReader` 使用 `PaiCliHighlighter` 做输入实时高亮：slash 命令、`@` 引用、`@image:`、`@clipboard`、敏感词和明显危险 shell 片段会在编辑阶段被标记；不要把这类视觉提示混入最终提交文本。
- `LineReader` 使用 `PaiCliCompleter` 做上下文补全：`/model` provider、`/mcp` 子命令与 server、`/skill` 子命令与 skill name、`/task` / `/browser` / `/snapshot` 子命令、`@image:` 本地路径、本地 `@path` 和 MCP resource `@server:uri` 引用都应从同一个 completer 出口维护。
- 普通用户输入进入 Agent 前会先展开 MCP resource mention，再由 `LocalPathMentionExpander` 展开本地 `@path`：文件会内联为 `<file>` 块，目录会内联为 `<directory>` 列表；绝对路径或符号链接逃逸项目根时保持原文不展开。
- `LineReader` 使用 `PaiCliHistory` 持久化输入历史到 `~/.paicli/history/input.history`；如果 `paicli.history.file` / `PAICLI_HISTORY_FILE` 指向目录，也会自动使用该目录下的 `input.history`，避免把目录当文件读；默认忽略空白、重复、明显密钥/Bearer、base64 图片和超长输入，用户可用 `/history clear` 清空本机输入历史。
- 启动期会加载 `~/.paicli/PAI.md`、项目根 `PAI.md`、项目根 `.paicli/PAI.md`、`PAI.local.md`、`.paicli/PAI.local.md`，按此顺序注入 Project Context；`@relative/path.md` 可导入项目根内文件，总注入内容有字符预算，避免项目记忆变成 token 噪音。
- `/init` 会根据当前项目生成短 `PAI.md`，只放 commands / project positioning / architecture / pitfalls / don'ts；默认不覆盖已有文件。
- `/export` 导出当前 ReAct `conversationHistory` 为 Markdown 到 `~/.paicli/exports/session-*.md`；只支持无参数命令，包含完整 system prompt，便于检查 LLM 实际接收前的指令。
- `/better-harness` 走 M-CLI 原生四阶段审查：确定性脱敏证据快照 → 三路无工具 specialist 并行分析 → lead 汇总 → Java 确定性渲染。终端必须实时显示 5 个确定性工作单元、先完成先反馈的三路审查进度、累计耗时和 ESC 取消提示，不能只打印启动文案后静默等待；最终 Markdown 必须经过 `TerminalMarkdownRenderer` 按当前终端宽度渲染，不能直接输出 `#` / `**` 等源码标记。默认只读取当前 ledger 元数据和项目内公开工程资产，不读取消息正文、工具参数/结果、Memory 正文或用户目录配置；`--inline` 不写文件。
- 默认 CLI 会创建一个 `ConversationLedger` 并在 ReAct / Plan / Team 三条路径间共享，原始 `LlmClient.Message` 以 append-only JSONL 写入 `~/.paicli/history/raw/session-*.jsonl`。记录包含 mode / actor / source，以及完整 system / user / assistant / tool_call / tool_result（含 reasoning、工具参数和结果、图片 payload）；`/clear`、图片裁剪和 conversationHistory 压缩只改发送视图，只能向账本追加边界事件，不能改写或删除旧行。该目录在 POSIX 上使用 0700、文件使用 0600；内容可能敏感，不要提交或随意分享。
- JLine 交互升级计划记录在 `docs/phase-22-jline-interaction-upgrade.md`。

## 关键行为约束（Agent 必读）

### Memory

- 长期记忆只通过 `/save` 或用户明确要求保存；不要自动提取事实
- `PAI.md` 管团队共享的项目规则，长期记忆管个人或项目作用域的稳定事实；不要把一次性协作经验写进 `PAI.md`
- 长期记忆只保存跨会话稳定事实，不保存临时指令；默认项目级作用域，跨项目通用偏好才用 global
- 长期记忆去重以 `type + scope + project` 为边界，只做确定性的 Unicode/格式规范化与保守语法助词近似匹配；不要把它描述成事实冲突消解
- 长期记忆必须可审计和可删除：`/memory list` / `/memory search <关键词>` / `/memory delete <id>` / `/memory clear`
- 当前短期上下文只有各 Agent 实际发给 LLM 的 `conversationHistory`，不要再维护 `MemoryManager.shortTermMemory` 之类的影子消息副本。
- 自动压缩有两条路径：`PAICLI_SESSION_MEMORY_COMPACTION_ENABLED=true` 时优先使用阈值前异步生成的增量 Session Memory 摘要；摘要未就绪、失效或收益不足时回退 `ConversationHistoryCompactor` 完整摘要。两条路径最终都原地重建同一份 conversationHistory。
- 自动压缩阈值按 Claude Code 风格预留摘要输出和安全缓冲：大窗口使用 `window - 20k - 13k`，例如 200k 窗口约 167k 触发、1M 窗口约 967k 触发；小窗口按比例缩小预留。
- ReAct、Plan 单任务和 SubAgent 默认不设固定迭代上限；`paicli.react.hard.max.iterations` 仅在显式配置正整数时启用。显式轮数/Token 预算或停滞检测命中后必须禁用工具并做一次最佳努力收尾，返回“部分完成”结果，不能直接丢弃已有工作。

### HITL + 策略层

- 拦截顺序：HitlToolRegistry → ToolRegistry → PathGuard/CommandGuard
- 用户无法批准策略拒绝的请求
- PathGuard 强制路径限定在项目根内
- CommandGuard 是辅助黑名单，不是主防线
- 微信 iLink 通道没有人工审批面板，必须走非交互式默认拒绝策略：只读工具默认允许，`execute_command` 必须精确命中命令白名单，`mcp__*` 必须命中 MCP 白名单，`revert_turn` 和浏览器会话切换默认拒绝，文件写入仍由 PathGuard 限定在绑定 workspace 内。

### Plan 审阅交互

- `Enter` 执行 / `Ctrl+O` 展开 / `ESC` 取消 / `I` 补充重规划
- 方向键不应被误判为 ESC
- 涉及改动要连 raw mode 和回退路径一起看

### 并行工具

- 三条路径都走 `executeTools()`，不手写 for-loop
- 默认最多 4 个并发，结果保持原始顺序

### Web + Browser

- 每轮 system prompt 会注入当前日期/时区，用于相对日期理解；不做基于“最新/当前/今天”等关键词的自动 freshness 预检。模型只能在顶层用户目标明确时选择联网工具，用户明确要求不要联网时优先遵从。
- 当前顶层输入只是裸标题、主题或摘录，没有动作、问题或目标时，先澄清，本轮不调用任何工具；不得自行猜测 URL。
- 用户明确要求查找但没有提供 URL 时，先 `web_search` 再基于结果继续；`web_fetch` 与浏览器导航 URL 只能来自用户实际提交的顶层原文（不含 `@path` / MCP resource 展开正文），或本执行分支成功完成的 `web_search` 结构化 `discoveredUrls`。搜索正文、snippet、query 回显、错误提示、`web_fetch` 正文、浏览器导航/快照/网络列表、普通本地工具输出、模型 reasoning / 回复 / tool arguments 都不能扩充 URL 授权；当前 StepSearch MCP 的非结构化文本不会生成 URL 凭据。
- 运行时 `TurnToolPolicy` 覆盖 ReAct / Plan / Team，在 StepSearch、内置 Web provider 或 MCP / Chrome 路由之前执行；Plan 审阅补充会重建策略。Plan 并行任务和 Team worker 各用独立策略副本，不能跨分支共享新发现的 URL；只有 DAG 中声明的后继依赖才会继承前置分支的类型化 `web_search` URL 凭据，不从任务回复文本重新提取。grounded URL 只开放导航，成功导航只建立当前页的读取上下文；读取结果不产生新 URL 授权，点击/填写等交互仍需顶层原文明确授权。shared Chrome 状态必须从真实 `BrowserSession` 跨轮读取，非 M-CLI 创建的标签页只能在用户明确要求时只读，不能由 Agent 导航、改写或关闭；导航结果不得把完整标签页清单回灌模型。策略拒绝不得通过换工具或换 provider 绕过。
- “当前项目/当前 README/当前文件/当前代码”等表达属于本地上下文任务，通常应由模型选择 `glob_files` / `grep_code` / `read_file`，而不是联网工具。
- 当前模型为 `step-3.7-flash*` 且自动/显式 `step_search` MCP 的 `web_search` / `web_fetch` 已就绪时，内置 `web_search` / `web_fetch` 会优先转调 StepSearch MCP；未就绪或调用失败时回退到原 SearchProvider / WebFetcher。
- 有可信来源的已知 URL 先 `web_fetch`，SPA/防爬墙 fallback 到 Chrome DevTools MCP
- 浏览器读取优先 `take_snapshot`，不默认 `take_screenshot`
- 公开页面不要提前切 shared 模式

### Skill

- system prompt 索引段注入三处提示词，上限 20 个 / 4KB
- `load_skill` → SkillContextBuffer → 下一轮 user message 前置注入

## 修改时的硬规则

### 1. 改行为 → 同步文档

`AGENTS.md` / `README.md` / `ROADMAP.md`（仅状态变化时）

### 2. 改命令入口 → 联动

`Main.java` + `CliCommandParser.java` + 测试 + `README.md` + `AGENTS.md`

未识别的 `/xxx` 在 CLI 层直接报"未知命令"，不回退给 Agent。

### 3. 改 Plan 审阅交互 → 联动

`Main.java` + `PlanReviewInputParser.java` + 测试 + 手工验证

### 4. 改工具集 → 联动

`ToolRegistry.java` + Agent/PlanExecuteAgent/SubAgent 提示词 + 可能 Planner 提示词 + 文档

### 5. 改模型/接口 → 联动

对应 Client + `LlmClientFactory.java` + `.env.example` + 文档

### 5.1 改 Embedding → `EmbeddingClient` + `VectorStore` + `.env.example` + 文档

### 5.2 改 Web/搜索 → `web/` 相关 + ToolRegistry + `.env.example` + 文档 + 测试

### 5.3 改 Memory → `MemoryManager` + `LongTermMemory` + `TokenBudget` + 测试 + 文档

### 5.4 改 HITL/策略 → `policy/` + ToolRegistry + HitlToolRegistry + 提示词 + `.env.example` + 文档 + 测试

### 5.5 改 MCP → `mcp/` + ToolRegistry + HITL + AuditLog + 提示词 + 文档 + 测试

### 6. 不提交 `.env` / 真实 API Key / `target/` 产物

### 7. 保持代码可读性，不过度抽象

## 验证路径

| 场景 | 命令 |
|------|------|
| 代码搜索工具 | `mvn test -Dtest=ToolRegistryTest,CodeSearchGoldenSetTest,ApprovalPolicyTest` |
| 命令解析 | `mvn test -Dtest=CliCommandParserTest,PlanReviewInputParserTest,MainInputNormalizationTest` |
| DAG/Plan | `mvn test -Dtest=ExecutionPlanTest` |
| Multi-Agent | `mvn test -Dtest=AgentRoleTest,AgentMessageTest,AgentOrchestratorTest` |
| TUI/终端 | `mvn test -Pphase16-smoke` |
| RAG | `mvn test -Dtest=CodeChunkerTest,CodeAnalyzerTest,VectorStoreTest,CodeIndexTest` |
| 常规回归 | `mvn test -Pquick` |

## 给新线程的导航

1. 先看本文件 → 2. `README.md` → 3. `Main.java` → 4. 按任务进入对应模块

| 任务类型 | 先看 |
|----------|------|
| CLI 命令 | Main.java + CliCommandParser.java |
| 规划/DAG | PlanExecuteAgent.java + Planner.java + ExecutionPlan.java |
| 工具调用 | ToolRegistry.java + Agent.java |
| 代码搜索 | ToolRegistry.java (`glob_files` / `grep_code` / `read_file`) |
| 模型/API | llm/*Client.java + LlmClientFactory.java |
| RAG 语义辅助 | CodeRetriever.java + CodeIndex.java + VectorStore.java |
| Multi-Agent | AgentOrchestrator.java + SubAgent.java |
| Better Harness | BetterHarnessRunner.java + BetterHarnessEvidenceCollector.java |
| LLM-as-a-Judge | eval/LlmJudge.java + eval/PositionBalancedPairwiseJudge.java |
| MCP | McpServerManager.java + McpClient.java |
| TUI/渲染 | render/Renderer.java + RendererFactory.java |

## 当前已知边界

云端 CLI 以本地终端连接云端 API 为目标，设计见 `docs/cloud-cli-design.md`，实验环境见 `deploy/README.md`。`deploy/compose.yaml` 仅是默认断网的 gVisor 实验及可选中间件，不是已经接入 Runtime API 的租户沙箱。不得将共享 Key/共享 cwd 的现有 Runtime API 直接对外开放；沙箱启动失败不得回退宿主机执行。MySQL/Redis 尚未接入 Java，MinIO 不作为默认生产镜像。

以下在路线图但未交付：容器/VM 沙箱 / MCP OAuth + sampling + server 自动重启

不要把 `ROADMAP.md` 中"将来要做"误读成"现在已有"。

## 持续维护约定

形成稳定协作规则时直接补进本文件，不要只留在聊天记录里。详细实现细节补到 `docs/agents-reference.md`。
