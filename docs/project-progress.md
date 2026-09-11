# 项目建设进度

本文集中记录 M-CLI 的建设阶段与实现边界。使用方式见 [README](../README.md)，架构导读见 [项目与部署说明](m-cli-overview.md)，后续方向见 [ROADMAP](../ROADMAP.md)。阶段记录用于追溯，具体行为以当前代码为准；路线图中的规划不代表已交付。

## 当前状态

- 云端 CLI 基础环境已完成运行验收：新加坡轻量服务器已初始化，单用户 Runtime API 由 systemd 托管并保持 loopback-only，Docker/gVisor 沙箱动态探针通过；Windows 预发布客户端可自动建立 SSH 隧道、按 UTF-8 提交中文并渲染最终回答。可选 MySQL/Redis 保留配置但未启动。GitHub Actions 工作流已准备，仓库 Secrets 与部署开关仍待配置；用户鉴权、持久租户沙箱和云端调度尚未完成。见 [设计与实施顺序](cloud-cli-design.md)。

- 已推进至第 23 期微信 iLink 文本通道 MVP；各模块完成度和后续边界见下文。
- 2026-09-10：产品由 PaiCLI 更名为 M-CLI，更新启动标识、提示词、文档和 JAR 名称，保留原有配置兼容性。
- 代码已上传至 GitHub：`mbky-159/M-CLI`。
- 改名验证：打包成功；快速回归 864 项，855 项通过，9 项失败在改名前代码中同样复现，详见项目导读。
- 云端部署尚未完成。
- 未交付：容器/VM 沙箱、MCP OAuth、sampling、server 自动重启。

## 演进历程

### 第一期：ReAct Agent CLI

- 单轮对话驱动的 `ReAct` 循环
- 支持工具调用：读文件、写文件、列目录、文件 glob、代码 grep、执行命令、创建项目、RAG 语义辅助检索、联网搜索、MCP 动态工具
- ReAct、Plan 单任务和 Multi-Agent SubAgent 默认不限制执行轮数，由模型在不再调用工具时自然结束；无人值守或严格成本控制场景可用 `-Dpaicli.react.hard.max.iterations=N` 显式设置上限
- 显式轮数/Token 上限或重复调用安全阀命中后，会关闭工具并额外执行一次最佳努力收尾，明确返回部分成果、未完成项和下一步，而不是直接丢弃过程结果
- 更适合简单任务或单步操作

### 第二期：Plan-and-Execute + DAG

- 在保留 `ReAct` 模式的基础上新增复杂任务规划能力
- 支持先拆解任务，再按照依赖顺序执行
- 新增 `/plan` 入口，以一次性计划执行方式增强默认的 `ReAct`
- 计划生成后，会先与用户确认再执行
- 更适合多步骤、带依赖关系的复杂任务

### 第三期：Memory + 上下文工程

- 每个 Agent 实例用自己的 `conversationHistory` 管理当前对话与工具结果，不再复制第二份影子短期记忆
- 长期记忆通过 `/save <事实>` 或用户明确说“记一下 / 记住”时的 `save_memory` 保存关键事实，默认项目级作用域，跨会话复用
- 项目级记忆通过 `PAI.md` / `.paicli/PAI.md` 启动自动注入，适合提交到仓库的团队共享规则；`PAI.local.md` / `.paicli/PAI.local.md` 只做本地覆盖
- 注入给模型的相关记忆只使用长期稳定事实，不把当前轮短期对话误当成“历史记忆”
- 对话接近预算时自动做摘要压缩；可选 Session Memory 增量摘要快速路径，不可用时回退完整对话摘要
- 新增 `/memory` 查看状态、`/memory list/search/delete/clear` 管理长期记忆、`/save` 手动保存事实；Agent 在用户明确说“记一下 / 记住”时可调用 `save_memory`

### 第四期：RAG 检索 + 代码库理解

