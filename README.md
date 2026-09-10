# M-CLI

M-CLI 是一个 Java 编写的 AI 编程命令行工具，可以在当前项目中阅读代码、修改文件、执行命令，也支持先制定计划再执行任务。

## 快速开始

准备 Java 17+、Maven，以及至少一个模型服务的 API Key。

```sh
git clone https://github.com/mbky-159/M-CLI.git
cd M-CLI
```

复制配置文件：

```sh
# macOS / Linux
cp .env.example .env
```

```powershell
# Windows PowerShell
Copy-Item .env.example .env
```

编辑 `.env`，填入你使用的模型 Key。例如使用 DeepSeek：

```dotenv
DEEPSEEK_API_KEY=你的真实APIKey
```

删除或注释未使用服务的占位 Key（包括示例中默认的 `GLM_API_KEY=your_api_key_here`）。其他模型及可选参数见 [.env.example](.env.example)。

编译并启动：

```sh
mvn clean package
java -jar target/m-cli-1.0-SNAPSHOT.jar
```

打包默认跳过测试。进入后直接输入任务，按 Enter 提交：

```text
帮我梳理这个项目的入口和主要模块
查找登录逻辑，说明请求经过哪些方法
为这个方法修复空指针问题，并运行相关测试
```

要操作其他项目，先进入那个项目目录，再通过 JAR 的绝对路径启动。模型 Key 可通过环境变量或该目录的 `.env` 配置：

```sh
cd /path/to/your-project
java -jar /path/to/M-CLI/target/m-cli-1.0-SNAPSHOT.jar
```

## 日常使用

输入 `/` 后按 Tab 查看和补全命令。输入 `@` 引用当前项目中的文件或目录：

```text
解释 @src/main/java 中的主要模块
根据 @README.md 检查使用说明是否与代码一致
```

分析图片需要支持图片输入的模型：

```text
/model glm-5v-turbo
分析这张截图 @image:images/screenshot.png
```

### 模型与执行模式

```text
/model
/model deepseek
/model step
/model kimi
/model agnes
```

切换前需要配置对应 provider 的 Key；`/config` 查看和管理模型配置。

默认直接执行普通任务。需要先审阅方案时使用：

```text
/plan 为项目添加健康检查接口，并验证返回结果
```

计划审阅时按 Enter 执行、Ctrl+O 展开、ESC 取消、I 补充要求并重新规划。也可以单独输入 `/plan`，让下一条任务使用计划模式；任务结束后回到默认模式。

多 Agent 协作入口：

```text
/team 检查这个模块的实现，修复问题并验证
```

### 常用命令

| 命令 | 用法 |
|---|---|
| `/cancel` | 请求取消正在运行的任务 |
| `/context` | 查看当前上下文使用情况 |
| `/compact` | 手动压缩当前对话上下文 |
| `/clear` | 清空当前 ReAct 对话，保留长期记忆 |
| `/init` | 为当前项目生成 `PAI.md`；已有文件不覆盖 |
| `/save <事实>` | 保存当前项目的稳定规则或偏好 |
| `/memory list` | 查看长期记忆 |
| `/memory search <关键词>` | 搜索记忆 |
| `/memory delete <id>` | 删除指定记忆 |
| `/export` | 导出当前会话 Markdown，包含完整 system prompt |
| `/hitl on` | 开启危险操作人工审批，默认关闭 |
| `/policy` | 查看工具安全策略 |
| `/snapshot` | 查看最近工作区快照 |
| `/restore <N>` | 恢复到最近第 N 个任务前快照，会修改工作区 |
| `/task` | 查看后台任务；`/task add <任务>` 添加任务 |
| `/skill` | 查看可用 Skill |
| `/better-harness` | 审查当前项目的 AI 编码工作流并生成报告 |
| `/history clear` | 清空本机输入历史 |
| `/exit` | 退出程序 |

