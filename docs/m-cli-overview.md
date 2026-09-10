# M-CLI 项目导读与部署入口

## 产品与改名范围

M-CLI 是基于 PaiCLI 的 Java 17+ Agent CLI。此次调整产品名称、M 形启动标识、用户可见提示、提示词、文档和 Maven artifactId；保留原作者署名。产物为 `target/m-cli-1.0-SNAPSHOT.jar`。

兼容层继续使用 `com.paicli` 包名、`PaiCli*` 类名、`PAICLI_*` 环境变量、`paicli.*` 系统属性、`.paicli` 数据目录、`PAI.md` 项目记忆文件和 `X-PaiCLI-API-Key` 请求头。原有数据无需搬迁。

## 从输入到结果

1. `cli/Main.java` 初始化终端、模型、工具、MCP、记忆和渲染器。CLI 命令由 `CliCommandParser` 识别；普通输入展开资源和本地路径引用后进入 Agent。
2. 默认 `agent/Agent.java` 执行 ReAct：请求模型，执行工具，将结果加入同一份 conversationHistory，再继续请求，直到回答完成。
3. `/plan` 进入 `PlanExecuteAgent`，由 `Planner` 生成 DAG 并经过用户审阅；`/team` 进入 `AgentOrchestrator`，协调 worker 和 reviewer。
4. 三条路径共享工具注册、记忆与快照服务。工具通过统一批量执行入口调度，结果保持原始顺序。
5. `llm/` 处理 provider 差异、流式响应与有限重试；`render/` 将 reasoning、正文、工具进度和状态栏呈现在终端。

## 需要重点理解的边界

| 模块 | 职责与边界 |
|---|---|
| `memory/`、`prompt/` | 长期记忆与项目规则注入；上下文压缩修改发送视图 |
| `history/` | 原始会话追加账本，与压缩后的发送视图区分 |
| `tool/`、`policy/`、`hitl/` | 工具注册、路径与命令策略、人工审批；策略拒绝不能靠审批绕过 |
| `mcp/`、`browser/`、`web/` | 外部工具和联网；执行前受当前轮策略约束 |
| `rag/` | 向量检索辅助理解代码；精确定位优先文件搜索 |
| `runtime/` | 后台任务与 HTTP API，当前 API 绑定 loopback |
| `wechat/` | iLink 消息循环，采用非交互式工具策略 |

容器/VM 沙箱、MCP OAuth、sampling 和 server 自动重启仍是后续方向。Runtime API 当前提供线程创建、任务提交与事件读取，没有现成浏览器前端。

## 构建与验证

```sh
mvn test -Pquick
mvn package
java -jar target/m-cli-1.0-SNAPSHOT.jar
```

先从 `.env.example` 创建本机 `.env` 并配置一个模型 API Key。不要将真实密钥、原始会话或构建产物提交到 Git。

## 云端运行选择

### SSH 使用 CLI

服务器安装 Java 17+，将构建后的 JAR 放在部署目录。在需要操作的项目目录启动：

```sh
java -jar /opt/m-cli/m-cli-1.0-SNAPSHOT.jar
```

当前工作目录决定 Agent 的项目边界，因此不要把包含多个无关项目或管理凭据的目录作为工作区。

### Runtime API

在服务器环境中设置模型 Key 和独立的 `PAICLI_RUNTIME_API_KEY`，从目标项目目录运行：

```sh
java -jar /opt/m-cli/m-cli-1.0-SNAPSHOT.jar serve --http --port 8080
```

API 只监听 `127.0.0.1`。可以通过 SSH 隧道访问，在本机执行（替换用户名和服务器地址）：

```sh
ssh -N -L 8080:127.0.0.1:8080 user@server
```

客户端使用 `Authorization: Bearer <API key>`；入口是 `POST /v1/threads`、`POST /v1/threads/{id}/turns`、`GET /v1/threads/{id}/events`。公网网页产品还需要前端、身份与权限设计，不能仅把现有端口暴露到公网就算完成。

### 微信通道

```sh
java -jar /opt/m-cli/m-cli-1.0-SNAPSHOT.jar wechat setup
java -jar /opt/m-cli/m-cli-1.0-SNAPSHOT.jar wechat start
```

先完成账号绑定再配置进程常驻。微信通道无终端审批面板，工具白名单与 workspace 绑定需要按实际用途配置。

## 发布前待确定

- Git 目标仓库：`https://github.com/mbky-159/M-CLI.git`。
- 云厂商、地域、预算，以及 CLI / Runtime API / 微信的实际使用方式。
- 购买后的 SSH 接入方式、部署用户和目标工作区。

本文记录代码现状和部署入口，不代表已经购买服务器、上传仓库或完成线上验收。

## 本次改名验证（2026-09-10）

`mvn package` 成功生成可运行 JAR（按项目默认设置跳过测试）。`mvn test -Pquick` 执行 864 项，855 项通过、9 项失败；将改名前 HEAD 导出到独立目录后复跑相关 6 个测试类，复现相同的 9 个失败用例，涉及图片 URI、项目作用域记忆、RAG 索引/检索、Windows 换行和代码搜索路径断言。此次改名未消除这些已有问题，也不能视为全量测试通过。尚未进行真实模型调用或云服务器验收。
