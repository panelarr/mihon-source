// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * An API failure. Over HTTP the service answers errors with problem details such as
 * `{"code": "PERMISSION_DENIED", "reason": "...", "message": "..."}` (see docs/api.md).
 */
export class ApiError extends Error {
  /** HTTP status, or 0 when the server couldn't be reached. */
  readonly status: number;
  /** gRPC status name, e.g. "NOT_FOUND", when the body carried one. */
  readonly grpcCode: string | undefined;
  /** The error body's other fields, e.g. `reason` or `currentRunId`. */
  readonly details: Record<string, unknown>;

  constructor(status: number, message: string, grpcCode?: string, details: Record<string, unknown> = {}) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.grpcCode = grpcCode;
    this.details = details;
  }

  get unauthenticated(): boolean {
    return this.status === 401 || this.grpcCode === "UNAUTHENTICATED";
  }
}

const statusText: Record<number, string> = {
  400: "Bad request",
  401: "Not signed in",
  403: "Not allowed",
  404: "Not found",
  409: "Conflict",
  413: "Too large",
  429: "Too many requests",
  500: "Server error",
  501: "Not implemented yet",
  502: "Bad gateway",
  503: "Service unavailable",
  504: "Gateway timeout",
};

/** Builds an ApiError from a status and an error body (parsed JSON, text or nothing). */
export function toApiError(status: number, body: unknown): ApiError {
  let parsed = body;
  if (typeof body === "string") {
    try {
      parsed = JSON.parse(body);
    } catch {
      parsed = body;
    }
  }
  if (parsed && typeof parsed === "object") {
    const record = parsed as Record<string, unknown>;
    const named = record["grpc-code"] ?? record.code;
    const grpcCode = typeof named === "string" ? named : undefined;
    const message = typeof record.message === "string" && record.message.trim() !== "" ? record.message : undefined;
    return new ApiError(status, message ?? fallbackMessage(status, grpcCode), grpcCode, record);
  }
  if (typeof parsed === "string" && isReadableText(parsed)) return new ApiError(status, parsed.trim());
  return new ApiError(status, fallbackMessage(status));
}

/** Short plain text is shown as is; HTML pages and the server's bare status pages are not. */
function isReadableText(text: string): boolean {
  const trimmed = text.trim();
  return trimmed !== "" && trimmed.length <= 300 && !trimmed.startsWith("<") && !/^Status: \d+/.test(trimmed);
}

function fallbackMessage(status: number, grpcCode?: string): string {
  const text = statusText[status] ?? "Request failed";
  return grpcCode ? `${text} (${grpcCode})` : `${text} (HTTP ${status})`;
}

/** For fetch() rejections: the network failed before any response. */
export function networkError(cause: unknown): ApiError {
  if (cause instanceof ApiError) return cause;
  if (cause instanceof DOMException && cause.name === "AbortError") throw cause;
  const detail = cause instanceof Error && cause.message ? `: ${cause.message}` : "";
  return new ApiError(0, `Can't reach the server${detail}`);
}

/** A message to show for any thrown value. */
export function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message || error.name;
  if (typeof error === "string") return error;
  return "Something went wrong";
}
