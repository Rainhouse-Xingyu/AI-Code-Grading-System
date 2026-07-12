# AI Code Grading System

工程级代码作业评分闭环：教师创建作业并导入学生，学生提交 ZIP，后端解析代码结构，AI 服务消费 Redis 任务生成评分报告，教师复核后发布成绩并导出 PDF。

## 本地开发

```bash
cd backend
./mvnw spring-boot:run
```

```bash
cd model-service
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
```

```bash
cd inference-service
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8002
```

```bash
cd frontend
npm install
npm run dev
```

默认前端地址是 `http://localhost:5173`，后端地址是 `http://localhost:8080`。后端默认连接 MySQL；自动化测试会显式使用 H2 隔离环境。前端采用 `Vue3 + Vite + Element Plus`，使用 JavaScript，不使用 TypeScript。

前端目录约定：

- `frontend/src/components`：页面与复用组件。
- `frontend/src/JS`：接口请求、下载等 JavaScript 工具。
- `frontend/src/CSS`：按功能拆分样式，如 `teacher.css`、`student.css`。

账号安全：

- 密码至少 8 位，且必须同时包含字母和数字。
- 连续 5 次登录失败后账户锁定 15 分钟。
- 教师批量导入或重置学生密码后，学生登录会看到修改初始密码提示。
- 前端顶部“个人设置”可修改当前账号密码。

## Docker Compose

```bash
cp .env.example .env
docker compose up --build
```

Compose 会启动 MySQL 8、Redis 7、Spring Boot 后端、FastAPI AI 服务和 nginx 托管的前端。前端访问 `http://localhost:5173`。

可选启动独立本地 GPU 推理服务：

```bash
docker compose --profile local-inference up --build
```

启用后可将 `LOCAL_AI_BASE_URL` 配置为 `http://local-inference:8002/v1/chat/completions`（Compose 内部访问）或 `http://127.0.0.1:8002/v1/chat/completions`（宿主机进程访问）。

关键配置：

- `AI_ENABLE_REMOTE=true` 且设置 `DEEPSEEK_API_KEY` 后，AI 服务优先调用 DeepSeek。
- `DEEPSEEK_BASE_URL` 可配置为学校模型网关地址，服务会自动拼接 Chat Completions 路径。
- `DEEPSEEK_TOKEN_QUOTA` 默认 `12000000`，用于教师端 DeepSeek 配额余量展示。
- `REDIS_PASSWORD` 会同时传给 Redis、后端和 Python worker。
- `STORAGE_ROOT` 是后端文件存储根目录，Linux 推荐 `/data/ai-grading`，Windows 可使用 `D:/ai-grading`；Compose 会绑定挂载到容器 `/data/ai-grading`。
- `LOCAL_AI_BASE_URL`、`LOCAL_AI_MODEL`、`LOCAL_AI_API_KEY` 用于 DeepSeek 失败后的 OpenAI 兼容 fallback 模型。
- `AI_ENABLE_REMOTE=false` 时，AI 服务使用确定性的 fallback 评分，便于离线演示。
- `LOCAL_AI_BASE_URL` 可指向局域网 GPU 推理服务的 OpenAI 兼容接口；不要指向 `model-service` 自身端口。
- `AI_PROMPT_FULL_CODE_CHAR_LIMIT` / `AI_PROMPT_CORE_CODE_CHAR_LIMIT` 默认 `8000` / `30000`，控制评分 Prompt 的代码截取策略；超大项目默认每个文件保留前 `100` 行。
- `LOCAL_INFERENCE_*` 用于配置仓库内独立 `inference-service`，默认端口为 `8002`；`LOCAL_INFERENCE_MODEL_PATH` 是真实模型路径，`LOCAL_INFERENCE_SERVED_MODEL_NAME` 应与 `LOCAL_AI_MODEL` 一致。

## 常用验收命令

```bash
cd backend && ./mvnw test -q
cd backend && ./mvnw package -q
cd frontend && npm run build
python3 -m py_compile model-service/app/main.py
python3 -m py_compile inference-service/app/main.py
```

## 主要 API

- `GET /api/v1/users/import-template` 下载学生导入模板。
- `POST /api/v1/users/batch-import` 批量导入学生。
- `GET/POST/PUT/PATCH/DELETE /api/v1/rubric-templates` 管理员维护全局评分模板，教师读取启用模板并在作业中勾选评分点。
- `POST /api/v1/submissions` 学生上传 ZIP。
- `GET /api/v1/submissions/{id}/download` 下载提交 ZIP。
- `GET /api/v1/assignments/{id}/download-all` 下载当前作业全部学生代码和评分报告 ZIP。
- `GET /api/v1/assignments/{id}/download-selected?studentIds=...` 下载勾选学生代码和评分报告 ZIP。
- `POST /api/v1/ai-tasks/batch-score` 教师批量发起评分。
- `POST /api/v1/ai-tasks/cancel-current?assignment_id={id}` 结束当前作业最近一批未完成的评分任务。
- `GET /api/v1/ai-tasks/{id}/logs` 查看任务执行日志。
- `POST /api/v1/exports/pdf/batch` 批量导出 PDF ZIP。
