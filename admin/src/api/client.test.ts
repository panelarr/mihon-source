// SPDX-License-Identifier: GPL-3.0-or-later

import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";
import { session } from "../auth/session";
import { server } from "../test/setup";
import { api, authFetch, unwrap } from "./client";
import { ApiError, errorMessage, toApiError } from "./errors";
import { fetchImage } from "./extras";

describe("toApiError", () => {
  it("reads the service's error body", () => {
    const error = toApiError(403, {
      code: 7,
      "grpc-code": "PERMISSION_DENIED",
      message: "this call needs the 'admin' scope",
    });
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(403);
    expect(error.grpcCode).toBe("PERMISSION_DENIED");
    expect(error.message).toBe("this call needs the 'admin' scope");
  });

  it("parses a JSON body given as text", () => {
    expect(toApiError(501, '{"grpc-code":"UNIMPLEMENTED","message":"not yet"}').message).toBe("not yet");
  });

  it("falls back to the status when there's no message", () => {
    expect(toApiError(501, { "grpc-code": "UNIMPLEMENTED" }).message).toBe("Not implemented yet (UNIMPLEMENTED)");
    expect(toApiError(502, "<html>proxy error</html>").message).toBe("Bad gateway (HTTP 502)");
    expect(toApiError(418, undefined).message).toBe("Request failed (HTTP 418)");
    expect(toApiError(500, "plain text failure").message).toBe("plain text failure");
    // What the service answers for a route it doesn't serve yet.
    expect(toApiError(404, "Status: 404\nDescription: Not Found").message).toBe("Not found (HTTP 404)");
  });

  it("knows a 401", () => {
    expect(toApiError(401, {}).unauthenticated).toBe(true);
    expect(toApiError(403, {}).unauthenticated).toBe(false);
  });
});

describe("api", () => {
  it("sends the session token as a bearer header", async () => {
    session.signIn("tok-123");
    let seen: string | null = null;
    server.use(
      http.get("*/v1/sources", ({ request }) => {
        seen = request.headers.get("Authorization");
        return HttpResponse.json({ sources: [] });
      }),
    );
    await expect(unwrap(api.GET("/v1/sources"))).resolves.toEqual({ sources: [] });
    expect(seen).toBe("Bearer tok-123");
  });

  it("throws the API's message for errors", async () => {
    session.signIn("tok");
    server.use(
      http.get("*/v1/admin/extensions", () =>
        HttpResponse.json(
          { code: 12, "grpc-code": "UNIMPLEMENTED", message: "method not implemented" },
          { status: 501 },
        ),
      ),
    );
    const error = await unwrap(api.GET("/v1/admin/extensions")).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(errorMessage(error)).toBe("method not implemented");
    expect((error as ApiError).grpcCode).toBe("UNIMPLEMENTED");
  });

  it("signs out when the session token gets a 401", async () => {
    session.signIn("bad");
    const reasons: unknown[] = [];
    const unsubscribe = session.subscribe((reason) => reasons.push(reason));
    await expect(unwrap(api.GET("/v1/sources"))).rejects.toMatchObject({ status: 401 });
    unsubscribe();
    expect(session.token()).toBeNull();
    expect(reasons).toEqual(["expired"]);
  });

  it("doesn't sign out for a 401 on a token the caller checks itself", async () => {
    session.signIn("good");
    await expect(unwrap(api.GET("/v1/whoami", { headers: { Authorization: "Bearer bad" } }))).rejects.toMatchObject({
      status: 401,
    });
    expect(session.token()).toBe("good");
  });

  it("reports network failures", async () => {
    server.use(http.get("*/v1/sources", () => HttpResponse.error()));
    await expect(unwrap(api.GET("/v1/sources"))).rejects.toMatchObject({ status: 0 });
  });

  it("fills path parameters with dots in their names", async () => {
    session.signIn("tok");
    let path = "";
    server.use(
      http.put("*/v1/admin/profiles/:id", ({ request }) => {
        path = new URL(request.url).pathname;
        return HttpResponse.json({ profile: {} });
      }),
    );
    await unwrap(api.PUT("/v1/admin/profiles/{profile.id}", { params: { path: { "profile.id": "my-id" } }, body: {} }));
    expect(path).toBe("/v1/admin/profiles/my-id");
  });
});

describe("extra endpoints", () => {
  it("fetches images with the bearer header and the ref escaped", async () => {
    session.signIn("tok");
    let url = "";
    let auth: string | null = null;
    server.use(
      http.get("*/v1/sources/:id/image", ({ request }) => {
        url = request.url;
        auth = request.headers.get("Authorization");
        return new HttpResponse("img", { headers: { "Content-Type": "image/png" } });
      }),
    );
    const blob = await fetchImage("1001", "https://example.com/a b.png?x=1&y=2");
    expect(blob.type).toBe("image/png");
    expect(new URL(url).searchParams.get("ref")).toBe("https://example.com/a b.png?x=1&y=2");
    expect(auth).toBe("Bearer tok");
  });

  it("raises API errors from authFetch", async () => {
    session.signIn("tok");
    await expect(authFetch("/v1/sources/1001/image")).rejects.toMatchObject({
      status: 400,
      message: "ref is required",
    });
  });
});
