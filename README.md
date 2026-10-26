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
- `AI_WORKER_CONCURRENCY` 控制模型服务同时评分的作业数，默认 `5`，允许范围 `1`～`10`；管理员页保存后需在没有执行中评分任务时重建 `model-service`。
- `DEEPSEEK_TOKEN_QUOTA` 默认 `12000000`，用于教师端 DeepSeek 配额余量展示。
- `REDIS_PASSWORD` 会同时传给 Redis、后端和 Python worker。
- `STORAGE_ROOT` 是后端文件存储根目录，Linux 推荐 `/data/ai-grading`，Windows 可使用 `D:/ai-grading`；Compose 会绑定挂载到容器 `/data/ai-grading`。
- `LOCAL_AI_BASE_URL`、`LOCAL_AI_MODEL`、`LOCAL_AI_API_KEY` 用于 DeepSeek 失败后的 OpenAI 兼容 fallback 模型。
- `AI_ENABLE_REMOTE=false` 时，AI 服务使用确定性的 fallback 评分，便于离线演示。
- `LOCAL_AI_BASE_URL` 可指向局域网 GPU 推理服务的 OpenAI 兼容接口；不要指向 `model-service` 自身端口。
- `AI_PROMPT_FULL_CODE_CHAR_LIMIT` / `AI_PROMPT_CORE_CODE_CHAR_LIMIT` 默认 `8000` / `30000`，控制评分 Prompt 的代码截取策略；超大项目默认每个文件保留前 `100` 行。
- `LOCAL_INFERENCE_*` 用于配置仓库内独立 `inference-service`，默认端口为 `8002`；`LOCAL_INFERENCE_MODEL_PATH` 是真实模型路径，`LOCAL_INFERENCE_SERVED_MODEL_NAME` 应与 `LOCAL_AI_MODEL` 一致。

管理员修改模型评分并发数后，等待当前评分任务结束，再执行：

```bash
docker compose up -d --force-recreate model-service
```

## 常用验收命令

```bash
cd backend && ./mvnw test -q
cd backend && ./mvnw package -q
cd frontend && npm run build
python3 -m py_compile model-service/app/main.py
python3 -m py_compile inference-service/app/main.py
```

## 主要 API

评分模板继续使用原有 Word/Excel 格式，不需要老师增加列或重新分发文件。导入后按“维度 → 评分项 → 档位说明”展示，编号不同的同项区间会合并为一项满分；变量、循环等固定分值评分点仍独立累计。管理员可在页面选择 `ai_rule_average`（AI＋规则平均）、`ai_only`（仅 AI）或 `manual`（教师评分）。旧模板读取时提供兼容的合并视图，已有作业和历史报告的快照不自动重算。

新页面默认“下发总任务，由老师按班发布”：管理员统一创建题目和评分标准，发布后当前学期所有任课老师均可查看完整作业及评分快照，不受管理员本人班级或旧目标班级字段限制。普通老师不重新创建作业，而是选择本人负责班级及开始、截止时间，生成独立班级作业。总任务本身不向学生开放；本班作业发布且到达开放时间后学生才可见。班级作业继承发布时的评分标准快照，老师只可调整本班时间；管理员查看各班发布进度，普通老师只查看“本班发布状态”。老师负责班级读取账号中的任课班级，未填写时兼容原班级字段，支持逗号、顿号分隔及 `软件26201-26203` 这样的连续编号范围，不做模糊班级匹配。

新 API 字段 `teacherReleaseRequired=true` 表示总任务；旧客户端不传该字段时保留原来的直接发布行为，历史作业无需迁移为总任务。

管理员兼任任课老师时，可在下发总任务前勾选“同时发布本人负责班级”，选择本人班级并设置本班开始及截止时间。总任务与勾选的班级作业在同一事务中生成；不勾选时不自动发布，保存草稿也不会发布。管理员只开放自己班级不影响其他老师查看总任务和向他们的班级发布。负责班级以数据库最新教师资料为准，管理员的全局查看权限不作为任课发布权限。已下发的总任务也支持管理员使用“发布到本班”补发本人班级，不能重复发布同一班级。

班级作业按负责班级授权，不按点击发布的人授权。管理员或同班其他老师代发后，本班任课老师仍能查看提交、发起评分、查看报告、复核和发布成绩，也可调整本班开放时间；其他班级仍不可访问。`teacher_id` 继续保留实际发布人，不重写作业归属记录；实际复核人单独记录在复核历史中。负责班级更改后，以数据库最新分配即时校验，旧发布人不因此保留原班级权限。

- `GET /api/v1/assignments/my-classes` 获取当前教师（含管理员）实际负责的班级。
- `GET /api/v1/assignments/{id}` 查看作业详情与固定评分标准，普通老师可读取已发布总任务但不能修改。总任务允许不传目标班级；旧的直接发布作业仍必须选择班级。
- 创建或更新草稿并下发时，可传 `ownClassRelease: {classNames: ["本人班级"], startTime: null, endTime: "2026-12-15T23:59:59"}`；不传则保持原行为。PATCH 下发接口的可选请求体直接使用该对象。

- `POST /api/v1/assignments/{id}/class-release` 老师按总任务发布本班作业，提交 `className`、可选 `startTime`、必填 `endTime`。
- `PUT /api/v1/assignments/{id}/class-schedule` 仅调整本人负责班级的发布时间、截止时间，不要求本人是发布人；管理员兼任教师时也校验负责班级。子作业沿用创建时的总任务内容和评分快照，通用编辑接口禁止修改子作业；调整时间不影响总任务或其他班。
- `GET /api/v1/assignments/{id}/class-releases` 仅管理员可查看全局发布状态，普通老师调用返回 403。
- `GET /api/v1/assignments/{id}/my-class-releases` 仅返回当前老师负责班级的状态，不返回其他班级进度。

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
