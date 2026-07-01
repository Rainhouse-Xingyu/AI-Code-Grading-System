# AI代码自动评分系统 —— 需求设计文档（PRD + 技术方案）

**版本：** V2.0  
**日期：** 2026-06-25  
**类型：** 毕业设计 / 项目评审 / 技术答辩  
**密级：** 内部  

---

## 目录

1. [项目概述](#1-项目概述)
2. [核心难点与技术挑战](#2-核心难点与技术挑战)
3. [系统角色定义](#3-系统角色定义)
4. [功能模块设计](#4-功能模块设计)
5. [技术架构设计](#5-技术架构设计)
6. [数据库设计](#6-数据库设计)
7. [AI评分核心链路详解](#7-ai评分核心链路详解)
8. [API接口设计](#8-api接口设计)
9. [风险分析与缓解策略](#9-风险分析与缓解策略)
10. [可扩展性设计](#10-可扩展性设计)
11. [开发环境与部署拓扑](#11-开发环境与部署拓扑)

---

## 1. 项目概述

### 1.1 项目背景

高校程序设计类课程（Java、Python、C/C++）每学期有 60–200 名学生，教师需要在 1–2 周内完成数十万行代码的人工批改。传统方式的痛点：

| 痛点 | 现状 | AI 方案 |
|------|------|---------|
| 批改量大 | 每位学生 3–15 个代码文件，逐份审阅 | 批量交给 AI 初评，教师仅需复核 |
| 标准不统一 | 不同助教尺度不一致 | 评分标准结构化（Rubric JSON），AI 严格按标准执行 |
| 反馈不及时 | 批改周期 1–2 周 | 学生提交后 10 分钟内完成 AI 初评 |
| 重复劳动 | 代码规范检查占 40% 精力 | AI 自动标记命名、缩进、注释等问题 |

### 1.2 系统目标

构建一套**工程级 AI 代码自动评分系统**，覆盖「学生提交 ZIP → 代码结构化 → AI 评分 → 教师复核 → 成绩发布 → PDF 导出」的完整闭环。AI 模型采用 DeepSeek 云端 API 作为主模型，本地部署的 Qwen2.5-Coder-7B 作为 fallback 兜底。

### 1.3 核心技术链路

```
ZIP上传 → 解压 → 文件递归读取 → 结构化JSON → 依赖分析 → Redis队列 → Prompt组装
→ DeepSeek评分 → [失败? → 本地模型兜底] → 评分报告 → 教师复核 → 成绩发布 → PDF导出
```

---

## 2. 核心难点与技术挑战

### 2.1 难点一：ZIP 二进制文件解析

**问题：** ZIP 文件是二进制压缩格式，AI 模型（LLM）无法直接读取 ZIP 字节流。必须在上传后**立即解压**并**递归遍历目录结构**，将每个代码文件的完整内容读取为文本后存储。

**技术方案（SpringBoot 端）：**

```
ZIP字节流
  ↓ ZipInputStream（Java标准库）
临时目录解压
  ↓ Files.walkFileTree 递归遍历
代码文件过滤（.java .py .c .cpp .h .js .ts .go 等）
  ↓ 跳过二进制（.class .exe .o .so .jar）和超大文件（>1MB）
逐文件 UTF-8 读入
  ↓ 组装为结构化 JSON
存入 t_project_structure.structure_json
  ↓
清理临时目录
```

**关键设计决策：**
- **为什么不传到 Python 端解压？** —— 减少网络传输，ZIP 50MB 内部可能有 200+ 文件，传给 Python 再解压增加一次 I/O。SpringBoot 端就地解压生成 JSON 后，Python 仅接收 JSON 文本（通常 100KB–500KB）。
- **为什么持久化 structure_json？** —— 一份提交可能被评分多次（重评、模型对比），每次评分不应重复解压。structure_json 存入数据库，后续评分直接读取。

---

### 2.2 难点二：代码结构化（Code-to-JSON）

**问题：** AI 需要理解代码的**文件组织方式**。如果直接把所有文件内容拼接成一长串文本，AI 无法区分哪些代码属于哪个文件，跨文件引用关系也会丢失。

**结构化 JSON 设计：**

```json
{
  "language": "java",
  "total_files": 5,
  "total_lines": 342,
  "file_tree": [
    {
      "path": "src/main/java/com/example/Main.java",
      "filename": "Main.java",
      "language": "java",
      "lines": 45,
      "content": "package com.example;\n\nimport com.example.utils.StringUtils;\n\npublic class Main {\n    public static void main(String[] args) {\n        StringUtils.printHello();\n    }\n}"
    },
    {
      "path": "src/main/java/com/example/utils/StringUtils.java",
      "filename": "StringUtils.java",
      "language": "java",
      "lines": 120,
      "content": "package com.example.utils;\n\npublic class StringUtils {\n    public static void printHello() {\n        System.out.println(\"Hello\");\n    }\n}"
    }
  ],
  "structure_summary": "├── src/\n│   └── main/\n│       └── java/\n│           └── com/\n│               └── example/\n│                   ├── Main.java (45行, 入口类)\n│                   └── utils/\n│                       └── StringUtils.java (120行)"
}
```

**关键设计：**
- `path` 保留原始相对路径，AI 可通过路径理解项目结构
- `content` 是完整的文件文本，AI 可做行级引用
- `structure_summary` 提供 ASCII 树状图，帮助 AI 快速建立全局认知

---

### 2.3 难点三：代码依赖关系分析（AST 解析 + 依赖图）

**问题：** 单文件评分无法理解跨文件调用关系。例如 `UserController` 调用 `UserService`，如果 AI 只看到 `UserController.java`，无法判断其设计是否合理。

**技术方案：**

每种语言使用对应的 AST 解析工具：

| 语言 | 解析工具 | 提取内容 |
|------|---------|---------|
| Java | javaparser-core (Java库) | import 语句、类声明、方法声明、方法调用 |
| Python | ast 模块 (Python标准库) | import 语句、class/def 声明、函数调用 |
| C/C++ | pycparser (Python库) | #include、函数声明、函数调用 |

**依赖图 JSON 结构：**

```json
{
  "dependencies": [
    {"from_file": "src/Main.java", "from_class": "Main", "to_file": "src/utils/StringUtils.java", "to_class": "StringUtils", "type": "method_call"},
    {"from_file": "src/Main.java", "from_class": "Main", "to_file": null, "to_class": "java.util.Scanner", "type": "import"}
  ],
  "dependency_graph": "Main ──调用──▶ StringUtils\nMain ──导入──▶ java.util.Scanner"
}
```

**为什么这对 AI 评分至关重要：**

给 AI 的 Prompt 中包含依赖图后，AI 可以回答：
- "StringUtils 被 Main 调用但缺少对应 import" → 扣分
- "UserController 直接访问数据库而非通过 UserService" → 违反 MVC 规范，扣分
- "循环依赖：A → B → C → A" → 设计缺陷，扣分

**实现策略（MVP）：**
- Java：SpringBoot 端使用 `com.github.javaparser:javaparser-core` 进行 import 解析 + 方法调用图构建
- Python/C++：任务参数中标记语言类型，Python AI Service 评分前在本地执行 AST 分析

---

### 2.4 难点四：AI 评分 Prompt 工程

**问题：** 通用 Prompt（"请给这段代码打分"）效果极差。必须将结构化代码 JSON + 评分标准 JSON + 依赖分析 JSON 精确组织成 Prompt，并约束 AI 输出**严格的 JSON 格式**。

**Prompt 模板设计：**

```
## 系统指令
你是一名严格但公正的编程课程助教。请严格按照以下评分标准对学生代码进行评分。

## 评分标准（Rubric）
{ rubric_json 完整内容 }

## 学生代码结构
- 语言: {language}
- 文件数: {total_files}，总行数: {total_lines}

### 项目目录结构
{ structure_summary }

### 代码依赖关系
{ dependency_graph }

### 各文件详细内容
{ 遍历 file_tree，逐个输出: "## 文件: {path}" + 代码内容 }

## 输出格式要求
请严格按照以下 JSON Schema 返回评分结果（不要包含任何额外的解释文字）:
{
  "total_score": <0-100的整数>,
  "dimension_scores": [
    {"name": "<维度名>", "score": <得分>, "max_score": <满分>, "comment": "<评语>"}
  ],
  "issues": [
    {"severity": "error|warning|suggestion", "file": "<文件路径>", "line": <行号>, "description": "<问题描述>"}
  ],
  "suggestions": "<整体改进建议>",
  "report_markdown": "<完整的Markdown格式评分报告>"
}
```

**Prompt 长度控制策略：**

| 策略 | 触发条件 | 操作 |
|------|---------|------|
| 全量送入 | 代码总字符数 ≤ 8000 | 所有文件内容全部送入 |
| 关键文件优先 | 8000 < 字符数 ≤ 30000 | 优先送入入口文件 + 核心逻辑文件，其余仅保留路径和行数 |
| 摘要模式 | 字符数 > 30000 | 每个文件仅送 structure_summary + 每个文件前 100 行 |

---

## 3. 系统角色定义

| 角色 | 描述 | 核心职责 |
|------|------|---------|
| 学生 | 高校编程课学习者 | 上传 ZIP 作业、查看评分报告 |
| 教师 | 课程教师/助教 | 创建作业、上传评分标准、发起 AI 评分、复核修改、推送成绩、导出 PDF |
| 管理员 | 系统运维 | 用户管理、模型配置、存储管理 |
| AI 评分服务 | Python FastAPI 应用 | 消费 Redis 任务、组装 Prompt、调用模型、生成报告 |
| 本地推理服务 | 独立 GPU 主机 Python 应用 | 当 DeepSeek 不可用时兜底评分 |
| 文件处理服务 | SpringBoot 内嵌组件 | ZIP 解压、递归读取、代码 JSON 化、依赖分析 |

---

## 4. 功能模块设计

### 4.1 用户与权限模块

- 教师主动注册（工号+密码）；学生由教师通过 Excel 批量导入，分配统一初始密码
- 学生首次登录引导修改密码
- JWT 认证（24h 有效期），BCrypt 密码加密
- 角色级权限隔离：学生只能看自己的提交和成绩，教师只能管理本班学生

### 4.2 作业管理模块

- 教师：创建作业（标题、描述、编程语言、截止时间）、发布/草稿状态切换、查看提交统计
- 学生：查看已发布作业列表、按截止时间倒计时、查看提交状态

### 4.3 作业提交模块（含 ZIP 预处理）

- 学生上传 `.zip` 文件（≤50MB）
- 后端校验 MIME 类型 → 重命名为 `{学号}_{姓名}.zip` → 存储
- **立即解压 + 递归遍历目录** → 过滤代码文件 → 生成 `structure_json` → 存入 `t_project_structure`
- 清理临时目录，返回提交成功
- 同一作业允许多次提交（覆盖，保留历史）
- **学生提交不自动触发 AI 评分**，由教师批量手动发起

### 4.4 文件处理模块（代码结构化核心）

#### 4.4.1 ZIP 解压与文件遍历

```
Implementation: Java java.util.zip.ZipInputStream + java.nio.file.Files.walkFileTree
过滤规则:
  - 代码文件白名单: .java .py .c .cpp .h .hpp .js .ts .go .rs .kt .swift
  - 跳过目录: __pycache__ .git node_modules target build .idea .vscode
  - 跳过二进制: .class .jar .exe .o .so .pyc .dll
  - 单文件限制: >1MB 跳过并记录警告
```

#### 4.4.2 代码结构化（Code-to-JSON）

输出存入 `t_project_structure.structure_json`，格式详见 [2.2 节](#22-难点二代码结构化code-to-json)。

#### 4.4.3 依赖分析（Dependency Graph）

| 实现方式 | 适用语言 | 位置 |
|---------|---------|------|
| `com.github.javaparser:javaparser-core:3.25.10` | Java | SpringBoot 端同步执行 |
| `ast` 标准库模块 | Python | Python AI Service 评分前执行 |
| `pycparser` | C/C++ | Python AI Service 评分前执行 |

依赖分析结果 JSON 伴随 `code_json` 一同送入 Redis 任务队列。

### 4.5 评分标准模块（Word/Excel 解析）

- 教师上传 `.docx` 或 `.xlsx` 文件
- 后端解析规则：
  - Word：按标题层级识别维度（`## 标题 (分值)`），按列表项识别子项（`- 名称 (分值)`）
  - Excel：按固定表头（评分维度/权重/子项/子项分值/评分标准）解析
- 解析结果转换为结构化 Rubric JSON 并可视化预览
- 解析失败 → 明确错误提示（指明哪一行/哪个表头有问题）

### 4.6 AI 评分模块（核心）

#### 4.6.1 评分调度

- 教师勾选提交记录（支持全选/单选），点击"发起 AI 评分"
- 系统读取每个 Submission 的 `code_json` + Rubric `content_json`，组装 AiTask 推送 Redis 队列
- Python AI Service 拉取任务 → 组装 Prompt → 调用模型

#### 4.6.2 双模型评分策略

```
首选: DeepSeek Chat API (chat/completions)
  ├─ 成功 → 解析 JSON → 写入 AiReport → 更新 AiTask: success
  └─ 失败:
       ├─ 重试 1 (3s 后)
       ├─ 重试 2 (10s 后)
       └─ 均失败 → 切换本地模型
                      ├─ 成功 → 写入 AiReport (model: fallback)
                      └─ 失败 → 更新 AiTask: failed
```

#### 4.6.3 AI 输出格式约束

AI 返回的 JSON 必须通过结构校验（总分范围 0–100、dimension_scores 必须覆盖 rubric 全部维度、issues 数组每项含必填字段），校验不通过 → 触发重试。

### 4.7 异步任务模块

- 存储介质：Redis List（`LPUSH` 入队 / `BRPOP` 出队）
- 任务参数：`{ submission_id, rubric_json, code_json, dependency_json }`
- 状态机：`pending → running → success | failed`
- 失败记录：error_message + retry_count
- 3 次重试后仍失败 → 标记 failed，停止自动重试

### 4.8 教师批改与反馈模块

- 提交列表中每个学生显示"查看报告"按钮
- 评分详情页：分项得分卡片 + 问题列表 + Markdown 报告渲染 + 在线 Markdown 编辑器（左侧编辑/右侧预览）
- 教师修改分项得分 → 总分自动重算
- 保存修改 → 生成 TeacherReview（保留 AI 原始 AiReport）
- 点击"推送给学生" → 单份推送
- 列表页勾选"批量推送" → 多份同时推送

### 4.9 成绩发布与 PDF 导出

- 推送后生成 GradePublish 记录，成绩锁定（可撤回）
- 学生端查看：总分 + 分项得分 + AI 原始分 + 教师评语 + Markdown 报告
- PDF 导出：详情页单份导出（`{学号}_{姓名}.pdf`），列表页批量导出（ZIP 打包）
- PDF 生成：后端使用 Puppeteer/wkhtmltopdf 将 Markdown 渲染为 PDF

---

## 5. 技术架构设计

### 5.1 总体架构图（文字绘制）

```
┌────────────────────────────────────────────────────────────────────────┐
│                           前端层 (Vue3 + Vite + Element Plus)             │
│   学生端: 作业列表 / ZIP上传 / 查看报告                                    │
│   教师端: 作业管理 / 评分标准上传 / 发起评分 / 复核修改 / PDF导出            │
└──────────────────────────┬─────────────────────────────────────────────┘
                           │ HTTP REST API (JWT Auth)
                           ▼
┌────────────────────────────────────────────────────────────────────────┐
│                       Spring Boot 3 后端 (Java 17)                       │
│                                                                        │
│  ┌───────────┐  ┌───────────┐  ┌────────────┐  ┌──────────────────┐   │
│  │ Controller│  │  Service  │  │  Mapper     │  │ 文件处理组件      │   │
│  │ (RESTful) │──│ (业务逻辑) │──│ (MyBatis+)  │  │ - Zip解压        │   │
│  │           │  │           │  │             │  │ - 递归遍历       │   │
│  │ JWT过滤器 │  │ 事务管理  │  │ MySQL交互   │  │ - AST依赖分析    │   │
│  └───────────┘  └───────────┘  └────────────┘  │ - JSON结构化     │   │
│                                                 └──────────────────┘   │
│                          │                          │                  │
│                     ┌────┴────┐                     │                  │
│                     │  MySQL  │                     │                  │
│                     │ 8.0+    │                     │                  │
│                     └─────────┘                     │                  │
│                                                    ▼                  │
│                          ┌──────────────┐    ┌───────────┐            │
│                          │  Redis 7.x   │    │  本地文件  │            │
│                          │ (队列+缓存)  │    │ / MinIO   │            │
│                          └──────┬───────┘    └───────────┘            │
└─────────────────────────────────┼─────────────────────────────────────┘
                                  │ LPUSH / BRPOP (任务队列)
                                  ▼
┌────────────────────────────────────────────────────────────────────────┐
│                     Python AI 编排服务 (FastAPI)                         │
│                                                                        │
│  ┌──────────────┐   ┌──────────────┐   ┌─────────────────────────┐    │
│  │ Redis 消费者 │──▶│ Prompt 组装   │──▶│ 模型调用路由            │    │
│  │ (BRPOP监听)  │   │ - Rubric注入  │   │  首选 DeepSeek API      │    │
│  │              │   │ - Code注入    │   │  失败→ 局域网推理服务   │    │
│  │              │   │ - 依赖图注入  │   │                         │    │
│  └──────────────┘   └──────────────┘   └───────────┬─────────────┘    │
│                                                     │                  │
│                                         ┌───────────┴───────────┐     │
│                                         ▼                       ▼     │
│                                   DeepSeek API         HTTP POST      │
│                                   (公网)             局域网 → GPU主机  │
│                                                                        │
│  ┌──────────────┐                                                      │
│  │ 结果回调     │──▶ 写MySQL AiReport + 更新AiTask状态                  │
│  └──────────────┘                                                      │
└────────────────────────────────────────────────────────────────────────┘
                                  │ 局域网 HTTP
                                  ▼
┌────────────────────────────────────────────────────────────────────────┐
│              GPU 推理服务器 (Windows 11, 16GB RAM, RTX 3090)            │
│                                                                        │
│  ┌───────────────────────────────────────────────────────┐             │
│  │  自研 Python 推理服务 (inference_server.py)            │             │
│  │  - transformers + vLLM 加载模型                        │             │
│  │  - Qwen2.5-Coder-7B-Instruct / DeepSeek-Coder-7B      │             │
│  │  - OpenAI 兼容 API: POST /v1/chat/completions          │             │
│  │  - 监听 0.0.0.0:8000                                   │             │
│  └───────────────────────────────────────────────────────┘             │
└────────────────────────────────────────────────────────────────────────┘
```

### 5.2 技术栈明细

| 层级 | 技术 | 版本 |
|------|------|------|
| 后端框架 | Spring Boot | 3.x (JDK 17) |
| ORM | MyBatis-Plus | 3.5.x |
| 数据库 | MySQL | 8.0+ |
| 缓存/队列 | Redis | 7.x |
| 前端 | Vue 3 + Vite + Element Plus | — |
| AI 编排 | Python FastAPI | 3.10+ |
| AI 云端 | DeepSeek Chat API | — |
| AI 本地推理 | transformers + vLLM + PyTorch | — |
| Java AST 解析 | javaparser-core | 3.25.x |
| Python AST 解析 | ast (标准库) | — |
| C AST 解析 | pycparser | 2.21 |
| Word 解析 | Apache POI (poi-ooxml) | 5.2.x |
| Excel 解析 | Apache POI / easyexcel | — |
| PDF 生成 | wkhtmltopdf / weasyprint | — |
| 认证 | jjwt | 0.12.x |
| 存储 | 本地磁盘 → MinIO | — |

### 5.3 跨语言通信方案

SpringBoot (Java) 与 Python AI Service 之间**不通过 HTTP 直连**，而是通过 **Redis 解耦**：

```
SpringBoot                    Redis                    Python AI Service
   │                            │                            │
   │──LPUSH task_queue──▶      │                            │
   │  (写入AiTask记录)           │                            │
   │                            │          BRPOP task_queue──│
   │                            │◀───────────────────────────│
   │                            │                            │──处理评分
   │                            │                            │──写入AiReport
   │                            │                            │──更新AiTask状态
   │◀──轮询AiTask状态──────────│                            │
   │  返回给前端                 │                            │
```

优势：
- Java 和 Python 完全解耦，各自独立部署升级
- Redis 天然支持多消费者，Python Service 可水平扩展
- 任务持久化在 Redis List 中，消费者重启不丢任务

---

## 6. 数据库设计

### 6.1 实体关系图（ER）

```
t_user (1) ─────────< (N) t_submission (学生提交)
t_user (1) ─────────< (N) t_assignment (教师发布)
t_assignment (1) ───< (N) t_submission
t_assignment (1) ───< (N) t_rubric
t_submission (1) ──── (1) t_project_structure (代码结构JSON)
t_submission (1) ───< (N) t_ai_task
t_submission (1) ───< (N) t_ai_report
t_ai_task (1) ──────< (N) t_ai_report
t_submission (1) ───< (N) t_teacher_review
t_teacher_review (1) ─ (1) t_grade_publish
t_submission (1) ──── (1) t_file
t_rubric (1) ──────── (1) t_file
```

### 6.2 完整表结构

#### t_user（用户表）

```sql
CREATE TABLE `t_user` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `username`   VARCHAR(50)  NOT NULL COMMENT '学号/工号',
  `password`   VARCHAR(100) NOT NULL COMMENT 'BCrypt加密',
  `role`       VARCHAR(20)  NOT NULL COMMENT 'student/teacher/admin',
  `real_name`  VARCHAR(50)  DEFAULT NULL,
  `email`      VARCHAR(100) DEFAULT NULL,
  `class_name` VARCHAR(50)  DEFAULT NULL COMMENT '班级',
  `created_at` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### t_assignment（作业表）

```sql
CREATE TABLE `t_assignment` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `title`       VARCHAR(100) NOT NULL COMMENT '作业标题',
  `description` TEXT         DEFAULT NULL,
  `teacher_id`  BIGINT       NOT NULL,
  `language`    VARCHAR(50)  DEFAULT NULL COMMENT '编程语言 java/python/c/cpp',
  `status`      VARCHAR(20)  DEFAULT 'draft' COMMENT 'draft/published/closed',
  `start_time`  DATETIME     DEFAULT NULL,
  `end_time`    DATETIME     DEFAULT NULL COMMENT '截止时间',
  `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### t_submission（提交表）

```sql
CREATE TABLE `t_submission` (
  `id`                    BIGINT         NOT NULL AUTO_INCREMENT,
  `assignment_id`         BIGINT         NOT NULL,
  `student_id`            BIGINT         NOT NULL,
  `file_url`              VARCHAR(500)   NOT NULL COMMENT 'ZIP存储路径',
  `file_name`             VARCHAR(255)   DEFAULT NULL COMMENT '原始文件名',
  `project_structure_id`  BIGINT         DEFAULT NULL COMMENT '关联t_project_structure',
  `language`              VARCHAR(50)    DEFAULT NULL COMMENT '编程语言',
  `file_count`            INT            DEFAULT 0 COMMENT '代码文件数',
  `upload_time`           DATETIME       DEFAULT CURRENT_TIMESTAMP,
  `status`                VARCHAR(20)    DEFAULT 'uploaded' COMMENT 'uploaded/parsed/scoring/scored/reviewed/published/parse_failed',
  `current_score`         DECIMAL(5,2)   DEFAULT NULL,
  `current_report_id`     BIGINT         DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### t_project_structure（代码结构表）★核心表

```sql
CREATE TABLE `t_project_structure` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `submission_id`   BIGINT       NOT NULL,
  `structure_json`  LONGTEXT     DEFAULT NULL COMMENT '完整代码结构JSON(file_tree+contents)',
  `language`        VARCHAR(50)  DEFAULT NULL,
  `file_count`      INT          DEFAULT 0,
  `created_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `idx_submission_id` (`submission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代码结构存储表-ZIP解压后生成';
```

**structure_json 字段详细说明：** 见 [2.2 节结构化 JSON 设计](#22-难点二代码结构化code-to-json)，此处为数据库中实际存储的 LONGTEXT 内容。

#### t_rubric（评分标准表）

```sql
CREATE TABLE `t_rubric` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `assignment_id`   BIGINT       NOT NULL,
  `rubric_type`     VARCHAR(20)  DEFAULT 'manual' COMMENT 'manual/auto',
  `file_url`        VARCHAR(500) DEFAULT NULL,
  `rubric_json`     JSON         NOT NULL COMMENT '结构化评分标准',
  `parsed_json`     LONGTEXT     DEFAULT NULL COMMENT 'Word/Excel原始解析JSON',
  `version`         INT          DEFAULT 1,
  `rubric_version`  INT          DEFAULT 1 COMMENT '评分标准版本号',
  `is_active`       TINYINT      DEFAULT 1,
  `created_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### t_ai_task（AI评分任务表）

```sql
CREATE TABLE `t_ai_task` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT,
  `submission_id`    BIGINT       NOT NULL,
  `assignment_id`    BIGINT       NOT NULL,
  `model_name`       VARCHAR(50)  DEFAULT NULL COMMENT 'deepseek/qwen-coder-7b/local',
  `status`           VARCHAR(20)  DEFAULT 'pending' COMMENT 'pending/running/success/failed',
  `prompt_tokens`    INT          DEFAULT 0,
  `completion_tokens` INT         DEFAULT 0,
  `total_tokens`     INT          DEFAULT 0,
  `error_message`    TEXT         DEFAULT NULL,
  `retry_count`      INT          DEFAULT 0,
  `start_time`       DATETIME     DEFAULT NULL,
  `end_time`         DATETIME     DEFAULT NULL,
  `created_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### t_ai_report（AI评分报告表）

```sql
CREATE TABLE `t_ai_report` (
  `id`                 BIGINT         NOT NULL AUTO_INCREMENT,
  `submission_id`      BIGINT         NOT NULL,
  `task_id`            BIGINT         DEFAULT NULL,
  `model_name`         VARCHAR(50)    DEFAULT NULL COMMENT 'deepseek/fallback',
  `total_score`        DECIMAL(5,2)   DEFAULT NULL,
  `score_json`         JSON           DEFAULT NULL COMMENT '分项评分(简化版)',
  `score_detail_json`  LONGTEXT       DEFAULT NULL COMMENT '分项评分(完整版)',
  `file_analysis_json` LONGTEXT       DEFAULT NULL COMMENT '逐文件分析结果',
  `token_usage`        INT            DEFAULT 0 COMMENT 'Token消耗',
  `report_markdown`    LONGTEXT       DEFAULT NULL COMMENT 'Markdown报告全文',
  `suggestion`         TEXT           DEFAULT NULL,
  `created_at`         DATETIME       DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### t_teacher_review（教师批改表）

```sql
CREATE TABLE `t_teacher_review` (
  `id`             BIGINT         NOT NULL AUTO_INCREMENT,
  `submission_id`  BIGINT         NOT NULL,
  `ai_report_id`   BIGINT         DEFAULT NULL,
  `teacher_id`     BIGINT         NOT NULL,
  `final_score`    DECIMAL(5,2)   DEFAULT NULL COMMENT '教师修改后总分',
  `final_comment`  TEXT           DEFAULT NULL,
  `modified_json`  JSON           DEFAULT NULL COMMENT '修改后的评分结构',
  `created_at`     DATETIME       DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### t_grade_publish（成绩发布表）

```sql
CREATE TABLE `t_grade_publish` (
  `id`             BIGINT         NOT NULL AUTO_INCREMENT,
  `submission_id`  BIGINT         NOT NULL,
  `assignment_id`  BIGINT         NOT NULL,
  `student_id`     BIGINT         NOT NULL,
  `final_score`    DECIMAL(5,2)   DEFAULT NULL,
  `report_id`      BIGINT         DEFAULT NULL COMMENT '关联t_teacher_review',
  `is_published`   TINYINT        DEFAULT 0 COMMENT '0=未发布 1=已发布 2=已撤回',
  `published_at`   DATETIME       DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### t_file（文件表）

```sql
CREATE TABLE `t_file` (
  `id`             BIGINT         NOT NULL AUTO_INCREMENT,
  `file_name`      VARCHAR(255)   DEFAULT NULL COMMENT '原始文件名',
  `storage_name`   VARCHAR(255)   DEFAULT NULL COMMENT '重命名: {学号}_{姓名}.zip',
  `file_url`       VARCHAR(500)   DEFAULT NULL COMMENT '存储路径',
  `file_type`      VARCHAR(50)    DEFAULT NULL COMMENT 'submission_zip/rubric_word/rubric_excel',
  `file_size`      BIGINT         DEFAULT NULL,
  `uploader_id`    BIGINT         DEFAULT NULL,
  `created_at`     DATETIME       DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

---

## 7. AI评分核心链路详解

### 7.1 完整流程图（11步）

```
Step 1  学生上传 ZIP
         │
         ▼
Step 2  SpringBoot 接收 → 安全校验(类型/大小/魔数) → 重命名 {学号}_{姓名}.zip → 存储
         │
         ▼
Step 3  ZipInputStream 解压到临时目录
         │
         ▼
Step 4  Files.walkFileTree 递归遍历
         ├─ 白名单过滤代码文件(.java .py .cpp .c .h ...)
         ├─ 黑名单跳过(__pycache__ .git node_modules target .class .jar ...)
         └─ 逐文件 UTF-8 读入内容
         │
         ▼
Step 5  组装 structure_json (file_tree + contents + structure_summary)
         存入 t_project_structure.structure_json
         │
         ▼
Step 6  静态代码分析 (依赖关系)
         ├─ Java: javaparser-core 解析 import + method call
         ├─ Python: ast 模块 (评分时执行)
         └─ C/C++: pycparser (评分时执行)
         生成 dependency_json
         │
         ▼
Step 7  教师勾选提交 → 点击"发起AI评分"
         系统读取 structure_json + rubric_json + dependency_json
         组装 AiTask 任务参数 → LPUSH 到 Redis List
         │
         ▼
Step 8  Python AI Service BRPOP 拉取任务
         组装 Prompt:
           System Prompt (角色指令)
           + rubric_json (评分标准)
           + structure_summary (项目结构)
           + dependency_graph (依赖关系)
           + 各文件 content (代码内容)
           + Output Schema (输出格式约束)
         │
         ▼
Step 9  调用模型评分
         ├─ Try 1: DeepSeek Chat API (120s 超时)
         │   ├─ 成功 → 解析 JSON → 校验结构 → 跳至 Step 10
         │   └─ 失败 → 重试(3s)
         ├─ Try 2: DeepSeek (120s 超时)
         │   └─ 失败 → 重试(10s)
         ├─ Try 3: DeepSeek (120s 超时)
         │   └─ 失败 → 切换 Fallback
         └─ Fallback: HTTP POST → GPU推理服务 (300s 超时)
             ├─ model: Qwen2.5-Coder-7B-Instruct
             ├─ 成功 → 解析 JSON → 标记 model: fallback
             └─ 失败 → AiTask 标记 failed + error_message
         │
         ▼
Step 10 写入 AiReport 表
         - total_score, score_json, score_detail_json
         - file_analysis_json, report_markdown
         - model_name, token_usage
         - 更新 AiTask 状态为 success/failed
         │
         ▼
Step 11 教师复核 → 在线修改 → 推送学生 → (可选) PDF导出
```

### 7.2 Prompt 组装示例（实际送入 AI 的内容）

```
[System]
你是一名严格但公正的编程课程助教。请严格按照以下评分标准对学生代码进行评分。

[Rubric]
{
  "rubric_name": "第一次Java作业评分标准",
  "total_score": 100,
  "dimensions": [
    {"name": "代码规范", "weight": 30, "items": [
      {"name": "变量命名", "max_score": 10, "criteria": "驼峰命名,见名知意"},
      {"name": "缩进格式", "max_score": 10, "criteria": "统一4空格缩进"},
      {"name": "注释完整", "max_score": 10, "criteria": "关键逻辑有注释"}
    ]},
    ...
  ]
}

[Project Structure]
项目包含 2 个 Java 文件，总计 165 行代码。
├── src/main/java/com/example/
│   ├── Main.java (45行, 入口类)
│   └── utils/
│       └── StringUtils.java (120行)

[Dependency Graph]
Main ──方法调用──▶ StringUtils.printHello()
Main ──import──▶ java.util.Scanner

[Code Files]

## 文件: src/main/java/com/example/Main.java
package com.example;
import com.example.utils.StringUtils;
import java.util.Scanner;
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        System.out.print("Enter name: ");
        String name = sc.nextLine();
        StringUtils.printHello(name);
    }
}

## 文件: src/main/java/com/example/utils/StringUtils.java
package com.example.utils;
public class StringUtils {
    public static void printHello(String name) {
        if (name == null) {  // ← AI 将识别这个 null 检查
            System.out.println("Hello, World!");
        } else {
            System.out.println("Hello, " + name + "!");
        }
    }
}

[Output Format]
返回JSON(不要包含其他文字):
{
  "total_score": <0-100>,
  "dimension_scores": [{"name":"","score":0,"max_score":0,"comment":""}],
  "issues": [{"severity":"error|warning|suggestion","file":"","line":0,"description":""}],
  "suggestions": "",
  "report_markdown": ""
}
```

### 7.3 AI 输出示例

```json
{
  "total_score": 85,
  "dimension_scores": [
    {"name": "代码规范", "score": 25, "max_score": 30, "comment": "命名总体规范，Main.java第12行变量名sc过于简短"},
    {"name": "功能完整性", "score": 38, "max_score": 40, "comment": "功能完整，交互逻辑正确"},
    {"name": "创新性", "score": 22, "max_score": 30, "comment": "实现了基本的封装(StringUtils)，但无明显算法优化"}
  ],
  "issues": [
    {"severity": "warning", "file": "src/main/java/com/example/Main.java", "line": 12, "description": "变量'sc'命名过于简短，建议改为'scanner'"},
    {"severity": "suggestion", "file": "src/main/java/com/example/utils/StringUtils.java", "line": 5, "description": "建议为printHello方法添加Javadoc注释"}
  ],
  "suggestions": "整体良好。建议(1)规范变量命名(2)补充关键方法注释(3)可考虑将输入逻辑也抽取到独立方法中",
  "report_markdown": "# 评分报告\n\n## 总分: 85/100\n\n## 分项评分\n| 维度 | 得分 | 满分 |\n|------|------|------|\n| 代码规范 | 25 | 30 |\n| 功能完整性 | 38 | 40 |\n| 创新性 | 22 | 30 |\n\n## 问题列表\n- ⚠️ Main.java:12 变量命名过短\n- 💡 StringUtils.java:5 缺少注释\n\n## 改进建议\n整体良好。建议补充注释并规范命名。"
}
```

---

## 8. API接口设计

### 8.1 RESTful API（SpringBoot端，面向前端）

| 方法 | 路径 | 说明 | 角色 |
|------|------|------|------|
| POST | `/api/v1/auth/login` | 登录，返回JWT | 公开 |
| GET | `/api/v1/assignments?class_id=X` | 获取作业列表 | 学生/教师 |
| POST | `/api/v1/assignments` | 创建作业 | 教师 |
| PATCH | `/api/v1/assignments/{id}/publish` | 发布作业 | 教师 |
| POST | `/api/v1/submissions` | 上传ZIP提交作业 | 学生 |
| GET | `/api/v1/submissions?assignment_id=X` | 获取提交列表 | 教师 |
| POST | `/api/v1/rubrics` | 上传评分标准(Word/Excel) | 教师 |
| GET | `/api/v1/rubrics/{id}/preview` | 预览解析后的Rubric JSON | 教师 |
| POST | `/api/v1/ai-tasks/batch-score` | 批量发起AI评分 | 教师 |
| GET | `/api/v1/ai-tasks?assignment_id=X` | 查看评分任务进度 | 教师 |
| GET | `/api/v1/ai-reports/{submission_id}` | 查看AI评分详情 | 教师 |
| PUT | `/api/v1/teacher-reviews/{id}` | 保存教师修改 | 教师 |
| POST | `/api/v1/grade-publish/push` | 推送成绩给学生 | 教师 |
| GET | `/api/v1/grade-publish/my-grade/{assignment_id}` | 学生查看自己成绩 | 学生 |
| POST | `/api/v1/exports/pdf/single/{submission_id}` | 导出单份PDF | 教师 |
| POST | `/api/v1/exports/pdf/batch` | 批量导出PDF(ZIP) | 教师 |

### 8.2 统一响应格式

```json
{
  "code": 200,
  "message": "success",
  "data": {},
  "timestamp": 1719302400000
}
```

错误码：400(参数错误) / 401(未认证) / 403(无权限) / 404(不存在) / 409(冲突) / 500(服务端) / 503(服务不可用)

### 8.3 AI 服务内部接口（Python FastAPI）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 健康检查，SpringBoot 定期探测 |
| POST | `/internal/callback` | SpringBoot → Python 的回调接口（用于任务结果通知，可选） |

### 8.4 GPU 推理服务接口（局域网）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/v1/chat/completions` | OpenAI 兼容的 Chat Completion 端点 |
| GET | `/health` | GPU 推理服务健康检查 |

---

## 9. 风险分析与缓解策略

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| DeepSeek API 不稳定 | 中 | 高 | 3次重试 + 自动切换局域网推理服务 |
| 本地模型显存不足(RTX3090 24GB预估可跑7B模型) | 低 | 中 | 使用4-bit量化(Qwen2.5-Coder-7B-INT4 <6GB显存) |
| ZIP 解压安全漏洞(压缩炸弹/zipslip) | 低 | 高 | 限制解压后总大小≤100MB；文件名校检防路径穿越；沙箱目录操作 |
| Prompt 超长导致超出模型上下文窗口 | 中 | 中 | 智能截取策略（见2.4节）；DeepSeek 128K context 基本够用 |
| AI 评分公平性争议 | 高 | 中 | AI评分仅为辅助，教师人工复核为必要环节；AI原始分与教师最终分双轨存储 |
| Word/Excel 解析率不足 | 中 | 中 | 提供标准模板供教师下载；解析失败给出明确错误信息 |
| 批量评分耗时 | 中 | 中 | Redis多消费者并行；Python AI Service 支持水平扩展 |
| 单次提交的代码文件超过200个 | 低 | 低 | 过滤黑名单目录、跳过超大文件、summary模式 |

---

## 10. 可扩展性设计

### 10.1 模型可替换

- `application.yml` 中配置模型名称和 API URL，切换模型无需改代码
- GPU 推理服务启动参数指定模型路径，支持 Qwen / DeepSeek-Coder / CodeLlama 等任意 HuggingFace 模型

### 10.2 评分维度可配置

- Rubric JSON 结构支持任意维度和子项数量
- 教师可自行设计评分标准（代码规范/功能/性能/安全/文档/创新...），系统无硬编码维度

### 10.3 新语言支持

- 添加代码文件白名单后缀（在配置文件中追加）
- 添加对应语言的 AST 解析器（实现统一的 `DependencyAnalyzer` 接口）
- Prompt 无需改动

### 10.4 Python AI Service 水平扩展

- 多实例共享同一个 Redis List，自动负载均衡
- 每个实例独立处理任务，某实例宕机不影响其他

### 10.5 存储升级路径

```
本地磁盘 (MVP) → MinIO (单机对象存储) → 阿里云OSS / AWS S3 (生产)
```

通过 SpringBoot 的 `ResourceLoader` 抽象，切换存储时仅改配置，不涉及业务代码。

---

## 11. 开发环境与部署拓扑

### 11.1 开发环境

| 组件 | 配置 |
|------|------|
| 开发机 | macOS / Linux, 16GB RAM |
| SpringBoot | IDEA / VS Code, JDK 17, Maven |
| Python AI Service | PyCharm / VS Code, Python 3.10, venv |
| 数据库 | MySQL 8.0 (Docker / 本地) |
| Redis | Redis 7.x (Docker / 本地) |
| GPU 主机 | Windows 11, 16GB RAM, RTX 3090 (24GB VRAM) |

### 11.2 部署拓扑

```
┌──────────────────────────────────────────────┐
│          Server A (主服务器)                    │
│  ┌──────────┐  ┌──────────┐  ┌────────────┐  │
│  │SpringBoot│  │  MySQL   │  │  Redis      │  │
│  │ :8080    │  │  :3306   │  │  :6379      │  │
│  └──────────┘  └──────────┘  └────────────┘  │
│  ┌──────────┐                                 │
│  │  Python  │  FastAPI 消费者                  │
│  │  :8000   │  (可与SpringBoot同机)             │
│  └──────────┘                                 │
└──────────────┬───────────────────────────────┘
               │ 局域网 HTTP
               ▼
┌──────────────────────────────────────────────┐
│          Server B (GPU 推理)                    │
│  Windows 11 | RTX 3090                        │
│  ┌──────────────────────────────────────┐     │
│  │  inference_server.py :8000           │     │
│  │  Qwen2.5-Coder-7B-Instruct           │     │
│  └──────────────────────────────────────┘     │
└──────────────────────────────────────────────┘
```

---

## 附录 A：术语表

| 术语 | 说明 |
|------|------|
| Rubric | 评分标准/量规，教师定义的评分维度与分值 |
| Structure JSON | ZIP 解压后生成的结构化代码 JSON |
| Dependency Graph | 通过 AST 解析得到的跨文件调用关系图 |
| Fallback | DeepSeek 不可用时自动切换的本地推理模型 |
| Code JSON | 同 Structure JSON |
| Teacher Review | 教师在 AI 评分基础上人工修改后的评分记录 |
| Grade Publish | 教师确认后推送给学生的最终成绩快照 |

---

## 附录 B：版本历史

| 日期 | 版本 | 修改内容 |
|------|------|---------|
| 2026-06-25 | V2.0 | 全新PRD+技术方案：系统架构、核心难点分析(ZIP→JSON→依赖分析→AI评分)、完整DB设计、Prompt工程、API设计、部署拓扑 |