- 代码向量化（Embedding），支持本地 Ollama 和远程 API
- SQLite 持久化 + 余弦相似度语义检索
- 代码分块（文件/类/方法粒度）与 AST 解析
- 代码关系图谱（extends/implements/imports/calls/contains）
- 新增 `/index`、`/search`、`/graph` CLI 命令
- `search_code` 作为语义辅助检索工具；精确代码定位默认走 `glob_files` / `grep_code` / `read_file` 现用现查

### 第五期：Multi-Agent 协作 + 角色分工

- 三个角色：规划者（Planner）、执行者（Worker）、检查者（Reviewer）
- 主从架构：编排器（Orchestrator）协调子代理（SubAgent）
- 规划者拆解任务 -> 执行者执行 -> 检查者审查质量
- 审查未通过时带反馈重试（最多 2 次），冲突自动解决
- 新增 `/team` CLI 命令，进入多 Agent 协作模式

### 第六期：Human-in-the-Loop + 审批流

- 危险操作静态规则识别：`write_file`、`execute_command`、`create_project`、`revert_turn`
- 三级危险等级：高危（`execute_command`）、中危（`write_file` / `create_project`）
- 审批决策：批准 / 全部放行 / 拒绝 / 跳过 / 修改参数后执行
- HITL 默认关闭，通过 `/hitl on` 启用
- 新增 `/hitl` CLI 命令，支持 `/hitl on`、`/hitl off`、`/hitl`（查看状态）

### 第七期：异步执行 + 并行工具调用

- 同一轮 LLM 返回多个 `tool_calls` 时，工具层会并行执行
- ReAct、Plan-and-Execute、Multi-Agent Worker 都复用统一的批量工具执行入口
- 工具结果仍按原始 `tool_call` 顺序回灌，保证消息历史协议稳定
- 批量工具调用有统一超时与取消兜底，单个 `execute_command` 仍保留 60 秒命令级超时
- Plan-and-Execute 与 Multi-Agent 已支持按依赖批次并行执行独立任务

### 第八期：多模型适配 + 运行时切换

- `LlmClient` 接口抽象 + `AbstractOpenAiCompatibleClient` 模板基类
- 内置 `GLMClient`、`DeepSeekClient`、`StepClient`、`KimiClient`、`FreeLlmApiClient`、`AgnesClient` 六个瘦实现
- `/model glm-5.1` / `/model glm-5v-turbo` 明确切 GLM 模型；`/model deepseek` / `/model step` / `/model kimi` / `/model freellmapi` / `/model agnes` 切 provider 并读取配置里的具体模型
- 配置持久化到 `~/.paicli/config.json`，API Key 可从配置、环境变量或 `.env` 读取
- OpenAI-compatible provider 共用有限重试：`408` / `429` / 可恢复 `5xx` 与瞬时连接故障默认总尝试 3 次，采用指数退避 + jitter 并读取 `Retry-After`；确定性 `4xx` 直接失败。SSE 尚未交付输出时可安全重发，已经流给终端的半截内容不会自动重放

### 第九期：联网能力 + Web 工具

