# M-CLI 云端 CLI 与租户沙箱建设

状态：部署基础准备阶段；未购买服务器，未上线多用户 API，未完成 Linux/gVisor 运行验收。

## 产品范围与预算

用户在自己的终端连接云端 API。云端为每个用户提供独立 workspace、HOME、会话和执行环境；不要求每个注册用户都有永久运行的容器。空闲释放算力，持久工作区保留，超过活跃配额的任务排队。暂不部署另一个简历项目。

预算为约 100 元/月，先使用三个月。服务器、公网网络、云盘、备份、对象存储应合计核价；模型 API 是另一项可变成本，必须设置用户与平台额度。

## 阿里云选型建议

优先核价 Linux x86_64 ECS，4 vCPU / 8 GiB、40 GiB 系统盘 + 100 GiB 数据盘、公网 IPv4。操作系统候选 Ubuntu 24.04 LTS。这是容量目标，不是已核实的优惠套餐或可用性承诺。

具体核价候选为经济型 `ecs.e-c1m2.xlarge`（4 vCPU / 8 GiB）；若同配置独享或通用型活动实付更低，再比较替换。经济型存在共享 CPU 性能波动，适合初期小范围实验，不据此承诺并发性能。购买前同时比较 4 核 8 GiB 轻量套餐的磁盘扩展与 runtime 验证条件。

ECS 便于后续扩展数据盘、划分网络和增加独立 worker。若预算内只有轻量服务器满足内存要求，也应先试装 Docker/runsc、验证内核与资源限制后再决定。不要为了数量更大的 CPU 牺牲内存。普通云主机优先验证 gVisor systrap，不把嵌套 KVM 可用性作为前提。

地域待确认：优先结合目标用户、ICP备案安排和模型 API/镜像仓库连通性选。购买前记录实例规格完整名称、地域、系统盘和数据盘、带宽/流量、三个月实付、续费价与扩容方式；实际总价超过 300 元时重新比较套餐，不自动增加预算。

8 GiB 起步预算（估算，需要压测）：

| 用途 | 预留内存 |
|---|---|
| Linux、Docker、系统服务 | 1 GiB |
| API、鉴权、调度与模型代理 | 0.75 GiB |
| MySQL | 1 GiB |
| Redis | 0.25 GiB |
| 对象存储服务（若自建） | 0.75 GiB |
| 活跃沙箱 | 初始 1 × 1.5 GiB；验收后尝试 2 × 1.5 GiB |
| 余量 | 两个沙箱时仍约 1.25 GiB |

JVM、Maven 编译、浏览器进程和 gVisor 都可能突破估算。并发上限由 admission control 强制执行，不能仅写进文档。Compose 实验配置只限制单容器，不实现全局调度；管理员不要手工无限创建实例。初版不开放浏览器自动化和用户自定义 MCP。

数据盘规划：workspace 40 GiB、数据库 15 GiB、对象与导出 15 GiB、镜像/构建缓存 15 GiB、可用余量 15 GiB。镜像目录需在安装阶段配置到数据盘，否则默认 Docker 数据仍在系统盘。租户工作区起步建议 2 GiB 硬配额，必须使用经验证的文件系统配额或限定大小的独立卷；目录命名不等于磁盘配额。临时实验使用有尺寸上限的 tmpfs，消耗容器内存，不能用于保存用户代码。备份放在异机或对象存储，不能只放同一块盘。

## 现有代码与差距

| 现有位置 | 已核实行为 | 云端版本要补齐 |
|---|---|---|
| `RuntimeApiServer` | 单个共享 Key；loopback HTTP | 每用户可撤销凭据、HTTPS 网关、限流 |
| `RuntimeThreadStore` | 只有 thread id，无 owner | 所有 thread/turn/event/artifact 都关联用户并检查归属 |
| `Main.runHeadlessTask` | 每次新建 Agent，统一 cwd/HOME | worker 在独立沙箱进程中运行，持久会话恢复 |
| `TaskRunner` | 仅接收 prompt 字符串 | 类型化用户、会话、任务、配额上下文 |
| API executor | cached thread pool，无全局队列上限 | 有界队列、每用户串行、总并发限制、拒绝/取消 |
| API events | 请求时读出已有事件，最终回答写成 delta | 真正流式输出或有界轮询、分页、断线续读 |
| ToolRegistry/PathGuard | 路径与命令策略 | OS 级沙箱、网络与资源限制，不能互相替代 |
| 本地 SQLite | 会话/任务持久化 | 先定义控制面存储边界，再迁移必要元数据到 MySQL |

不要将现有单 Key API 绑定 `0.0.0.0` 后称为云端多租户服务；不要在共享 JVM 中通过修改 `user.dir` / `user.home` 模拟每用户隔离。

## 目标执行链路

```text
本地 CLI（登录、会话、任务、取消、事件重连）
  → HTTPS API（认证、归属检查、请求大小限制、额度）
  → 调度器（每用户互斥、有界队列、租约、取消、回收）
  → 受信任沙箱管理进程（固定模板，不接受任意 Docker 参数）
  → gVisor worker（独立 workspace/HOME，受限资源）
  → 受控模型代理 / 经授权的网络出口
```

