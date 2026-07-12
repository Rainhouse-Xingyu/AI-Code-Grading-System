# Ubuntu 24.04 服务器部署与压测准备

本文档用于将 AI Code Grading System 部署到 Ubuntu 24.04 服务器，并为后续压力测试做好基础准备。推荐使用 Docker Compose 部署，服务器只对外开放前端入口，其他服务端口尽量绑定到本机。

## 1. 部署架构

Compose 会启动以下服务：

| 服务 | 容器内端口 | 用途 |
| --- | ---: | --- |
| `frontend` | `80` | nginx 托管前端静态资源，并反向代理 `/api/` 到后端 |
| `backend` | `8080` | Spring Boot 后端 API |
| `model-service` | `8000` | FastAPI AI 评分 worker，消费 Redis 队列 |
| `mysql` | `3306` | MySQL 8 数据库 |
| `redis` | `6379` | Redis 7 队列与缓存 |
| `local-inference` | `8002` | 可选本地 GPU 推理服务 |

默认访问入口为：

```text
http://服务器IP:5173
```

生产或公网压测时，也可以把 `FRONTEND_PORT` 改为 `80`，通过：

```text
http://服务器IP
```

访问系统。

## 2. 服务器要求

最低建议配置：

| 场景 | CPU | 内存 | 磁盘 |
| --- | ---: | ---: | ---: |
| 基础功能验证 | 4 核 | 8 GB | 80 GB SSD |
| 中等并发压测 | 8 核 | 16 GB | 150 GB SSD |
| 大量 ZIP 上传与批量评分 | 16 核 | 32 GB | 300 GB SSD |

注意：

- 如果启用远程 DeepSeek 或学校模型网关，服务器需要能访问对应 API 地址。
- 如果启用仓库内 `local-inference`，服务器需要 NVIDIA GPU、驱动和 NVIDIA Container Toolkit。
- `STORAGE_ROOT` 建议放在容量充足的数据盘，例如 `/data/ai-grading`。

## 3. 初始化服务器

使用具有 sudo 权限的用户登录服务器：

```bash
ssh ubuntu@服务器IP
```

更新系统并安装基础工具：

```bash
sudo apt update
sudo apt -y upgrade
sudo apt -y install ca-certificates curl gnupg git jq unzip htop net-tools
```

设置时区：

```bash
sudo timedatectl set-timezone Asia/Shanghai
timedatectl
```

创建数据目录：

```bash
sudo mkdir -p /data/ai-grading
sudo chown -R "$USER:$USER" /data/ai-grading
```

## 4. 安装 Docker 与 Compose

添加 Docker 官方 apt 仓库：

```bash
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt update
sudo apt -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

如果执行 `curl https://download.docker.com/linux/ubuntu/gpg` 时出现 `curl: (35) Recv failure: Connection reset by peer`，通常是服务器到 Docker 官方域名的网络链路被重置。可以改用清华 TUNA Docker CE 镜像源：

```bash
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://mirrors.tuna.tsinghua.edu.cn/docker-ce/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://mirrors.tuna.tsinghua.edu.cn/docker-ce/linux/ubuntu \
  $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt update
sudo apt -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

允许当前用户直接运行 Docker：

```bash
sudo usermod -aG docker "$USER"
newgrp docker
```

验证安装：

```bash
docker version
docker compose version
```

## 5. 获取项目代码

### 5.1 推荐：为服务器配置长期只读 Deploy Key

如果服务器后续需要由校内工作人员长期维护，推荐使用 GitHub Deploy Key，而不是把个人 GitHub SSH 私钥放到服务器上。Deploy Key 可以只授权当前仓库，适合服务器长期拉取私有仓库代码。

在服务器上生成专用 SSH key：

```bash
mkdir -p ~/.ssh
chmod 700 ~/.ssh