- `web_search` 抽象成 `SearchProvider` 接口，内置三个实现：智谱 Web Search（默认，与 GLM 共用 Key，0.01–0.05 元/次）、SerpAPI（国际通用付费）、SearXNG（开源自托管免费）
- `web_fetch` 新工具：有可信来源的 URL → OkHttp 抓取 → Jsoup 解析 → 简易 readability → Markdown 正文
- 联网不再对“最新/当前/今天/趋势/新闻/版本”等关键词做自动 freshness 预检。顶层用户输入只是标题、主题或摘录而没有任务目标时，M-CLI 会先询问用户，本轮不调用工具；用户明确要求不要联网时始终优先遵从。
- 模型不得根据标题猜测 URL。明确要求查找但没有 URL 时先 `web_search`；`web_fetch` 和浏览器导航只接受用户实际提交的顶层原文（不含 `@path` / MCP resource 展开正文）中的 URL，或当前执行分支由搜索 provider 返回的结构化 `discoveredUrls`。搜索正文/snippet/query 回显/错误提示、`web_fetch` 正文、浏览器结果和普通文件/命令输出里的链接不会自动取得访问授权；StepSearch MCP 的非结构化结果文本也不会生成 URL 凭据。
- 运行时 `TurnToolPolicy` 覆盖 ReAct / Plan / Team，并在 StepSearch、内置 Web provider 和 MCP / Chrome 路由之前校验顶层意图与 URL 来源；Plan 审阅补充会重建策略。并行任务/worker 不共享新发现的 URL，只有 DAG 中声明的后继依赖会继承前置分支的类型化 `web_search` URL 凭据，不从任务回复文本重新抽取。grounded URL 先只开放导航，成功导航只建立当前页读取上下文，页面读取不扩充 URL 授权；点击/填写等交互需顶层原文明确授权。shared Chrome 状态跨轮读取，非 M-CLI 创建的标签页只在用户明确要求时开放只读，不能由 Agent 导航、改写或关闭；导航工具返回的全量标签页清单会在回灌模型前裁掉。被拒绝的调用不能靠切换工具绕过。
- 当前模型是 `step-3.7-flash*` 且自动/显式 `step_search` 远程 server 已就绪时，通过 `TurnToolPolicy` 后的内置 `web_search` / `web_fetch` 会优先走 StepSearch MCP；未就绪或调用失败时自动回退到原 provider。
- 默认安全策略：屏蔽 `file://` / 内网 / loopback；30 秒超时；5MB 响应上限；每分钟 30 次限流
- 边界明确：SPA / 防爬墙站点会返回空正文 + 已知边界提示，Agent 会 fallback 到浏览器 MCP 路线

### 第十期：MCP 协议核心

- 新增 `com.paicli.mcp` 模块，支持 stdio 子进程 server 与 Streamable HTTP 远程 server
- 启动时读取 `~/.paicli/mcp.json` 与 `.paicli/mcp.json`，项目级配置按 server 名覆盖用户级配置
- MCP `${VAR}` 支持系统环境变量、系统属性、项目 `.env`、用户 `~/.env`；检测到 `STEP_API_KEY` 时自动内置 `step_search` 远程 MCP，显式同名配置优先
- MCP 工具自动注册为 `mcp__{server}__{tool}`，参数 schema 会清洗 `$ref` / `anyOf` / 超长 description，降低模型调用失败率
- 所有 MCP 工具默认走 HITL 审批和审计，审计参数会脱敏 token / key / password / Authorization / Bearer 凭证
- 支持 MCP resources：server 声明 `resources` capability 后，自动注册 `mcp__{server}__list_resources` / `mcp__{server}__read_resource` 虚拟工具
- 普通输入支持 `@server:protocol://path` 显式引用 resource，提交给 Agent 前展开为 `<resource>` 内联块
- 被动处理 `notifications/tools/list_changed`、`notifications/resources/list_changed`、`notifications/resources/updated`
- 运行中输入 `/cancel` 并回车可请求取消当前 Agent run
- CLI 命令：`/mcp`、`/mcp restart <name>`、`/mcp logs <name>`、`/mcp disable <name>`、`/mcp enable <name>`、`/mcp resources <name>`、`/mcp prompts <name>`
- `~/.paicli/mcp.json` 不存在时会自动创建默认 chrome-devtools 配置；项目级 `.paicli/mcp.json` 仍可按 server 名覆盖

### 第十二期：长上下文工程

- `LlmClient` 声明模型能力：`maxContextWindow()`、`supportsPromptCaching()`、`promptCacheMode()`
- GLM-5.1 默认 200k window，DeepSeek V4 默认 1M window，Agnes 2.0 Flash 默认 1M window，StepFun 默认 256k window，Kimi K2.6 默认 256k window，FreeLLMAPI 默认按 128k 保守预算
- `AgentBudget` 按当前模型动态计算预算，默认 `80% * maxContextWindow`，仍可用系统属性覆盖
- 上下文预算按模型 window 连续派生：所有窗口都保留自动压缩，较大窗口可启用更多 MCP resource 索引能力
- `search_code` 未显式传 `top_k` 时按上下文模式自适应；默认代码定位仍优先实时 grep/read
- 长上下文模式下自动把 MCP resources 的 URI / 描述索引注入 system prompt，不自动注入正文
- inline 模式下 Token / cached input tokens / 估算成本 / 耗时进入底部状态栏，避免占用正文输出区
- `/context` 会显示当前上下文模式、prompt cache 模式、RAG topK、resources 自动索引状态

