# Local Inference Service

独立 GPU 本地推理服务，和评分编排服务 `model-service` 分开运行。它暴露 OpenAI 兼容接口：

- `GET /health`
- `POST /v1/chat/completions`

默认服务模型名为 `Qwen2.5-Coder-7B-Instruct`。实际加载路径和对外模型名可以分开配置：

- `LOCAL_INFERENCE_MODEL_PATH`：真实模型路径，例如 `Qwen/Qwen2.5-Coder-7B-Instruct` 或本地磁盘路径。
- `LOCAL_INFERENCE_SERVED_MODEL_NAME`：OpenAI 请求中的 `model` 名，需和 `LOCAL_AI_MODEL` 保持一致。

## 本地运行

```bash
cd inference-service
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8002
```

连通性 dry-run：

```bash
INFERENCE_DRY_RUN=true uvicorn app.main:app --port 8002
curl http://127.0.0.1:8002/health
```

## 后端选择

Transformers 后端：

```bash
export LOCAL_INFERENCE_BACKEND=transformers
export LOCAL_INFERENCE_MODEL_PATH=Qwen/Qwen2.5-Coder-7B-Instruct
export LOCAL_INFERENCE_SERVED_MODEL_NAME=Qwen2.5-Coder-7B-Instruct
```

vLLM 后端：

```bash
pip install -r requirements-vllm.txt
export LOCAL_INFERENCE_BACKEND=vllm
export LOCAL_INFERENCE_MODEL_PATH=Qwen/Qwen2.5-Coder-7B-Instruct
export LOCAL_INFERENCE_SERVED_MODEL_NAME=Qwen2.5-Coder-7B-Instruct
```

## 与评分服务联动

Docker Compose 内部使用服务名：

```env
LOCAL_AI_BASE_URL=http://local-inference:8002/v1/chat/completions
LOCAL_AI_MODEL=Qwen2.5-Coder-7B-Instruct
```

宿主机或非容器进程访问：

```env
LOCAL_AI_BASE_URL=http://127.0.0.1:8002/v1/chat/completions
```

不要把 `LOCAL_AI_BASE_URL` 指向 `model-service` 的端口；`model-service` 是评分编排服务，不是加载模型的推理服务。
