# AI Code Grading Model Service

FastAPI service for AI grading orchestration.

## Run

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
```

## Environment

- `AI_ENABLE_REMOTE=true` enables DeepSeek calls.
- `DEEPSEEK_API_KEY` is required when remote scoring is enabled.
- `DEEPSEEK_BASE_URL` defaults to the Neusoft school model gateway and can be overridden.
- `AI_MODEL` defaults to `DeepSeek/DeepSeek-R1`.
- `LOCAL_AI_BASE_URL` points to an OpenAI-compatible local/school fallback model endpoint.
- `LOCAL_AI_MODEL` defaults to `Qwen2.5-Coder-7B-Instruct`.
- `LOCAL_AI_API_KEY` is optional for fallback endpoints that require authentication.
- `REDIS_URL` may include the Redis password, for example `redis://:password@localhost:6379/0`.
- `AI_WORKER_CONCURRENCY` controls how many Redis grading consumers run in parallel. It defaults to 5 and is limited to 1-10.
- `DEEPSEEK_TIMEOUT_SECONDS` defaults to 600.
- `LOCAL_AI_TIMEOUT_SECONDS` defaults to 600.
- `AI_PROMPT_FULL_CODE_CHAR_LIMIT` defaults to 8000. Projects at or below this size are sent in full.
- `AI_PROMPT_CORE_CODE_CHAR_LIMIT` defaults to 30000. Projects up to this size keep entry/core files first and summarize less important files.
- `AI_PROMPT_SUMMARY_MAX_LINES` defaults to 100. Larger projects keep file metadata plus the first 100 lines of each file.

When remote scoring is enabled, the service calls DeepSeek up to three times, waits 3s and 10s between retries, then calls the fallback model endpoint. If both model paths fail, it returns a deterministic fallback result for teacher review.

`LOCAL_AI_BASE_URL` must point to a real OpenAI-compatible inference service, such as this repository's `inference-service` on port 8002. Do not point it to `model-service` itself; this service orchestrates grading jobs and does not load the local model.

## Redis worker

The service can consume grading jobs from Redis when configured:

```bash
export WORKER_ENABLED=true
export REDIS_URL=redis://localhost:6379/0
export AI_REDIS_QUEUE=ai:grading:tasks
export BACKEND_CALLBACK_URL=http://localhost:8080/internal/ai-callback
uvicorn app.main:app --port 8000
```

被教师结束的任务会通过 `${AI_REDIS_QUEUE}:cancelled` 标记；worker 取到尚未开始执行的任务时会跳过它。

Each worker consumes one Redis task at a time, so `AI_WORKER_CONCURRENCY=5` allows up to five model requests to run concurrently. Changing the value requires recreating the model-service process.

Queue payloads should be JSON objects matching `/score`:

```json
{
  "task_id": 1,
  "submission_id": 10,
  "code_json": {"total_files": 1, "file_tree": []},
  "rubric_json": {"dimensions": [{"name": "功能", "max_score": 100}]}
}
```