ssh-keygen -t ed25519 -C "ai-code-grading-server-deploy-key" -f ~/.ssh/ai_code_grading_deploy
chmod 600 ~/.ssh/ai_code_grading_deploy
chmod 644 ~/.ssh/ai_code_grading_deploy.pub
```

查看公钥：

```bash
cat ~/.ssh/ai_code_grading_deploy.pub
```

在 GitHub 仓库中添加 Deploy Key：

1. 打开 `https://github.com/Rainhouse-Xingyu/AI-Code-Grading-System`
2. 进入 `Settings` -> `Deploy keys`
3. 点击 `Add deploy key`
4. `Title` 填写 `ai-code-grading-server`
5. `Key` 粘贴服务器上 `ai_code_grading_deploy.pub` 的内容
6. 不勾选 `Allow write access`
7. 保存

在服务器上添加 SSH 配置：

```bash
cat >> ~/.ssh/config <<'EOF'
Host github-ai-code-grading
  HostName github.com
  User git
  IdentityFile ~/.ssh/ai_code_grading_deploy
  IdentitiesOnly yes
EOF

chmod 600 ~/.ssh/config
```

首次连接 GitHub 时确认主机指纹：

```bash
ssh -T github-ai-code-grading
```

如果提示是否继续连接，输入 `yes`。看到 `successfully authenticated` 或 `does not provide shell access` 之类提示即可，GitHub 不提供 shell 登录是正常现象。

之后克隆或拉取本项目时使用这个专用 Host：

```bash
git clone git@github-ai-code-grading:Rainhouse-Xingyu/AI-Code-Grading-System.git .
```

如果项目已经克隆过，可以切换远端地址：

```bash
git remote set-url origin git@github-ai-code-grading:Rainhouse-Xingyu/AI-Code-Grading-System.git
git pull
```

交接给校内工作人员时，只需要说明：

```bash
cd /opt/ai-code-grading
git pull
docker compose up -d --build
```

不要把 `~/.ssh/ai_code_grading_deploy` 私钥复制给其他人；如果服务器更换或密钥泄露，到 GitHub 的 `Deploy keys` 页面删除旧 key，再生成新 key。

### 5.2 克隆项目

从 GitHub 克隆项目：

```bash
cd /opt
sudo mkdir -p ai-code-grading
sudo chown -R "$USER:$USER" ai-code-grading
cd ai-code-grading

git clone git@github-ai-code-grading:Rainhouse-Xingyu/AI-Code-Grading-System.git .
```

如果没有配置 Deploy Key，也可以临时使用普通 SSH 或 HTTPS：

```bash
git clone git@github.com:Rainhouse-Xingyu/AI-Code-Grading-System.git .
```

```bash
git clone https://github.com/Rainhouse-Xingyu/AI-Code-Grading-System.git .
```

## 6. 配置环境变量

复制环境变量模板：

```bash
cp .env.example .env
chmod 600 .env
```

编辑 `.env`：

```bash
nano .env
```

压测环境推荐配置示例：

```dotenv
MYSQL_DATABASE=ai_code_grading
MYSQL_USER=root
MYSQL_PASSWORD=请替换为强密码
MYSQL_PORT=127.0.0.1:3306

REDIS_PASSWORD=请替换为强密码
REDIS_PORT=127.0.0.1:6379

BACKEND_PORT=127.0.0.1:8080
MODEL_SERVICE_PORT=127.0.0.1:8000
FRONTEND_PORT=5173
STORAGE_ROOT=/data/ai-grading

JWT_SECRET=请替换为至少32位随机字符串

AI_REDIS_QUEUE=ai:grading:tasks
AI_ENABLE_REMOTE=false
AI_PROVIDER=deepseek
DEEPSEEK_API_KEY=
DEEPSEEK_BASE_URL=https://ai-gateway.neusoft.edu.cn/v1/models
DEEPSEEK_TIMEOUT_SECONDS=600
DEEPSEEK_TOKEN_QUOTA=12000000
AI_MODEL=DeepSeek/DeepSeek-R1

LOCAL_AI_BASE_URL=
LOCAL_AI_TIMEOUT_SECONDS=600
LOCAL_AI_MODEL=Qwen2.5-Coder-7B-Instruct
LOCAL_AI_API_KEY=
AI_MAX_COMPLETION_TOKENS=8192
AI_PROMPT_FULL_CODE_CHAR_LIMIT=8000
AI_PROMPT_CORE_CODE_CHAR_LIMIT=30000
AI_PROMPT_CORE_TARGET_CHARS=16000
AI_PROMPT_SUMMARY_MAX_LINES=100
```