### 第十三期：Chrome DevTools MCP

- 默认接入 Google 官方 `chrome-devtools-mcp@latest`，注册为 `mcp__chrome-devtools__navigate_page`、`take_snapshot`、`click`、`fill_form` 等浏览器工具
- `~/.paicli/mcp.json` 不存在时启动自动创建模板，默认使用 `--isolated=true` 临时浏览器 profile
- 用于处理 SPA / JS 渲染 / 防爬墙 / 表单交互页面；微信公众号文章、知乎专栏、推特、小红书等 `web_fetch` 失败站点会引导走浏览器 MCP
- HITL 的“全部放行”支持 MCP server 维度，连续浏览器操作可对 `chrome-devtools` 一次确认
- `image` 类型结果会作为图片输入附加到下一轮；文本 fallback 仍保留，用于日志、人类可读摘要，以及 DeepSeek 等不接受图片块的 provider 自动降级上下文
- MCP initialize 默认超时为 60 秒；CLI 首屏默认最多等待 8 秒，超时后先进入交互，未完成的 server 保持 `starting` 并在后台继续启动，可用 `/mcp` 和 `/mcp logs <name>` 追踪

### 第十四期：CDP 会话复用 + 登录态访问

- 新增 `/browser status`、`/browser connect [port]`、`/browser disconnect`、`/browser tabs` 命令组，并给 Agent 暴露内部 `browser_connect` / `browser_disconnect` / `browser_status` 工具
- 默认仍使用 `--isolated=true` 临时浏览器 profile；执行 `/browser connect` 后，运行时把 `chrome-devtools` 切到 `--autoConnect`，复用已在 `chrome://inspect/#remote-debugging` 允许远程调试的登录态 Chrome
- Agent 遇到登录页、权限不足或明确需要登录态页面时，会先调用 `browser_connect` 自动切到 shared；公开页面如微信公众号文章不提前切换
- `/browser connect <port>` 保留旧式 CDP 端口兼容路径：先探活 `127.0.0.1:<port>/json/version`，成功后切到 `--browser-url=http://127.0.0.1:<port>`；失败时不会改 MCP 启动参数，并输出 macOS / Windows / Linux 的 Chrome 启动命令
- 切换 shared / isolated 模式都会清空 `chrome-devtools` 的 server 维度全部放行，避免旧信任跨模式延续
- shared 模式下 `close_page` 只能关闭 M-CLI 自己创建的 tab；无法证明是 M-CLI 创建的 tab 会被策略层拒绝
- 敏感页面命中规则后，`click` / `fill_form` / `evaluate_script` 等改写型浏览器工具必须单步 HITL 审批，不复用全部放行；读型工具如 `take_snapshot` 仍可继续使用
- 审计日志为 chrome-devtools 工具追加可选浏览器 metadata：`browser_mode`、`sensitive`、`target_url`，旧格式 JSONL 仍可读取

### 第十五期：Skill 系统 + 内置 web-access skill

把"Agent 该怎么思考"从硬编码 system prompt 抽出，沉淀成可复用单元。每个 Skill 是一个目录：`SKILL.md`（决策手册）+ `references/`（按需读取）+ 可选 `scripts/`（可执行依赖）。

