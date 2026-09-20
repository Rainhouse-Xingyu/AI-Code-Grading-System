# Windows 电脑本地部署指南

本文档用于在 Windows 电脑上本地部署“AI Code Grading System”。尽量按第一次部署、命令不熟悉的情况来写：照着复制命令执行即可。

推荐使用 Docker Desktop 部署。这样不需要分别安装 Java、Node.js、Python、MySQL、Redis，Docker 会把这些服务一起启动起来。

## 1. 部署后会启动哪些服务

本系统本地部署后会启动 5 个主要服务：

| 服务 | 作用 | 默认访问 |
| --- | --- | --- |
| frontend | 前端页面 | `http://localhost:5173` |
| backend | 后端接口 | `http://localhost:8080` |
| model-service | AI 评分任务 worker | `http://localhost:8000/health` |
| mysql | 数据库 | 本机 `3306` |
| redis | AI 任务队列 | 本机 `6379` |

平时老师只需要打开：

```text
http://localhost:5173
```

## 2. 电脑准备

### 2.1 安装 Docker Desktop

1. 打开 Docker Desktop 官网下载安装包：
   `https://www.docker.com/products/docker-desktop/`
2. 安装时保持默认选项即可。
3. 安装完成后重启电脑。
4. 打开 Docker Desktop，等待左下角显示 Docker 正在运行。

如果 Docker Desktop 提示需要 WSL 2，按提示安装即可。Windows 10/11 通常会自动引导安装。

### 2.2 安装 Git

1. 打开 Git 官网：
   `https://git-scm.com/download/win`
2. 下载 Windows 版本并安装。
3. 安装时一路默认即可。

### 2.3 打开 PowerShell

后续命令都在 PowerShell 里执行。

打开方式：

1. 按 `Win` 键。
2. 搜索 `PowerShell`。
3. 点击打开。

不要使用 Word 或记事本执行命令，命令要输入到 PowerShell 窗口。

## 3. 获取项目代码

选择一个目录放代码，例如 `D:\AI-Code-Grading-System`。

在 PowerShell 中执行：

```powershell
cd D:\
git clone 项目仓库地址 AI-Code-Grading-System
cd D:\AI-Code-Grading-System
```

如果已经拿到了项目压缩包，也可以直接解压到：

```text
D:\AI-Code-Grading-System
```

然后在 PowerShell 中进入目录：

```powershell
cd D:\AI-Code-Grading-System
```

确认目录正确：

```powershell
dir
```

能看到这些文件或文件夹就说明位置对了：

```text
backend
frontend
model-service
docker-compose.yml
.env.example
```

## 4. 创建本地配置文件

项目根目录里有一个 `.env.example`，需要复制成 `.env`。

在 PowerShell 中执行：

```powershell
Copy-Item .env.example .env
```

然后用记事本打开 `.env`：

```powershell
notepad .env
```

建议先把以下几项改成适合 Windows 本机的值。

### 4.1 推荐的基础配置

可以先按下面这样配置：

```dotenv
COMPOSE_PROJECT_NAME=ai-code-grading

MYSQL_DATABASE=ai_code_grading
MYSQL_USER=root
MYSQL_PASSWORD=NeusoftAICode123
MYSQL_PORT=3306

REDIS_PORT=6379
REDIS_PASSWORD=NeusoftAICode123

BACKEND_PORT=8080
MODEL_SERVICE_PORT=8000
LOCAL_INFERENCE_PORT=8002
FRONTEND_PORT=5173
STORAGE_ROOT=D:/ai-grading

JWT_SECRET=change-me-change-me-change-me-change-me
ID_CARD_ENCRYPTION_KEY=change-me-id-card-secret-change-me
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
LOCAL_AI_MODEL=deepseek-r1:14b
LOCAL_AI_API_KEY=

AI_MAX_COMPLETION_TOKENS=8192
AI_PROMPT_FULL_CODE_CHAR_LIMIT=8000
AI_PROMPT_CORE_CODE_CHAR_LIMIT=30000
AI_PROMPT_CORE_TARGET_CHARS=16000
AI_PROMPT_SUMMARY_MAX_LINES=100
```

说明：

