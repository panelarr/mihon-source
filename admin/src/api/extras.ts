// SPDX-License-Identifier: GPL-3.0-or-later

// Endpoints the service serves outside the protos (so not in the OpenAPI document).
import { authFetch, json } from "./client";
import type { InstallExtensionResponse } from "./types";

/** `GET /v1/sources/{sourceId}/image?ref=`: a page ref or an image URL, as a Blob. */
export async function fetchImage(sourceId: string, ref: string, signal?: AbortSignal): Promise<Blob> {
  const path = `/v1/sources/${encodeURIComponent(sourceId)}/image?ref=${encodeURIComponent(ref)}`;
  const response = await authFetch(path, signal ? { signal } : {});
  return response.blob();
}

/** `POST /v1/admin/extensions/upload`: multipart form with the file in field `file`. */
export async function uploadExtension(file: File): Promise<InstallExtensionResponse> {
  const form = new FormData();
  form.append("file", file, file.name);
  const response = await authFetch("/v1/admin/extensions/upload", { method: "POST", body: form });
  return json<InstallExtensionResponse>(response);
}
