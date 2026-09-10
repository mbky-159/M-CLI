# 云端部署实验准备

这是一套管理员使用的离线沙箱实验环境，不是可直接上线的多用户 M-CLI 服务。架构、采购容量与后续接入见 [云端 CLI 设计](../docs/cloud-cli-design.md)。

## 不启动容器的检查

需要 Python 3 和 Docker Compose：

```sh
python deploy/check_sandbox.py
python -m unittest discover -s deploy -p test_sandbox_policy.py -v
```

只验证渲染后的配置与策略退化场景，不需要 Docker daemon，不代表 Linux 隔离运行通过。

## Linux 沙箱实验

准备 Linux Docker Engine 与 [gVisor runsc](https://gvisor.dev/docs/user_guide/quick_start/docker/)，按官方流程安装并注册 runtime。云主机优先验证默认 systrap。不要覆盖已有 daemon 配置；如果需要重启 Docker，先安排已有服务维护窗口。

从仓库根目录执行：

```sh
docker info --format '{{json .Runtimes}}'
docker compose -f deploy/compose.yaml --profile sandbox build sandbox
python deploy/check_sandbox.py --run
```

必须包含 `runsc`；缺失时探针报错，不回退到 runc。探针创建独有容器名、超时后仅清理自身容器，检查 UID、根目录只读、loopback-only 网络、临时目录可写和无 API Key。首次拉取镜像、apt 下载需要宿主机网络。

手工进入一个新的离线实验环境：

```sh
docker compose -f deploy/compose.yaml run --rm sandbox
```

沙箱为非 root、1 CPU、1.5 GiB 内存（禁止额外 swap）、128 进程；workspace/HOME/tmp 使用有尺寸上限的 tmpfs。退出后数据消失。没有宿主机目录挂载、Docker socket、模型 Key 或网络，不要将它用作持久用户工作区。M-CLI JAR 和云端任务协议尚未接入该镜像；当前镜像提供 Java、Maven、Git 和 Python 用于验证执行环境。限制内的 tmpfs 文件占用也计入内存预算。

## 可选中间件

当前 M-CLI 使用 SQLite，这些服务尚未被 Java 代码引用。只有进入中间件开发阶段才运行：

```sh
python deploy/init_secrets.py
docker compose -f deploy/compose.yaml --profile middleware up -d mysql redis
docker compose -f deploy/compose.yaml ps
```

凭据随机生成到被 Git 忽略的 `deploy/secrets/`，不会打印或覆盖已有值。Linux 目录权限为 0700；文件 0644 便于容器服务 UID 读取，以 Compose secrets 只读挂入指定容器。Windows 权限需要管理员确认，本配置面向 Linux 部署。轮换密码必须同时更新数据库/Redis，不是重新运行初始化脚本。

MySQL/Redis 仅在 internal 网络中互通，不发布宿主机端口。数据在命名卷中，部署时确认 Docker data-root 位于预算内的数据盘。不要把沙箱连入 backend 网络。停止服务用 `docker compose -f deploy/compose.yaml --profile middleware stop`，不使用 `down -v` 删除数据库。

MinIO 仅预留需求，不启动已归档项目的旧镜像。对象存储候选与原因见设计文档。

## 发布前仍需完成

- 在目标 ECS 上构建镜像并执行探针，确认 runsc、cgroup 限额和临时存储兼容性。
- 当前镜像标签用于实验；上线前选择维护版本、固定已扫描镜像 digest 并建立更新流程。
- 完成用户鉴权、资源归属、磁盘硬配额、受控网络、模型代理、任务调度和完整隔离测试。
- 配置实际部署流水线的镜像仓库与服务器凭据；本次 CI 仅校验与构建实验镜像，不部署任何服务器。

## 单用户预发布与自动发布

当前预发布服务仍使用单个 API Key，并固定监听服务器的 `127.0.0.1:8080`。初始化 Ubuntu 24.04 服务器：

```sh
git clone https://github.com/mbky-159/M-CLI.git
cd M-CLI
sudo bash deploy/server/bootstrap-ubuntu.sh
sudoedit /etc/m-cli/m-cli.env
```

在环境文件中配置一个模型 Key，并将 `PAICLI_RUNTIME_API_KEY` 换成随机长值。启动前仍需先发布一个 JAR；发布脚本校验 SHA-256、创建不可变 release 目录、切换 `current` 符号链接并请求 `GET /healthz`，失败时恢复上一版本。

GitHub 仓库的 `staging` Environment 需要以下 Secrets：

- `STAGING_HOST`：服务器公网 IP 或域名。
- `STAGING_USER`：固定填写初始化脚本创建的 `mcli-deploy`；该用户只允许通过 `sudo` 调用服务器上由 root 安装的发布脚本。
- `STAGING_SSH_PRIVATE_KEY`：专用部署密钥，不复用个人管理密钥。
- `STAGING_SSH_KNOWN_HOSTS`：管理员在可信渠道核对后的服务器 host key 记录。

确认服务器初始化和 Secrets 均完成后，再将仓库变量 `STAGING_DEPLOY_ENABLED` 设为 `true`。此前每次推送只构建和上传 JAR artifact，部署 job 会跳过。合并到 `master` 后同一时间只运行一个发布，健康检查失败自动回滚。

本机通过 SSH 隧道访问预发布 API：

```sh
ssh -N -L 8080:127.0.0.1:8080 <admin-user>@<server-ip>
curl http://127.0.0.1:8080/healthz
```

不要为此在轻量服务器防火墙开放 8080。域名、HTTPS、多用户 token 和租户沙箱调度完成前，不开放公网 API。