- `COMPOSE_PROJECT_NAME=ai-code-grading` 用于固定 Docker Compose 生成的镜像和容器前缀。使用离线镜像包时不要改这一项，否则 Compose 可能找不到已经导入的镜像。
- `STORAGE_ROOT=D:/ai-grading` 是学生提交 ZIP、报告等文件的保存目录。
- `AI_ENABLE_REMOTE=false` 表示先不用真实 AI，系统会使用兜底评分，适合先验证系统功能。
- 等系统能正常打开、登录、提交作业后，再接学校 AI 或老师本机 Ollama。

保存记事本后关闭。

### 4.2 创建文件存储目录

在 PowerShell 中执行：

```powershell
New-Item -ItemType Directory -Force D:\ai-grading
```

## 5. 第一次启动系统

确保 Docker Desktop 已经打开并运行。

### 5.1 使用离线镜像包启动

如果拿到的是项目提供的离线镜像包，例如：

```text
ai-code-grading-images-amd64.tar
```

先把这个文件放到项目根目录：

```text
D:\AI-Code-Grading-System\ai-code-grading-images-amd64.tar
```

然后在项目根目录执行：

```powershell
docker load -i ai-code-grading-images-amd64.tar
```

加载完成后检查镜像：

```powershell
docker images
```

应该能看到下面这些镜像：

```text
mysql                         8.0
redis                         7-alpine
ai-code-grading-backend        latest
ai-code-grading-model-service  latest
ai-code-grading-frontend       latest
```

确认 `.env` 里有：

```dotenv
COMPOSE_PROJECT_NAME=ai-code-grading
```

然后启动系统：

```powershell
docker compose up -d --no-build --pull never
```

注意：离线镜像包启动时不要加 `--build`。加了 `--build` 之后，Docker 会重新构建后端、前端和 AI worker，可能又去联网下载依赖。

### 5.2 在线下载并构建启动

如果 Windows 电脑可以正常访问 Docker Hub 和 Maven、npm、pip 等依赖源，也可以直接在线构建。

在项目根目录执行：

```powershell
docker compose up -d --build
```

第一次启动会比较慢，因为要下载 MySQL、Redis、构建后端、前端和 Python 服务镜像。根据网络情况可能需要几分钟到十几分钟。

启动完成后查看状态：

```powershell
docker compose ps
```

正常情况下应该看到这些服务都是 `Up`：

```text
mysql
redis
backend
model-service
frontend
```

## 6. 打开系统

浏览器打开：

```text
http://localhost:5173
```

首次初始化 SQL 中带了演示账号：

| 角色 | 账号 | 密码 |
| --- | --- | --- |
| 管理员 | `admin` | `12345` |
| 教师 | `t001` | `Pass12345` |
| 教师 | `t002` | `Pass12345` |
| 教师 | `t003` | `Pass12345` |
| 学生 | `s001` | `Stu123456` |

建议第一次登录后尽快在页面右上角“个人设置”里修改密码。新密码至少 8 位，并且需要同时包含字母和数字。

## 7. 常用 Docker 命令

这些命令都在项目根目录执行。

### 7.1 查看服务状态

```powershell
docker compose ps
```

### 7.2 查看全部日志

```powershell
docker compose logs -f --tail=200
```

按 `Ctrl + C` 可以退出日志查看，不会停止系统。

### 7.3 只看后端日志

```powershell
docker compose logs -f --tail=200 backend
```

### 7.4 只看 AI worker 日志

```powershell
docker compose logs -f --tail=200 model-service
```

### 7.5 停止系统

```powershell
docker compose down
```

### 7.6 重新启动系统

```powershell
docker compose up -d
```

### 7.7 修改 `.env` 后重启

修改 `.env` 之后，如果是在线构建部署，建议执行：

```powershell
docker compose up -d --build
```

如果是离线镜像包部署，或者只是改了 AI 地址、密码、端口等配置，执行：

```powershell
docker compose up -d --force-recreate
```

### 7.8 代码更新后同步系统

代码更新后，不能只重启容器。后端、前端和 AI worker 的代码都已经打进 Docker 镜像里，需要根据部署方式选择同步方法。

#### 7.8.1 Windows 电脑可以联网

如果 Windows 电脑可以访问 Git 仓库、Docker Hub、Maven、npm 和 pip 依赖源，在项目根目录执行：

```powershell
cd D:\AI-Code-Grading-System
git pull
docker compose up -d --build
```