### MCP 与代码检索

MCP 配置支持用户级 `~/.paicli/mcp.json` 和项目级 `.paicli/mcp.json`。配置后使用 `/mcp` 查看连接状态，使用 `/mcp logs <name>` 检查服务日志。具体配置与浏览器接入见 [MCP 说明](docs/phase-10-mcp-core.md)和[浏览器说明](docs/phase-14-cdp-session-reuse.md)。

普通代码阅读与搜索不要求建立向量索引。需要 RAG 语义检索时，先按 `.env.example` 配置 Embedding 服务，再执行：

```text
/index
/search 用户登录后的权限校验
/graph YourClassName
```

默认 Embedding 配置使用本地 Ollama 的 `nomic-embed-text:latest`，使用该配置前需要启动 Ollama 并准备对应模型。`ripgrep` 为可选依赖，未安装时精确代码搜索会回退到 Java 扫描。

## 微信使用

在 CLI 中输入 `/wechat`，按提示扫码绑定并启动通道；`/wechat status` 查看状态，`/wechat stop` 停止通道。

也可以单独运行：

```sh
java -jar target/m-cli-1.0-SNAPSHOT.jar wechat setup
java -jar target/m-cli-1.0-SNAPSHOT.jar wechat start
```

微信通道默认不开启，工具执行采用非交互式策略。配置与限制见 [微信通道说明](docs/phase-23-wechat-channel.md)。

## Runtime API

设置模型 Key 和独立的 `PAICLI_RUNTIME_API_KEY` 环境变量后启动：

```sh
java -jar target/m-cli-1.0-SNAPSHOT.jar serve --http --port 8080
```

服务监听 `127.0.0.1:8080`，请求需携带 `Authorization: Bearer <PAICLI_RUNTIME_API_KEY>`。

`GET /healthz` 用于本机服务健康检查，不需要认证；业务端点仍需 API Key。该接口不代表当前 Runtime API 已具备多租户公网服务能力。

| 接口 | 用途 |
|---|---|
| `POST /v1/threads` | 创建会话 |
| `POST /v1/threads/{id}/turns` | 提交任务，JSON 请求体为 `{"input":"任务内容"}` |
| `GET /v1/threads/{id}/events` | 读取事件 |

更多示例见 [Runtime API 说明](docs/phase-20-runtime-api.md)，远程访问方式见 [部署说明](docs/m-cli-overview.md)。

## 配置与数据

为兼容 PaiCLI，M-CLI 继续使用 `PAICLI_*` 环境变量、`paicli.*` 系统属性、`.paicli` 数据目录和 `PAI.md` 项目记忆文件。

- 模型配置：`~/.paicli/config.json`。
- 项目规则：项目根目录的 `PAI.md`，可用 `/init` 创建。
- 个人覆盖：`PAI.local.md`，不提交到 Git。
- 输入与原始会话记录：`~/.paicli/history/`。
- 无法正常显示终端界面时，可设置 `PAICLI_RENDERER=plain`；关闭颜色用 `NO_COLOR=1`。

API Key、`.env`、会话导出和原始账本可能含敏感信息，请勿提交或分享。文件工具限制在当前项目内；命令执行没有容器隔离，执行前请确认当前工作目录和审批设置。

## 开发与项目资料

准备云端 CLI 与独立用户沙箱时，先看 [云端架构与建设步骤](docs/cloud-cli-design.md)和[部署实验说明](deploy/README.md)。目前提供隔离实验配置，尚未开放多用户服务。

```sh
mvn test -Pquick
mvn test -Pphase16-smoke
mvn test -DskipTests=false
```

- [项目建设进度](docs/project-progress.md)
- [项目架构与部署说明](docs/m-cli-overview.md)
- [后续规划](ROADMAP.md)
- [开发约定](AGENTS.md)

M-CLI 基于 PaiCLI 更名与维护，原项目作者为沉默王二。