- 三层加载位置（按优先级，后者整体覆盖同名 skill）：jar 内置 < 用户级 `~/.paicli/skills/<name>/` < 项目级 `<project>/.paicli/skills/<name>/`
- 启动期把启用 skill 的 `name` + `description` 注入三处 Agent 系统提示词索引段（启用上限 20 个，索引段 ≤ 4KB）
- 内置工具 `load_skill(name)`：LLM 在 system prompt 看到匹配 description 时主动调用，M-CLI 把 SKILL.md 正文（5KB 截断）写入 `SkillContextBuffer`，下一轮 user message 自动前置注入
- 内置 web-access skill：决策手册（浏览哲学四步法 + 工具选择表 + 浏览器优先级 + Jina 兜底说明）+ 6 个站点经验文件（mp.weixin / zhuanlan.zhihu / x.com / xiaohongshu / github / juejin）+ cdp-cheatsheet
- frontmatter 走手写 YAML 子集解析，不引 SnakeYAML；解析失败 stderr 警告但不阻塞启动
- CLI 命令：`/skill list` / `/skill show <name>` / `/skill on <name>` / `/skill off <name>` / `/skill reload`
- 启用状态持久化：`~/.paicli/skills.json` 的 `disabled` 列表，默认全启用
- 与 HITL 协同：Skill 内调用 `execute_command` 等危险工具仍走既有 HITL 审批，沿用 `execute_command` 工具维度全放行；不给 Skill 单独审批维度

设计意图：从「写工具」演进到「打包专家手册」。当工具堆成山（M-CLI 当前内置 9 个 + MCP 60+ 工具），用 Skill 给 LLM 一份按场景展开的"专家手册"，比往 system prompt 里塞更多规则更可扩展。

### Better Harness 原生审计

M-CLI 内置 `better-harness` Skill 和 `/better-harness` 命令，用于审查编码 Agent 外层工作流，而不只是最终代码 diff。实现基于 QoderAI Better Harness 的 Agent Work Loop 方法，并针对 M-CLI 的 Java 运行时、`ConversationLedger`、`PAI.md`、Skill、MCP 与 HITL 资产做了原生适配，不依赖 Node。

- `/better-harness` 或 `/better-harness normal`：正常深度审查，生成持久化报告
- `/better-harness quick`：缩小项目摘录和候选 finding 上限，快速建立基线
- `/better-harness --inline`：只在当前终端输出，不创建报告文件
- 三路证据保持独立：当前会话脱敏元数据、Project Harness、Agent Customize
- 三路 evidence specialist 并行运行且不暴露工具，lead 只基于三个结果做最终定级和归并
- 运行期间展示 5 个确定性工作单元、三路审查完成数、当前阶段、累计耗时和 ESC 取消提示，不再用静态等待或估算进度冒充真实完成度
- 终端报告统一经过 M-CLI Markdown 渲染器，标题、强调、列表、表格和代码块按当前终端宽度显示，不直接打印 Markdown 源码标记
- 默认不读取消息正文、reasoning、工具参数/结果、Memory 正文、用户目录资产或其他 provider
- 持久化输出位于 `.paicli/better-harness/<run-id>/`：`report.md`、`report.html`、`findings.json`
- 一次报告只能证明当前机制和观测证据，不能单独证明工作流已经因修复而改善；效果需要后续可比较 Task Episode

### LLM-as-a-Judge 离线评测核心

`com.paicli.eval` 提供可独立调用的 Rubric 评分和 Pairwise 双向对比组件。Judge 只返回逐维分数与证据，总分、权重和通过阈值由 Java 确定性计算；Pairwise 会交换 A/B 位置复评，前后不一致时降级为 `TIE`，用于暴露位置偏见。

当前交付是 Java 核心库和单元测试，不是 `/eval` 命令，也不会自动读取可能包含敏感内容的原始会话账本。数据集加载、批量 Runner、报告持久化、Judge 人工校准和真实业务指标仍需基于具体评测任务补齐。使用示例与边界见 [`docs/llm-as-a-judge.md`](llm-as-a-judge.md)。

### 第十六期：TUI 产品化（v16.1 形态修正后：双形态可切换）

v16.1 抽出 `Renderer` 接口 + 三个实现：