如果项目不是用 Git 获取的，而是拿到新的压缩包：

1. 先停止系统：

   ```powershell
   docker compose down
   ```

2. 用新代码覆盖项目目录，但保留自己的 `.env` 文件。
3. 回到项目根目录重新构建并启动：

   ```powershell
   docker compose up -d --build
   ```

#### 7.8.2 Windows 电脑离线

如果 Windows 电脑离线，代码更新后需要由能联网、能构建镜像的电脑重新制作离线镜像包。只拷贝新版代码到 Windows 不够，因为容器仍然会使用旧镜像。

在 Mac 制作新版离线包：

```bash
cd "/Users/xingyuzhao/Code/Neusoft/AI Code Grading System"

export COMPOSE_PROJECT_NAME=ai-code-grading
export DOCKER_DEFAULT_PLATFORM=linux/amd64

docker pull --platform linux/amd64 mysql:8.0
docker pull --platform linux/amd64 redis:7-alpine
docker compose build backend model-service frontend

docker save -o ai-code-grading-images-amd64.tar \
  mysql:8.0 \
  redis:7-alpine \
  ai-code-grading-backend:latest \
  ai-code-grading-model-service:latest \
  ai-code-grading-frontend:latest
```

然后把下面两样一起拷到 Windows：

- 新版项目代码。
- 新版 `ai-code-grading-images-amd64.tar`。

在 Windows 项目根目录执行：

```powershell
cd D:\AI-Code-Grading-System

docker compose down
docker load -i .\ai-code-grading-images-amd64.tar
docker compose up -d --no-build --pull never --force-recreate
```

确认服务已经使用新版镜像：

```powershell
docker compose ps
docker compose logs --tail=100 backend
docker compose logs --tail=100 model-service
```

注意事项：

- `.env` 是本机配置文件，更新代码时不要直接用别人的 `.env` 覆盖。
- `D:\ai-grading` 是上传文件和报告存储目录，更新代码时不要删除。
- MySQL 数据在 Docker volume 里，普通更新不要执行 `docker compose down -v`。
- `deploy/mysql/ai_code_grading_full_with_test_data.sql` 只会在第一次创建 MySQL 数据卷时自动执行；已有数据库不会因为更新代码自动重新导入初始化 SQL。
- 如果这次更新包含数据库结构变更，需要单独执行对应 SQL 升级脚本，或者在确认可以清空数据的演示环境中再使用 `docker compose down -v` 重置数据库。

## 8. 接入学校 AI 网关

如果要使用学校 AI 网关真实评分，修改 `.env`：

```dotenv
AI_PROVIDER=deepseek
AI_ENABLE_REMOTE=true
DEEPSEEK_API_KEY=这里填写学校网关的APIKey
DEEPSEEK_BASE_URL=https://ai-gateway.neusoft.edu.cn/v1/models
AI_MODEL=DeepSeek/DeepSeek-R1
```

保存后重启：

```powershell
docker compose up -d --force-recreate
```

检查 `model-service` 是否读取到配置：

```powershell
curl http://localhost:8000/health
```

正常会看到类似：

```json
{
  "status": "ok",
  "provider": "deepseek",
  "remote_enabled": true,
  "deepseek_configured": true
}
```

注意：不要把真实 API Key 发到群里或写进公开文档。

## 9. 接入本机 Ollama

如果老师电脑上要跑本地 Ollama 模型，推荐先确认 Ollama 本身可用，再把系统接进去。

### 9.1 安装 Ollama

打开官网下载安装：

```text
https://ollama.com/download/windows
```

安装后打开 PowerShell，检查版本：

```powershell
ollama --version
```

### 9.2 下载模型

例如使用 `deepseek-r1:14b`：

```powershell
ollama pull deepseek-r1:14b
```

模型比较大，下载时间取决于网络速度。

查看已有模型：

```powershell
ollama list
```

### 9.3 启动 Ollama

如果只是本机 Docker 容器访问 Ollama，通常 Docker 可以通过 `host.docker.internal` 访问 Windows 宿主机。

先确认 Windows 本机可以访问：

```powershell
curl http://127.0.0.1:11434/api/tags
```

如果能看到模型列表，说明 Ollama 本机正常。

然后修改项目 `.env`：