生成随机 `JWT_SECRET`：

```bash
openssl rand -base64 48
```

AI 评分模式：

- 离线功能压测：保持 `AI_ENABLE_REMOTE=false`，系统使用确定性 fallback 评分，适合测试登录、上传、下载、数据库和队列链路。
- 远程模型压测：设置 `AI_ENABLE_REMOTE=true`，并填写 `DEEPSEEK_API_KEY`，适合测试真实 AI 评分耗时和外部网关稳定性。
- 本地兼容模型：设置 `LOCAL_AI_BASE_URL` 为 OpenAI 兼容接口，例如 `http://GPU服务器IP:8002/v1/chat/completions`。

## 7. 防火墙

只开放 SSH 和前端入口：

```bash
sudo ufw allow OpenSSH
sudo ufw allow 5173/tcp
sudo ufw enable
sudo ufw status
```

如果将 `FRONTEND_PORT=80`，改为：

```bash
sudo ufw allow 80/tcp
```

不要向公网开放 MySQL、Redis、后端和 worker 端口。上面的 `.env` 已经将它们绑定到 `127.0.0.1`。

## 8. 启动服务

首次构建并启动：

```bash
docker compose up -d --build
```

查看服务状态：

```bash
docker compose ps
```

查看启动日志：

```bash
docker compose logs -f --tail=200
```

单独查看某个服务日志：

```bash
docker compose logs -f --tail=200 backend
docker compose logs -f --tail=200 model-service
docker compose logs -f --tail=200 mysql
docker compose logs -f --tail=200 redis
docker compose logs -f --tail=200 frontend
```

## 9. 部署验证

检查前端：

```bash
curl -I http://127.0.0.1:5173
```

检查后端 API 代理：

```bash
curl -i http://127.0.0.1:5173/api/v1/auth/me
```

如果 `.env` 中修改了 `FRONTEND_PORT`，把上面命令里的 `5173` 替换为实际端口。未登录时返回 401 属于正常现象，说明请求已经到达后端。

检查容器健康状态：

```bash
docker compose ps
```

检查 MySQL：

```bash
docker compose exec mysql sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW DATABASES;"'
```

检查 Redis：

```bash
docker compose exec redis sh -lc 'redis-cli -a "$REDIS_PASSWORD" ping'
```

浏览器访问：

```text
http://服务器IP:5173
```

## 10. 更新部署

拉取最新代码并重新构建：

```bash
cd /opt/ai-code-grading
git pull
docker compose up -d --build
docker compose ps
```

如果只改了 `.env`，通常无需重新构建：

```bash
docker compose up -d
```

## 11. 压测前系统调优

提高文件句柄限制：

```bash
ulimit -n 65535
```

持久化 sysctl 参数：

```bash
sudo tee /etc/sysctl.d/99-ai-code-grading-loadtest.conf > /dev/null <<'EOF'
fs.file-max = 1000000
net.core.somaxconn = 65535
net.ipv4.ip_local_port_range = 1024 65535
net.ipv4.tcp_tw_reuse = 1
EOF

sudo sysctl --system
```

如果服务器内存较小，建议开启 swap：

```bash
sudo fallocate -l 8G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h
```

压测期间观察资源：

```bash
htop
docker stats
df -h
free -h
```

## 12. 压测建议

推荐先分层压测：

1. 登录、查询类 API：验证后端、数据库和鉴权性能。
2. ZIP 上传接口：验证 nginx 上传限制、后端文件落盘、数据库写入。
3. 批量评分接口：验证 Redis 队列、`model-service` worker 和外部 AI 网关。
4. PDF/ZIP 下载接口：验证文件读取、打包和网络吞吐。

安装常用压测工具：

```bash
sudo apt -y install wrk apache2-utils
```

简单探活压测：

```bash
wrk -t4 -c100 -d60s http://127.0.0.1:5173/
```

带登录态的 API 压测需要先在浏览器或接口中获取 JWT，然后添加请求头：

```bash
wrk -t4 -c100 -d60s \
  -H "Authorization: Bearer 替换为JWT" \
  http://127.0.0.1:5173/api/v1/assignments
```