| 形态 | 启用方式 | 视觉风格 |
|---|---|---|
| **inline 流式 TUI**（默认） | 直接运行 / `PAICLI_RENDERER=inline` | Claude Code / Qoder 风格：M 主题彩色开屏、主屏直出、transcript 当前位置的 `* ` 输入提示、JLine `Status` 托管的底部 dock（YOLO/HITL、MCP、Skill、model、ctx、token、cwd 等关键字段带克制彩色高亮；ctx 是当前上下文估算，in/out/cache 是调用统计）、右侧输入提示、行内可折叠工具块（`Read 3 files (ctrl+o to expand)`）、行内 git diff、HITL 单字符 `[y/n/a/s/m]` 提示 |
| **lanterna 全屏 TUI** | `PAICLI_RENDERER=lanterna`（或兼容旧 `PAICLI_TUI=true`） | v16 三栏全屏：文件树 + 对话流 + 状态栏 + 底部输入栏，HITL 模态弹窗 |
| **plain 兜底** | `PAICLI_RENDERER=plain` | 纯 println，无折叠 / 状态栏，等价 v15 行为 |

- 三种形态共享同一套 `Agent` / `ToolRegistry` / `MemoryManager` / MCP server / SkillRegistry / HITL handler，不创建孤立空会话
- 普通输入走 ReAct；`/plan <任务>` 走 Plan-and-Execute；`/team <任务>` 走 Multi-Agent；`/cancel` 可取消运行中任务
- 通用命令：`/clear`、`/context`、`/memory`、`/memory clear`、`/save <事实>`、`/export`、`/better-harness`、`/hitl`、`/hitl on`、`/hitl off`、`/config`、`/exit`
- Lanterna 的展示快照保存到 `~/.paicli/history/session_*.jsonl`
- 原始会话账本独立保存到 `~/.paicli/history/raw/session-*.jsonl`：默认 CLI 的 ReAct / Plan / Team 共享同一个 append-only 文件，system、user、assistant、tool_call、tool_result 都保留完整 `LlmClient.Message`（含 reasoning、工具参数/结果和图片 payload）。`/clear` 和上下文压缩只改模型发送视图，不改写旧账本；POSIX 下目录为 0700、文件为 0600。账本可能包含敏感内容，请勿提交或随意分享
- 兼容旧设置：`PAICLI_TUI=true` 自动映射为 `PAICLI_RENDERER=lanterna`（已 deprecated）
- `PAICLI_NO_STATUSBAR=true` 在 inline 模式下禁用 JLine 底部 dock（不适合 ANSI 光标控制的终端）
- `NO_COLOR=1` 禁用所有 ANSI 颜色，保留布局

### 第十七期：LSP 诊断注入（MVP）

- `write_file` 成功后触发 post-edit 诊断，诊断结果不会阻塞工具主流程
- 当前 MVP 对 Java 文件使用 JavaParser 做轻量语法诊断，不依赖本机安装 JDT LS
- ReAct、Plan-and-Execute、Multi-Agent 三条路径都会在下一轮 LLM 请求前注入 pending 诊断
- 诊断按 error / warning / info、文件、行列号、message 格式化，默认最多注入 20 条
- 配置：`PAICLI_LSP_ENABLED=false` 可关闭，`PAICLI_LSP_MAX_DIAGNOSTICS=20` 可调整注入上限
- 后续增强：接入 JDT LS / rust-analyzer / pyright / gopls 的 stdio JSON-RPC transport

### 第十八期：Git Side-History 快照与回滚（MVP）

- 每个 ReAct / Plan / Team turn 开始前创建 `pre-turn` 快照，结束后异步创建 `post-turn` 快照
- 快照仓库使用 JGit 纯 Java 实现，默认位于 `~/.paicli/snapshots/<project_hash>/<worktree_hash>/.git`，不写用户项目 `.git`
- `/snapshot` 查看最近快照，`/snapshot status` 查看配置与 side-git 目录，`/snapshot clean` 清理当前项目快照目录
- `/restore <N>` 恢复到最近第 N 个 `pre-turn` 快照；恢复前会先创建 `pre-restore` 快照
- Agent 内置 `revert_turn` 工具，纳入 HITL 与 AuditLog 危险工具链
- 配置：`PAICLI_SNAPSHOT_ENABLED=false` 可关闭，`PAICLI_SNAPSHOT_MAX=50`、`PAICLI_SNAPSHOT_EXCLUDES=...`、`PAICLI_SNAPSHOT_DIR=...` 可调整策略