```dotenv
AI_PROVIDER=local
AI_ENABLE_REMOTE=false
LOCAL_AI_BASE_URL=http://host.docker.internal:11434/v1/chat/completions
LOCAL_AI_MODEL=deepseek-r1:14b
LOCAL_AI_API_KEY=
```

保存后重启系统：

```powershell
docker compose up -d --force-recreate
```

检查容器里的配置：

```powershell
docker compose exec model-service printenv LOCAL_AI_BASE_URL
```

应该输出：

```text
http://host.docker.internal:11434/v1/chat/completions
```

### 9.4 测试 Docker 容器能否访问 Ollama

执行：

```powershell
docker compose exec model-service python -c "import urllib.request; print(urllib.request.urlopen('http://host.docker.internal:11434/api/tags', timeout=10).read().decode()[:500])"
```

如果输出模型列表，说明 Docker 容器能访问老师电脑上的 Ollama。

### 9.5 如果其他电脑也要访问电脑 Ollama

如果不是本机 Docker 访问，而是让另一台服务器访问电脑 Ollama，需要让 Ollama 监听局域网。

PowerShell 中执行：

```powershell
$env:OLLAMA_HOST="0.0.0.0:11434"
ollama serve
```

然后在防火墙允许 `11434` 端口。

另一台电脑测试：

```powershell
curl http://电脑IP:11434/api/tags
```

如果连接失败，通常是：

- 电脑 IP 写错了。
- Ollama 没启动。
- Ollama 只监听了 `127.0.0.1`。
- Windows 防火墙拦截了 `11434`。
- 两台电脑不在同一网络或 VPN。

## 10. 学生从其他电脑访问本机系统

如果只在老师电脑自己使用，访问：

```text
http://localhost:5173
```

如果学生电脑也要访问电脑上的系统，需要：

1. 电脑和学生电脑在同一个局域网。
2. 查电脑 IP。
3. Windows 防火墙放行前端端口 `5173`。
4. 学生浏览器访问 `http://老师电脑IP:5173`。

查看电脑 IP：

```powershell
ipconfig
```

找到当前联网网卡的 `IPv4 地址`，例如：

```text
192.168.1.20
```

学生访问：

```text
http://192.168.1.20:5173
```

如果打不开，先在电脑确认：

```powershell
curl http://localhost:5173
```

老师电脑本机能打开，但学生打不开，通常是防火墙或网络隔离问题。

## 11. 重新初始化数据库

如果想清空所有演示数据、重新从初始化 SQL 开始，需要删除 Docker 数据卷。

注意：这会删除数据库数据，包括账号、作业、提交记录、评分报告。

执行：

```powershell
docker compose down -v
docker compose up -d --build
```

如果只是停止系统，不要加 `-v`：

```powershell
docker compose down
```

## 12. 更新项目代码

如果代码来自 Git 仓库，更新方式：

```powershell
cd D:\AI-Code-Grading-System
git pull
docker compose up -d --build
```

如果使用的是离线镜像包，不更新镜像包只更新代码不会改变容器里的程序。需要拿到新的离线镜像包后重新加载：

```powershell
docker compose down
docker load -i ai-code-grading-images-amd64.tar
docker compose up -d --no-build --pull never
```

如果代码来自压缩包：

1. 先执行：

```powershell
docker compose down
```

2. 备份旧目录里的 `.env`。
3. 解压新代码。
4. 把 `.env` 放回新目录。
5. 执行：

```powershell
docker compose up -d --build
```

## 13. 常见问题

### 13.1 `docker` 不是内部或外部命令

说明 Docker Desktop 没安装好，或者安装后没有重新打开 PowerShell。

处理：

1. 打开 Docker Desktop。
2. 关闭当前 PowerShell。
3. 重新打开 PowerShell。
4. 执行：

```powershell
docker --version
```

### 13.2 Docker Desktop 一直启动失败

常见原因是 WSL 2 没启用。

可以在管理员 PowerShell 中执行：

```powershell
wsl --install
```

然后重启电脑。

### 13.3 `port is already allocated`

说明端口被占用了。例如 MySQL 已经占用 `3306`。

可以修改 `.env`：

```dotenv
MYSQL_PORT=3307
REDIS_PORT=6380
FRONTEND_PORT=5173
BACKEND_PORT=8081
MODEL_SERVICE_PORT=8001
```

