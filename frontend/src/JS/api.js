const API_BASE = import.meta.env.VITE_API_BASE || "";

export function createApi(token, csrfToken = "", handlers = {}) {
  const authState = { token, csrfToken };
  const authHeaders = () => (authState.token ? { Authorization: `Bearer ${authState.token}` } : {});
  const csrfHeaders = () => (authState.csrfToken ? { "X-CSRF-Token": authState.csrfToken } : {});
  const withRefresh = (fn) => requestWithRefresh(fn, authState, handlers);
  return {
    get: (url) => withRefresh(() => request(url, { headers: authHeaders() })),
    post: (url, body) =>
      withRefresh(() => request(url, {
        method: "POST",
        headers: { ...authHeaders(), ...csrfHeaders(), "Content-Type": "application/json" },
        body: JSON.stringify(body)
      })),
    put: (url, body) =>
      withRefresh(() => request(url, {
        method: "PUT",
        headers: { ...authHeaders(), ...csrfHeaders(), "Content-Type": "application/json" },
        body: JSON.stringify(body)
      })),
    delete: (url, body) =>
      withRefresh(() => request(url, {
        method: "DELETE",
        headers: { ...authHeaders(), ...csrfHeaders(), ...(body === undefined ? {} : { "Content-Type": "application/json" }) },
        ...(body === undefined ? {} : { body: JSON.stringify(body) })
      })),
    patch: (url, body = {}) =>
      withRefresh(() => request(url, {
        method: "PATCH",
        headers: { ...authHeaders(), ...csrfHeaders(), "Content-Type": "application/json" },
        body: JSON.stringify(body)
      })),
    upload: (url, body, options = {}) =>
      withRefresh(() => uploadWithProgress(url, body, {
        headers: { ...authHeaders(), ...csrfHeaders() },
        onProgress: options.onProgress
      })),
    download: (url, body, filename, options = {}) =>
      withRefresh(() => downloadBlob(url, {
        method: "POST",
        headers: { ...authHeaders(), ...csrfHeaders(), "Content-Type": "application/json" },
        body: JSON.stringify(body)
      }, filename, options)),
    downloadGet: (url, filename, options = {}) => withRefresh(() => downloadBlob(url, { headers: authHeaders() }, filename, options))
  };
}

export async function request(url, init = {}) {
  const response = await fetch(API_BASE + url, init);
  const text = await response.text();
  const payload = text ? JSON.parse(text) : null;
  if (!response.ok || (payload && payload.code !== 200)) {
    throw new ApiError(payload?.message || response.statusText || "请求失败", response.status, payload?.code);
  }
  return payload?.data;
}

async function downloadBlob(url, init, filename, options = {}) {
  const response = await fetch(API_BASE + url, init);
  if (!response.ok) {
    const text = await response.text();
    let message = response.statusText || "下载失败";
    try {
      const payload = JSON.parse(text);
      message = payload.message || message;
    } catch {
      message = text || message;
    }
    throw new ApiError(message, response.status);
  }
  const blob = await responseBlob(response, options.onProgress);
  const href = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = href;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(href);
}

async function responseBlob(response, onProgress) {
  const total = Number(response.headers.get("content-length") || 0);
  if (!response.body || !onProgress || total <= 0) {
    const blob = await response.blob();
    onProgress?.(100);
    return blob;
  }
  const reader = response.body.getReader();
  const chunks = [];
  let loaded = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    loaded += value.byteLength;
    onProgress(Math.min(99, Math.round((loaded / total) * 100)));
  }
  onProgress(100);
  return new Blob(chunks, { type: response.headers.get("content-type") || "application/octet-stream" });
}

function uploadWithProgress(url, body, options = {}) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", API_BASE + url);
    for (const [key, value] of Object.entries(options.headers || {})) {
      if (value) xhr.setRequestHeader(key, value);
    }
    xhr.upload.onprogress = (event) => {
      if (!event.lengthComputable || !options.onProgress) return;
      options.onProgress(Math.min(100, Math.round((event.loaded / event.total) * 100)));
    };
    xhr.onload = () => {
      const text = xhr.responseText || "";
      let payload = null;
      try {
        payload = text ? JSON.parse(text) : null;
      } catch {
        reject(new ApiError(text || xhr.statusText || "上传失败", xhr.status));
        return;
      }
      if (xhr.status < 200 || xhr.status >= 300 || (payload && payload.code !== 200)) {
        reject(new ApiError(payload?.message || xhr.statusText || "上传失败", xhr.status, payload?.code));
        return;
      }
      options.onProgress?.(100);
      resolve(payload?.data);
    };
    xhr.onerror = () => reject(new ApiError("网络异常，上传失败", xhr.status));
    xhr.onabort = () => reject(new ApiError("上传已取消", xhr.status));
    xhr.send(body);
  });
}

async function requestWithRefresh(fn, authState, handlers) {
  if (authState.token && handlers.isSessionActive && !handlers.isSessionActive()) {
    handlers.onAuthExpired?.();
    throw new ApiError("登录状态已超过 15 分钟未操作，请重新登录", 401);
  }
  handlers.onActivity?.();
  try {
    return await fn();
  } catch (error) {
    if (isCsrfError(error) && authState.token) {
      const refreshed = await refreshToken(authState, handlers);
      if (refreshed) {
        return fn();
      }
    }
    if (error?.status !== 401 || !authState.token) {
      throw error;
    }
    const refreshed = await refreshToken(authState, handlers);
    if (!refreshed) {
      handlers.onAuthExpired?.();
      throw error;
    }
    return fn();
  }
}

function isCsrfError(error) {
  return error?.status === 403 && /CSRF/i.test(error?.message || "");
}

async function refreshToken(authState, handlers) {
  try {
    const payload = await request("/api/v1/auth/refresh", {
      method: "POST",
      headers: authState.token ? { Authorization: `Bearer ${authState.token}` } : {}
    });
    authState.token = payload.token;
    authState.csrfToken = payload.csrfToken || "";
    handlers.onAuthUpdate?.(payload);
    return true;
  } catch {
    return false;
  }
}

export class ApiError extends Error {
  constructor(message, status, code) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

export function messageOf(error) {
  return error instanceof Error ? error.message : "操作失败";
}