### 第十九期：Prompt 分层架构（MVP）

- ReAct、Plan task executor、Multi-Agent 三角色、Planner 的 system prompt 已从 Java 硬编码抽离到 `src/main/resources/prompts/`
- `PromptAssembler` 按 `base -> personality -> mode -> approval -> runtime_context -> project_context -> skills -> context_mgmt -> handoff` 组装；`runtime_context` 注入当前日期/时区，动态项目上下文靠后注入
- `project_context` 会先注入 `PAI.md` 项目记忆，再注入 `/save` 检索到的相关长期记忆和 MCP resource 索引
- 支持用户级覆盖 `~/.paicli/prompts/...`，支持项目级覆盖 `.paicli/prompts/...`，项目级优先级最高
- 覆盖是整文件替换；`base.md` 和最终 prompt 必须包含 `## Language`
- Prompt 改动审计模板见 `docs/prompt-analysis-template.md`

### 第二十期：异步后台任务 + Runtime API（MVP）

- `DurableTaskManager` 使用 SQLite 持久化后台任务队列，默认位置 `~/.paicli/tasks/tasks.db`
- 任务生命周期：`enqueued -> running -> completed / failed / canceled`
- `/task`、`/task add <任务内容>`、`/task cancel <task_id>`、`/task log <task_id>` 提供 CLI 闭环
- Worker Pool 默认 2 个后台 worker，可通过 `PAICLI_TASK_WORKERS` 调整
- `java -jar target/m-cli-1.0-SNAPSHOT.jar serve --http --port 8080` 启动 localhost Runtime API
- Runtime API 端点：`POST /v1/threads`、`POST /v1/threads/{id}/turns`、`GET /v1/threads/{id}/events`
- Runtime API 强制要求 `PAICLI_RUNTIME_API_KEY` 或 `-Dpaicli.runtime.api.key`
- 详细文档见 `docs/phase-20-runtime-api.md`

### 第二十一期：图片复制粘贴输入（MVP）

- `LlmClient.Message` 支持 `ContentPart`，包括 `text`、`image_base64`、`image_url`
- 请求体在含图片且 provider 支持图片输入时输出带图片块的 content array，纯文本仍保持 string content
- `LlmClient` 公共接口用 `supportsImageInput()` 声明图片能力；DeepSeek 等文本 provider 会把图片块替换成文本提示，避免 `image_url` 进入不支持多模态的 API 请求体
- GLM 套餐用户可通过 `/model glm-5v-turbo` 切换到 GLM-5V-Turbo 多模态模型，再用 Ctrl+V 或 `@image:` 输入图片；本地 base64 图片会按智谱格式写入 `image_url.url`
- MCP `image` content 会保留 base64 与 `mimeType`，在 ReAct / Plan / SubAgent 工具结果后作为图片 user message 回灌；当前 provider 不支持图片输入时，请求序列化层会自动省略图片 payload 并保留文本提示
- 用户可通过 `@image:file:///abs/path.png`、`@image:/abs/path.png` 或 `@image:relative/path.png` 引用本地图片
- 本地图片和 MCP 图片都会按 Claude Code 同类策略预处理：不是 OCR 成文本，而是压缩 / 缩放后作为图片块发送；带 alpha 的 PNG 会铺白底重编码；额外注入来源、尺寸和坐标映射元信息
- 本地 `@image:` 消息会要求模型优先分析本轮图片；除非用户明确要求结合历史，历史对话和历史工具结果不能替代当前图片内容
- 新一轮 ReAct / SubAgent 任务开始前会省略历史 image payload，仅保留文本元信息，避免旧截图反复进入上下文；模型 `reasoning_content` 默认只写日志 / 展示，DeepSeek V4 / Kimi thinking tool-call 续轮会按 provider 协议带回上一轮 assistant reasoning
- DeepSeek 流式调用默认使用 HTTP/1.1，规避部分 HTTP/2 网关长 SSE 响应被重置导致的 `stream was reset: INTERNAL_ERROR`
- 当前边界：不做视频 / 音频、图像生成、TUI sixel 图片预览

