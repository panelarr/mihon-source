// SPDX-License-Identifier: GPL-3.0-or-later

import { api, unwrap } from "../api/client";
import { ApiError } from "../api/errors";
import type { WhoAmI } from "../api/types";

/**
 * Checks a token with GET /v1/whoami before the UI keeps it. Only the admin token is
 * accepted: client API keys can't use most of the admin API.
 */
export async function verifyAdminToken(token: string): Promise<WhoAmI> {
  const trimmed = token.trim();
  if (trimmed === "") throw new Error("Enter the admin token.");
  let who: WhoAmI;
  try {
    who = await unwrap(api.GET("/v1/whoami", { headers: { Authorization: `Bearer ${trimmed}` } }));
  } catch (error) {
    if (error instanceof ApiError && error.unauthenticated) {
      throw new ApiError(error.status, `The server rejected this token: ${error.message}`, error.grpcCode);
    }
    throw error;
  }
  if (who.kind !== "PRINCIPAL_KIND_ADMIN") {
    throw new Error(
      `This is the API key of client "${who.name || who.clientId || "unknown"}", not the admin token. Sign in with the admin token.`,
    );
  }
  return who;
}

/** Only same-app paths are followed after login, never another origin. */
export function safeRedirect(target: string | undefined): string {
  if (!target?.startsWith("/") || target.startsWith("//") || target.includes("\\") || target.startsWith("/login")) {
    return "/sources";
  }
  return target;
}
