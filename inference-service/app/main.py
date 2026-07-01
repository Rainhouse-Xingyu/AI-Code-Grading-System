import os
import time
import uuid
from functools import lru_cache
from typing import Any, Literal

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

DEFAULT_MODEL_NAME = "Qwen2.5-Coder-7B-Instruct"
MODEL_PATH = os.getenv("LOCAL_INFERENCE_MODEL_PATH", os.getenv("LOCAL_INFERENCE_MODEL", DEFAULT_MODEL_NAME))
SERVED_MODEL_NAME = os.getenv("LOCAL_INFERENCE_SERVED_MODEL_NAME", os.getenv("LOCAL_INFERENCE_MODEL", DEFAULT_MODEL_NAME))
BACKEND = os.getenv("LOCAL_INFERENCE_BACKEND", "transformers").lower()
DRY_RUN = os.getenv("INFERENCE_DRY_RUN", "false").lower() == "true"
MAX_MODEL_LEN = int(os.getenv("LOCAL_INFERENCE_MAX_MODEL_LEN", "32768"))
MAX_NEW_TOKENS = int(os.getenv("LOCAL_INFERENCE_MAX_NEW_TOKENS", "8192"))
DEVICE_MAP = os.getenv("LOCAL_INFERENCE_DEVICE_MAP", "auto")
DTYPE = os.getenv("LOCAL_INFERENCE_DTYPE", "auto")

app = FastAPI(title="AI Code Grading Local Inference Service", version="0.1.0")


class ChatMessage(BaseModel):
    role: Literal["system", "user", "assistant"]
    content: str


class ChatCompletionRequest(BaseModel):
    model: str | None = None
    messages: list[ChatMessage] = Field(default_factory=list)
    temperature: float = 0.2
    top_p: float = 0.95
    max_tokens: int | None = None
    max_completion_tokens: int | None = None
    stream: bool = False
    response_format: dict[str, Any] | None = None


@app.get("/health")
async def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "model": SERVED_MODEL_NAME,
        "model_path": MODEL_PATH,
        "backend": BACKEND,
        "dry_run": DRY_RUN,
        "openai_compatible": True,
        "chat_completions": "/v1/chat/completions",
    }


@app.post("/v1/chat/completions")
async def chat_completions(request: ChatCompletionRequest) -> dict[str, Any]:
    if request.stream:
        raise HTTPException(status_code=400, detail="stream=true is not supported by this service")
    if not request.messages:
        raise HTTPException(status_code=400, detail="messages is required")
    requested_model = request.model or SERVED_MODEL_NAME
    if requested_model != SERVED_MODEL_NAME:
        raise HTTPException(status_code=404, detail=f"model not loaded: {requested_model}")

    max_new_tokens = request.max_completion_tokens or request.max_tokens or MAX_NEW_TOKENS
    max_new_tokens = max(1, min(max_new_tokens, MAX_NEW_TOKENS))

    if DRY_RUN:
        content = dry_run_response(request)
    elif BACKEND == "vllm":
        content = generate_with_vllm(request, max_new_tokens)
    elif BACKEND == "transformers":
        content = generate_with_transformers(request, max_new_tokens)
    else:
        raise HTTPException(status_code=500, detail=f"unsupported LOCAL_INFERENCE_BACKEND: {BACKEND}")

    prompt_tokens = estimate_tokens([message.content for message in request.messages])
    completion_tokens = estimate_tokens([content])
    now = int(time.time())
    return {
        "id": f"chatcmpl-{uuid.uuid4().hex}",
        "object": "chat.completion",
        "created": now,
        "model": SERVED_MODEL_NAME,
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": content},
                "finish_reason": "stop",
            }
        ],
        "usage": {
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "total_tokens": prompt_tokens + completion_tokens,
        },
    }


def dry_run_response(request: ChatCompletionRequest) -> str:
    if request.response_format and request.response_format.get("type") == "json_object":
        return (
            '{"total_score":85,"dimension_scores":[{"name":"综合评分","score":85,'
            '"max_score":100,"comment":"本地推理服务 dry-run 响应，仅用于接口连通性测试。"}],'
            '"issues":[{"severity":"suggestion","file":"project","line":1,'
            '"description":"请切换到 transformers 或 vLLM 后端进行真实评分。"}],'
            '"file_analysis":[],"report_markdown":"# 本地推理服务连通性测试\\n\\nDry-run 模式已返回。",'
            '"token_usage":0}'
        )
    return "本地推理服务 dry-run 响应。请切换到 transformers 或 vLLM 后端进行真实推理。"


@lru_cache(maxsize=1)
def transformers_engine() -> tuple[Any, Any]:
    try:
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer
    except ImportError as exc:
        raise HTTPException(
            status_code=503,
            detail="transformers backend requires torch and transformers to be installed",
        ) from exc

    tokenizer = AutoTokenizer.from_pretrained(MODEL_PATH, trust_remote_code=True)
    torch_dtype = "auto" if DTYPE == "auto" else getattr(torch, DTYPE)
    model = AutoModelForCausalLM.from_pretrained(
        MODEL_PATH,
        torch_dtype=torch_dtype,
        device_map=DEVICE_MAP,
        trust_remote_code=True,
    )
    model.eval()
    return tokenizer, model


def generate_with_transformers(request: ChatCompletionRequest, max_new_tokens: int) -> str:
    try:
        import torch
    except ImportError as exc:
        raise HTTPException(status_code=503, detail="torch is required for transformers backend") from exc
    tokenizer, model = transformers_engine()
    prompt = chat_prompt(tokenizer, request.messages)
    inputs = tokenizer(prompt, return_tensors="pt", truncation=True, max_length=MAX_MODEL_LEN)
    inputs = {key: value.to(model.device) for key, value in inputs.items()}
    with torch.inference_mode():
        output = model.generate(
            **inputs,
            max_new_tokens=max_new_tokens,
            temperature=request.temperature,
            top_p=request.top_p,
            do_sample=request.temperature > 0,
            pad_token_id=tokenizer.eos_token_id,
        )
    generated = output[0][inputs["input_ids"].shape[-1] :]
    return tokenizer.decode(generated, skip_special_tokens=True).strip()


@lru_cache(maxsize=1)
def vllm_engine() -> tuple[Any, Any, Any]:
    try:
        from vllm import LLM, SamplingParams
    except ImportError as exc:
        raise HTTPException(status_code=503, detail="vLLM backend requires vllm to be installed") from exc
    llm = LLM(model=MODEL_PATH, trust_remote_code=True, max_model_len=MAX_MODEL_LEN)
    tokenizer = llm.get_tokenizer()
    return llm, tokenizer, SamplingParams


def generate_with_vllm(request: ChatCompletionRequest, max_new_tokens: int) -> str:
    llm, tokenizer, sampling_params_cls = vllm_engine()
    prompt = chat_prompt(tokenizer, request.messages)
    params = sampling_params_cls(
        temperature=request.temperature,
        top_p=request.top_p,
        max_tokens=max_new_tokens,
    )
    outputs = llm.generate([prompt], params)
    return outputs[0].outputs[0].text.strip()


def chat_prompt(tokenizer: Any, messages: list[ChatMessage]) -> str:
    payload = [message.model_dump() for message in messages]
    if hasattr(tokenizer, "apply_chat_template"):
        return tokenizer.apply_chat_template(payload, tokenize=False, add_generation_prompt=True)
    return "\n".join(f"{message.role}: {message.content}" for message in messages) + "\nassistant:"


def estimate_tokens(values: list[str]) -> int:
    return max(1, sum(len(value or "") for value in values) // 4)