如果 `.env` 中修改了 `FRONTEND_PORT`，同步替换压测 URL 中的端口。

上传类接口建议使用 k6、JMeter 或 Locust，因为它们更适合构造 multipart 表单和业务流程。

压测注意事项：

- 不要一开始就压真实 AI 评分接口，先用 `AI_ENABLE_REMOTE=false` 建立系统基线。
- 真实 AI 评分的瓶颈通常在外部模型网关、单任务耗时和 token 配额，不一定反映后端基础吞吐。
- 上传 ZIP 和导出 PDF 会显著增加磁盘 IO，压测时要同时观察 `df -h` 和 `docker stats`。
- 每轮压测前记录代码版本、`.env`、并发数、持续时间、测试数据规模和服务器配置。

## 13. 可选：启用本地 GPU 推理

安装 NVIDIA 驱动后，验证：

```bash
nvidia-smi
```

安装 NVIDIA Container Toolkit 后，验证 Docker 能看到 GPU：

```bash
docker run --rm --gpus all nvidia/cuda:12.4.1-base-ubuntu22.04 nvidia-smi
```

配置 `.env`：

```dotenv
AI_PROVIDER=local
AI_ENABLE_REMOTE=false
LOCAL_AI_BASE_URL=http://local-inference:8002/v1/chat/completions
LOCAL_AI_MODEL=Qwen2.5-Coder-7B-Instruct
LOCAL_INFERENCE_MODEL_PATH=/root/.cache/huggingface/hub/你的模型目录
LOCAL_INFERENCE_SERVED_MODEL_NAME=Qwen2.5-Coder-7B-Instruct
```

启动：

```bash
docker compose --profile local-inference up -d --build
```

注意：本地 GPU 推理镜像和模型下载会占用较多磁盘与网络时间，建议先完成普通 Compose 部署验证后再启用。

## 14. 数据备份与清理

备份 MySQL：

```bash
mkdir -p backups
docker compose exec mysql sh -lc 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' > backups/ai_code_grading_$(date +%F_%H%M%S).sql
```

备份上传文件：

```bash
tar -czf backups/storage_$(date +%F_%H%M%S).tar.gz -C /data ai-grading
```

停止服务：

```bash
docker compose down
```

停止并删除数据库、Redis 等命名卷：

```bash
docker compose down -v
```

`docker compose down -v` 会删除 MySQL 和 Redis 数据卷，压测重置环境时才使用。

## 15. 常见问题

### 端口被占用

查看占用：

```bash
sudo ss -lntp
```

修改 `.env` 中的 `FRONTEND_PORT`、`BACKEND_PORT` 等端口后重启：

```bash
docker compose up -d
```

### 前端能打开，但 API 失败

查看后端日志：

```bash
docker compose logs -f --tail=200 backend
```

确认 `frontend/nginx.conf` 中 `/api/` 会代理到 Compose 内部的 `backend:8080`，因此浏览器不需要直接访问宿主机 `8080`。

### MySQL 初始化数据没有变化

MySQL 只会在第一次创建数据卷时执行 `docker-entrypoint-initdb.d` 下的初始化 SQL。需要重新初始化时：

```bash
docker compose down -v
docker compose up -d --build
```

这会清空数据库数据。

### AI 评分一直没有结果

查看 worker：

```bash
docker compose logs -f --tail=200 model-service
```

确认 Redis 队列配置一致：

```bash
grep AI_REDIS_QUEUE .env
```

如果使用远程模型，确认：

```bash
grep -E 'AI_ENABLE_REMOTE|DEEPSEEK_API_KEY|DEEPSEEK_BASE_URL' .env
```

### 上传失败或大文件失败

当前前端 nginx 配置 `client_max_body_size` 为 `55m`，后端 multipart 限制为 `50MB`。如果压测需要更大 ZIP，需要同步调整：

- `frontend/nginx.conf` 的 `client_max_body_size`
- `backend/src/main/resources/application.properties` 的 `spring.servlet.multipart.max-file-size`
- `backend/src/main/resources/application.properties` 的 `spring.servlet.multipart.max-request-size`

调整后重新构建：

```bash
docker compose up -d --build
```
