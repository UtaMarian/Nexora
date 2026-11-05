// Thin fetch wrapper: same-origin session cookie, CSRF header on writes, problem-detail errors.

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}

function cookie(name: string): string | undefined {
  return document.cookie
    .split("; ")
    .find((c) => c.startsWith(name + "="))
    ?.slice(name.length + 1);
}

function csrfHeaders(method: string): Record<string, string> {
  if (method === "GET" || method === "HEAD") return {};
  const token = cookie("XSRF-TOKEN");
  return token ? { "X-XSRF-TOKEN": decodeURIComponent(token) } : {};
}

/** True while someone reads public content without signing in. */
let visitor = false;
export const setVisitor = (v: boolean) => {
  visitor = v;
};

async function toError(res: Response, method = "POST"): Promise<ApiError> {
  let code = "http_" + res.status;
  let message = res.statusText || "Request failed";
  try {
    const body = await res.json();
    code = body.code ?? code;
    message = body.detail ?? body.message ?? message;
  } catch {
    /* not JSON */
  }
  // a visitor is sent to sign in for a page or an action, not for something a public page merely left out
  if (res.status === 401 && (!visitor || code === "sign_in_required" || method !== "GET")) {
    window.dispatchEvent(new Event("nexora:unauthorized"));
  }
  return new ApiError(res.status, code, message);
}

export async function api<T>(path: string, init: { method?: string; body?: unknown } = {}): Promise<T> {
  const method = init.method ?? "GET";
  const headers: Record<string, string> = { Accept: "application/json", ...csrfHeaders(method) };
  let body: BodyInit | undefined;
  if (init.body !== undefined) {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(init.body);
  }
  const res = await fetch(path, { method, headers, body, credentials: "same-origin" });
  if (!res.ok) throw await toError(res, method);
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Makes sure the XSRF-TOKEN cookie exists before the first write. */
export async function primeCsrf(): Promise<void> {
  if (!cookie("XSRF-TOKEN")) await fetch("/api/v1/auth/csrf", { credentials: "same-origin" });
}

/** Multipart upload with progress reporting (fetch has no upload progress). */
export function uploadFiles(
  parentId: string,
  files: File[],
  overwrite: boolean,
  onProgress?: (fraction: number) => void,
): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const form = new FormData();
    files.forEach((f) => form.append("file", f, f.name));
    const xhr = new XMLHttpRequest();
    xhr.open("POST", `/api/v1/nodes/${parentId}/files?overwrite=${overwrite}`);
    xhr.withCredentials = true;
    Object.entries(csrfHeaders("POST")).forEach(([k, v]) => xhr.setRequestHeader(k, v));
    xhr.setRequestHeader("Accept", "application/json");
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress?.(e.loaded / e.total);
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(xhr.responseText ? JSON.parse(xhr.responseText) : undefined);
        return;
      }
      let code = "http_" + xhr.status;
      let message = "Upload failed";
      try {
        const b = JSON.parse(xhr.responseText);
        code = b.code ?? code;
        message = b.detail ?? message;
      } catch {
        /* ignore */
      }
      reject(new ApiError(xhr.status, code, message));
    };
    xhr.onerror = () => reject(new ApiError(0, "network", "Network error during upload"));
    xhr.send(form);
  });
}

/** Posts one file as multipart/form-data (field "file") and returns the JSON answer. */
export async function postFile<T>(path: string, file: File, method = "POST"): Promise<T> {
  const form = new FormData();
  form.append("file", file, file.name);
  const res = await fetch(path, {
    method,
    headers: { Accept: "application/json", ...csrfHeaders(method) },
    body: form,
    credentials: "same-origin",
  });
  if (!res.ok) throw await toError(res);
  return (await res.json()) as T;
}

export const contentUrl = (id: string, opts: { versionId?: string; download?: boolean } = {}) => {
  const q = new URLSearchParams();
  if (opts.versionId) q.set("versionId", opts.versionId);
  if (opts.download) q.set("download", "true");
  const qs = q.toString();
  return `/api/v1/nodes/${id}/content${qs ? "?" + qs : ""}`;
};

/** Browser route for a node's server-relative path (/sites/hr/Shared Documents → same, URL-encoded). */
export const routeFor = (path: string) => path.split("/").map(encodeURIComponent).join("/");