公网 API 和用户容器不得挂 Docker socket。只有受信任的管理进程控制 Docker，接口只接受服务端生成的标识与固定策略，不接受用户提供的宿主机路径、镜像、启动参数或容器名。控制平面与 worker 后续可拆成不同机器。

租户 ID 来自认证结果，不相信请求体里的 `user_id`。workspace、历史、记忆、向量索引、日志、临时目录和对象存储键必须一起隔离；检查到跨用户资源时返回无泄漏的拒绝响应。用户 workspace 的持久化与清除策略要独立于容器生命周期。

模型服务的主 Key 保存在代理端，禁止注入可执行任意代码的沙箱。沙箱只能获得短时、绑定任务和额度的代理凭据。网络默认拒绝；开放出口前验证 DNS、IPv4/IPv6、重定向、云元数据和内部地址限制，禁止访问控制面数据库、Redis、Docker API 和其他租户。不能仅靠 `HTTP_PROXY` 环境变量，因为用户代码能绕过它。

当前离线实验全部断网，因此暂不能调用模型或在线下载依赖。这是实验限制，不是云端执行链路已经完成。

## 中间件准备

- MySQL：拟保存用户、token 哈希、workspace 归属、任务状态和账单元数据。当前配置仅供实验，尚未接入 Java。
- Redis：拟用于短时状态、限流和调度协调；不能作为任务唯一持久记录。先按需启动。
- 对象存储：保存上传、导出、备份和任务产物，全部私有，通过服务端授权签发短时下载链接。小型部署可以评估同地域 OSS 以减少常驻内存，S3 兼容差异需做 SDK 验证。
- MinIO 官方开源仓库截至本次核查已归档，先不添加旧镜像作为生产默认。保留对象存储接口与容量，候选方案确定后再接入。

## 实现顺序与验收条件

1. **基础准备（进行中）**：沙箱镜像、离线 Compose 配置、可选 MySQL/Redis、静态策略测试、Linux 运行探针，以及带健康检查和回滚的单用户预发布流水线。尚未运行验证的部分不能标记为已交付沙箱服务。
2. **租户控制面**：服务端发放/撤销用户 token，存储哈希；所有资源归属校验；请求体/队列/分页上限；增加用户 A 无法查询或执行用户 B 任务的接口测试。
3. **worker 接入**：类型化任务协议、独立持久卷、会话恢复、runsc 强制启动、网络出口代理、租约、超时取消与宕机回收；任何沙箱启动失败都必须失败关闭，不回退宿主机执行。
4. **CLI 接入**：云端登录、创建 workspace、提交任务、输出事件与重连、取消、上传/下载；第一版使用邀请制，先跑通一个用户完整链路再做跨用户测试。
5. **发布链路**：PR 校验 → master 合并 → 不可变镜像 → 受控部署 → 健康检查 → 失败回滚。控制面与 worker 分别发布；更新 worker 先停止接新任务，等待或终止在途任务，禁止直接删用户卷。数据库采用向前兼容迁移。
6. **开放前验收**：跨租户文件/会话/对象访问、网络逃逸、进程/内存/磁盘耗尽、队列拥塞、Key 泄漏、超时后子进程残留、主机重启恢复、备份恢复与发布回滚。单次 smoke 通过不能替代这些测试。

## 验证与采购状态

本地 Docker Compose 可以渲染；本机 Docker daemon 未启动，尚未构建沙箱镜像或执行 gVisor 探针。已购买新加坡轻量应用服务器（Ubuntu 24.04、4 vCPU、8 GiB、70 GiB ESSD），但 SSH 密钥登录尚未配置，服务器初始化和运行验收未执行。没有创建公共业务入口或开放 Runtime API 端口。准备文件的使用方式见 [部署实验说明](../deploy/README.md)。

## 核查来源（2026-09-10）

- [阿里云实例定价入口](https://ecs-buy.aliyun.com/price)：实际价格还需要账号、地域和套餐核价。
- [ECS 规格表](https://help.aliyun.com/zh/ecs/user-guide/overview-of-instance-families)与[共享型说明](https://help.aliyun.com/zh/ecs/user-guide/shared-instance-families)：核对候选规格与性能边界。
- [gVisor platforms](https://gvisor.dev/docs/user_guide/platforms/)：云主机优先考虑 systrap，KVM 依赖和嵌套虚拟化边界。
- [Docker 资源限制](https://docs.docker.com/engine/containers/resource_constraints/)与[安全模型](https://docs.docker.com/engine/security/)：限制默认不自动生效，daemon 必须限制访问。
- [MinIO 官方仓库](https://github.com/minio/minio)：已归档、不再维护的声明。
- [OSS S3 兼容范围](https://help.aliyun.com/zh/oss/developer-reference/compatibility-with-amazon-s3)：不是所有行为都完全一致。