修改后重启：

```powershell
docker compose up -d --force-recreate
```

如果改了 `FRONTEND_PORT`，浏览器访问地址也要跟着改。

### 13.4 页面打不开

检查服务是否运行：

```powershell
docker compose ps
```

看前端日志：

```powershell
docker compose logs --tail=100 frontend
```

看后端日志：

```powershell
docker compose logs --tail=100 backend
```

### 13.5 登录失败

先确认使用的是初始化账号：

```text
admin / 12345
t001 / Pass12345
s001 / Stu123456
```

如果已经改过密码，以改后的密码为准。

如果忘记了，并且只是本地测试环境，可以重新初始化数据库：

```powershell
docker compose down -v
docker compose up -d --build
```

### 13.6 AI 评分一直没有结果

先看任务 worker 日志：

```powershell
docker compose logs -f --tail=200 model-service
```

再检查健康状态：

```powershell
curl http://localhost:8000/health
```

如果使用学校 AI：

- 检查 `AI_ENABLE_REMOTE=true`。
- 检查 `DEEPSEEK_API_KEY` 是否填写。
- 检查电脑是否能访问学校网络。

如果使用 Ollama：

- 检查 `ollama list` 是否有模型。
- 检查 `curl http://127.0.0.1:11434/api/tags` 是否成功。
- 检查 `.env` 中 `LOCAL_AI_BASE_URL` 是否是 `http://host.docker.internal:11434/v1/chat/completions`。
- 修改 `.env` 后是否执行了 `docker compose up -d --force-recreate`。

### 13.7 修改 `.env` 后为什么没生效

Docker 容器启动时读取 `.env`。修改 `.env` 后，必须重新创建容器：

```powershell
docker compose up -d --force-recreate
```

检查容器实际读到的值：

```powershell
docker compose exec model-service printenv AI_PROVIDER
docker compose exec model-service printenv LOCAL_AI_BASE_URL
```

### 13.8 已经 `docker load`，为什么还在联网下载镜像

通常是下面几种情况：

- 启动命令用了 `docker compose up -d --build`，导致 Docker 重新构建镜像。
- `.env` 里没有 `COMPOSE_PROJECT_NAME=ai-code-grading`，Compose 查找的镜像名和离线包里的镜像名不一致。
- 离线包没有成功加载，或者加载到了另一个 Docker Desktop context。

先检查镜像：

```powershell
docker images
```

再检查 Compose 实际要用哪些镜像：

```powershell
docker compose config --images
```

如果离线包里的镜像是：

```text
ai-code-grading-backend
ai-code-grading-model-service
ai-code-grading-frontend
```

那么 `.env` 里应设置：

```dotenv
COMPOSE_PROJECT_NAME=ai-code-grading
```

然后使用离线启动命令：

```powershell
docker compose up -d --no-build --pull never
```

## 14. 推荐的第一次验收流程

第一次部署建议按这个顺序验收：

1. 打开 Docker Desktop。
2. 如果使用离线镜像包，执行 `docker load -i ai-code-grading-images-amd64.tar`。
3. 离线部署执行 `docker compose up -d --no-build --pull never`；在线部署执行 `docker compose up -d --build`。
4. 打开 `http://localhost:5173`。
5. 用 `admin / 12345` 登录。
6. 进入教师工作台，确认作业、学生、提交记录页面能打开。
7. 用 `s001 / Stu123456` 登录学生账号。
8. 上传一个 ZIP 作业。
9. 用教师账号 `t001 / Pass12345` 登录。
10. 查看提交记录。
11. 在 `AI_ENABLE_REMOTE=false` 的情况下先发起一次评分，确认兜底评分流程能跑通。
12. 再接学校 AI 或 Ollama，发起真实 AI 评分。

## 15. 建议保存的信息

老师本机部署完成后，建议把这些信息记到本地私有文档里：

```text
项目目录：D:\AI-Code-Grading-System
文件存储目录：D:\ai-grading
前端地址：http://localhost:5173
管理员账号：admin
管理员密码：部署后修改过的新密码
AI 模式：离线兜底 / 学校 AI / 本机 Ollama
Ollama 模型名：deepseek-r1:14b
```

不要把真实数据库密码、Redis 密码、API Key 发到公开群或提交到 Git 仓库。