### 第二十三期：微信 iLink 通道（文本 MVP）

- 新增进程级入口：`paicli wechat setup`、`paicli wechat start`、`paicli wechat status`、`paicli wechat daemon start|stop|restart|status|logs`
- 新增交互式入口：在 M-CLI 主界面输入 `/wechat` 可扫码绑定并在当前进程后台启动微信通道；`/wechat setup` 重新扫码绑定，`/wechat status` 查看状态，`/wechat stop` 停止通道
- 默认不开启微信通道；用户必须主动执行 `setup` 并扫码确认完成绑定
- 支持在 Warp / iTerm2 / WezTerm 等兼容终端内直接显示 260px PNG 二维码；不支持终端图片协议时回退为字符二维码和链接
- 微信侧使用 iLink `getupdates` 长轮询收消息、`sendmessage` 分片回消息，不依赖 SSE；这是独立通道，不是 Skill，也不是 Runtime API
- 运行时只接受绑定用户私聊；普通消息单并发排队，`/help`、`/status`、`/pause`、`/resume`、`/stop` 走队列外控制路径
- 微信侧用户消息会回显到 M-CLI 终端 transcript；M-CLI 终端继续显示 thinking / 工具调用过程，微信侧只接收 assistant 正文。iLink 协议层仍是 `text_item.text` 文本消息，没有显式 Markdown parse mode；M-CLI 会保留 ClawBot 稳定支持的 Markdown 子集（列表、引用、粗体、行内代码、真实代码块），把标题转成粗体标题、把表格转成移动端更稳的键值/列表，并过滤图片 Markdown / H5-H6 / 中文斜体等兼容性差的标记；非代码类 fenced block（流程说明、长中文箭头链）会解包并换行，避免微信侧出现横向滚动代码块。iLink 不提供真正 SSE 或改单条消息能力。
- 微信通道使用非交互式默认拒绝策略：只读工具默认允许，`write_file` / `create_project` 继续受 workspace PathGuard 限制，`execute_command` 必须精确命中命令白名单，`mcp__*` 必须命中 MCP 白名单，`revert_turn` 和浏览器会话切换默认拒绝
- 当前文本 MVP 会保留图片 / 文件消息的媒体元数据提示，但 CDN 下载解密、图片块输入和 `/send` 文件推送仍待后续媒体链路补齐

### 第六期 HITL 增强（路径围栏 / 命令快速拒绝 / 操作审计）

`com.paicli.policy` 包，作为 HITL 之外的辅助层（不是沙箱、不提供进程隔离）：

- `PathGuard` 路径围栏：文件类工具强制限定在项目根之内，拦截绝对路径外逃 / `..` 穿越 / 符号链接逃逸
- `CommandGuard` 命令快速拒绝：工具执行前的辅助黑名单（`sudo` / `rm -rf 全盘` / `mkfs` / `dd of=/dev` / fork bomb / `curl|sh` / `find /` / `chmod 777 /` / `shutdown`），减少 HITL 弹窗骚扰
- `AuditLog` 结构化审计：危险工具调用按天写 JSONL 到 `~/.paicli/audit/`，含 `outcome (allow|deny|error)` 与 `approver (hitl|policy|none)`；`revert_turn` 也纳入危险工具链
- `write_file` 单文件 5MB 上限
- CLI 命令：`/policy` 查看安全策略状态、`/audit [N]` 看最近 N 条审计

当前策略层提供路径校验、命令拒绝规则与审计，不提供容器或进程隔离。
