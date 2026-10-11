// SPDX-License-Identifier: GPL-3.0-or-later

// MSW handlers that answer every API endpoint from in-memory fake data, with the same
// JSON shapes and error bodies as the service. Used by mock mode and the tests.
import { delay, HttpResponse, http, type JsonBodyType } from "msw";
import type {
  Assignment,
  AvailableExtension,
  Client,
  Extension,
  FilterValue,
  JsonObject,
  NetworkProfile,
  Solver,
  Store,
} from "../api/types";
import {
  failingSourceIds,
  fakeHash,
  MOCK_ADMIN_TOKEN,
  mockAvailable,
  mockChapters,
  mockClients,
  mockExtensions,
  mockFilters,
  mockPages,
  mockProfiles,
  mockSeries,
  mockSettingsSchema,
  mockSettingsValues,
  mockSolvers,
  mockSources,
  mockStores,
  PAGE_COUNT,
  PAGE_SIZE,
  seriesTitle,
} from "./data";

type Db = {
  extensions: Extension[];
  stores: Store[];
  available: AvailableExtension[];
  clients: Client[];
  solvers: Solver[];
  profiles: NetworkProfile[];
  /** Stored proxy passwords by profile id (never returned). */
  passwords: Map<string, string>;
  defaultProfileId: string;
  assignments: Map<string, string>;
  pushed: Map<string, string>;
  settings: Map<string, JsonObject>;
  nextId: number;
};

export function createDb(): Db {
  const profiles = mockProfiles();
  return {
    extensions: mockExtensions(),
    stores: mockStores(),
    available: mockAvailable(),
    clients: mockClients(),
    solvers: mockSolvers(),
    profiles,
    passwords: new Map([["via-proxy", "fake-password"]]),
    defaultProfileId: "polite",
    assignments: new Map([["2001", "via-proxy"]]),
    pushed: new Map([["1002", "host-pushed-example"]]),
    settings: new Map([["1001", structuredClone(mockSettingsValues)]]),
    nextId: 1,
  };
}

const grpcCodes = {
  INVALID_ARGUMENT: [3, 400],
  NOT_FOUND: [5, 404],
  ALREADY_EXISTS: [6, 409],
  PERMISSION_DENIED: [7, 403],
  FAILED_PRECONDITION: [9, 400],
  UNIMPLEMENTED: [12, 501],
  UNAVAILABLE: [14, 503],
  UNAUTHENTICATED: [16, 401],
} as const;

export function grpcError(code: keyof typeof grpcCodes, message: string) {
  const [number, status] = grpcCodes[code];
  return HttpResponse.json({ code: number, "grpc-code": code, message }, { status });
}

const ok = (body: JsonBodyType) => HttpResponse.json(body);

/** Mirrors the service's auth: the admin token, client keys ("msk_…") or nothing. */
function principal(request: Request): "admin" | "client" | null {
  const header = request.headers.get("Authorization") ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";
  if (!token || token === "bad") return null;
  return token.startsWith("msk_") ? "client" : "admin";
}

function guard(request: Request, admin: boolean) {
  const who = principal(request);
  if (!who) return grpcError("UNAUTHENTICATED", "missing or invalid token");
  if (admin && who !== "admin") return grpcError("PERMISSION_DENIED", "this call needs the 'admin' scope");
  return null;
}

function newKey(): string {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
  let key = "msk_";
  for (let i = 0; i < 40; i++) key += chars[Math.floor(Math.random() * chars.length)];
  return key;
}

const now = () => new Date().toISOString();

function sourceById(id: string) {
  return mockSources.find((source) => source.id === id);
}

function browse(sourceId: string, page: number, matches: (n: number) => boolean = () => true) {
  const all = Array.from({ length: PAGE_SIZE * PAGE_COUNT }, (_, i) => i + 1).filter(matches);
  const start = (Math.max(page, 1) - 1) * PAGE_SIZE;
  const slice = all.slice(start, start + PAGE_SIZE);
  return { series: slice.map((n) => mockSeries(sourceId, n)), hasNextPage: start + PAGE_SIZE < all.length };
}

function seriesNumber(key: string): number | null {
  const match = /^\/series\/\d+-(\d+)$/.exec(key);
  return match?.[1] ? Number(match[1]) : null;
}

/** An SVG placeholder with the image ref written on it. */
function fakeImage(ref: string): string {
  const hue = Number.parseInt(fakeHash(ref).slice(0, 4), 16) % 360;
  const label = ref.replace(/[<>&"]/g, "").slice(-36);
  return `<svg xmlns="http://www.w3.org/2000/svg" width="400" height="600" viewBox="0 0 400 600">
<rect width="400" height="600" fill="hsl(${hue} 45% 55%)"/>
<text x="200" y="290" font-family="sans-serif" font-size="28" fill="#fff" text-anchor="middle">MOCK IMAGE</text>
<text x="200" y="330" font-family="monospace" font-size="14" fill="#fff" text-anchor="middle">${label}</text>
</svg>`;
}

function assignmentFor(db: Db, sourceId: string): Assignment {
  const pushed = db.pushed.get(sourceId);
  const assigned = db.assignments.get(sourceId) ?? "";
  if (pushed) return { sourceId, profileId: assigned, pushedProfileName: pushed, effective: "PROFILE_ORIGIN_PUSHED" };
  if (assigned)
    return { sourceId, profileId: assigned, effective: "PROFILE_ORIGIN_ASSIGNED", effectiveProfileId: assigned };
  if (db.defaultProfileId)
    return { sourceId, effective: "PROFILE_ORIGIN_DEFAULT", effectiveProfileId: db.defaultProfileId };
  return { sourceId, effective: "PROFILE_ORIGIN_NONE" };
}

function publicProfile(db: Db, profile: NetworkProfile): NetworkProfile {
  if (!profile.proxy) return profile;
  const { password: _password, ...proxy } = profile.proxy;
  return { ...profile, proxy: { ...proxy, hasPassword: db.passwords.has(profile.id ?? "") } };
}

function installFake(db: Db, name: string, origin: Extension["origin"]): Extension {
  const slug =
    name
      .replace(/\.(apk|jar)$/i, "")
      .replace(/[^a-z0-9]+/gi, "")
      .toLowerCase() || "uploaded";
  const extension: Extension = {
    pkgName: `eu.example.extension.${slug}`,
    name: `Installed ${slug}`,
    versionName: "1.0.0",
    versionCode: "1",
    libVersion: "1.5",
    format: name.toLowerCase().endsWith(".jar") ? "EXTENSION_FORMAT_JAR" : "EXTENSION_FORMAT_APK",
    contentWarning: "CONTENT_WARNING_SAFE",
    status: "EXTENSION_STATUS_LOADED",
    sources: [],
    signingFingerprint: fakeHash(`${slug}-signer`),
    sha256: fakeHash(`${slug}-file`),
    origin,
    installedAt: now(),
  };
  db.extensions = [...db.extensions.filter((e) => e.pkgName !== extension.pkgName), extension];
  return extension;
}

/** Handlers over `db`. `latency` adds a delay in milliseconds to feel like a network. */
export function createHandlers(db: Db = createDb(), latency = 0) {
  const wait = () => (latency > 0 ? delay(latency) : Promise.resolve());
  const base = "*/v1";

  return [
    // ----- panelarr.source.v1 -----------------------------------------------------
    http.get(`${base}/health`, () => ok({ status: "HEALTH_STATUS_DEGRADED", message: "1 extension failed to load" })),

    http.get(
      `${base}/info`,
      ({ request }) =>
        guard(request, false) ??
        ok({
          info: {
            id: "mihon-source",
            name: "mihon-source (mock)",
            version: "0.0.0-mock",
            contracts: ["panelarr.source.v1"],
            capabilities: ["PLUGIN_CAPABILITY_NETWORK_PROFILES", "PLUGIN_CAPABILITY_DYNAMIC_SOURCES"],
          },
        }),
    ),

    http.get(`${base}/sources`, async ({ request }) => {
      await wait();
      const lang = new URL(request.url).searchParams.get("lang");
      return guard(request, false) ?? ok({ sources: mockSources.filter((s) => !lang || s.lang === lang) });
    }),

    http.get(`${base}/sources/:sourceId`, async ({ request, params }) => {
      await wait();
      const denied = guard(request, false);
      if (denied) return denied;
      const source = sourceById(String(params.sourceId));
      return source ? ok({ source }) : grpcError("NOT_FOUND", `no source with id ${params.sourceId}`);
    }),

    http.post(`${base}/admin/checks`, async ({ request }) => {
      await wait();
      const denied = guard(request, true);
      if (denied) return denied;
      const body = (await request.json()) as { sourceIds?: string[] };
      const ids = body.sourceIds?.length ? body.sourceIds : mockSources.map((s) => s.id ?? "");
      return ok({
        results: ids.map((id) => {
          const failing = failingSourceIds.has(id);
          return {
            sourceId: id,
            ok: !failing,
            checkedAt: now(),
            steps: [
              failing
                ? {
                    step: "CHECK_STEP_POPULAR",
                    ok: false,
                    reason: "SOURCE_DOWN",
                    message: "the source answered HTTP 503",
                  }
                : { step: "CHECK_STEP_POPULAR", ok: true, detail: "20 series", duration: "0.300s" },
              failing
                ? { step: "CHECK_STEP_IMAGE", skipped: true }
                : { step: "CHECK_STEP_IMAGE", ok: true, detail: "image/webp, 65536 bytes read", duration: "0.200s" },
            ],
          };
        }),
      });
    }),

    http.put(`${base}/admin/sources/:sourceId/health-check`, async ({ request }) => {
      await wait();
      return guard(request, true) ?? ok({});
    }),

    http.get(`${base}/sources/:sourceId/filters`, async ({ request }) => {
      await wait();
      return guard(request, false) ?? ok({ filters: mockFilters });
    }),

    ...(["popular", "latest"] as const).map((kind) =>
      http.get(`${base}/sources/:sourceId/${kind}`, async ({ request, params }) => {
        await wait();
        const denied = guard(request, false);
        if (denied) return denied;
        const sourceId = String(params.sourceId);
        if (!sourceById(sourceId)) return grpcError("NOT_FOUND", `no source with id ${sourceId}`);
        if (failingSourceIds.has(sourceId))
          return grpcError("UNAVAILABLE", "SOURCE_DOWN: the upstream answered HTTP 503 (mock error)");
        const page = Number(new URL(request.url).searchParams.get("page") ?? 1);
        // Latest lists in a different order than popular.
        const result = browse(sourceId, page);
        if (kind === "latest") result.series.reverse();
        return ok(result);
      }),
    ),

    http.post(`${base}/sources/:sourceId/search`, async ({ request, params }) => {
      await wait();
      const denied = guard(request, false);
      if (denied) return denied;
      const sourceId = String(params.sourceId);
      if (failingSourceIds.has(sourceId))
        return grpcError("UNAVAILABLE", "CLOUDFLARE_CHALLENGE: blocked by an anti-bot challenge (mock error)");
      const body = (await request.json()) as { page?: number; query?: string; filters?: FilterValue[] };
      const query = (body.query ?? "").toLowerCase();
      // Each changed filter halves the results, so filtering visibly does something.
      const filterCount = body.filters?.length ?? 0;
      return ok(
        browse(
          sourceId,
          body.page ?? 1,
          (n) => seriesTitle(n).toLowerCase().includes(query) && n % 2 ** filterCount === 0,
        ),
      );
    }),

    http.get(`${base}/sources/:sourceId/series`, async ({ request, params }) => {
      await wait();
      const denied = guard(request, false);
      if (denied) return denied;
      const key = new URL(request.url).searchParams.get("key") ?? "";
      const n = seriesNumber(key);
      return n === null
        ? grpcError("NOT_FOUND", `NOT_FOUND: no series with key ${key}`)
        : ok({ series: mockSeries(String(params.sourceId), n, true) });
    }),

    http.get(`${base}/sources/:sourceId/chapters`, async ({ request }) => {
      await wait();
      const seriesKey = new URL(request.url).searchParams.get("seriesKey") ?? "";
      return guard(request, false) ?? ok({ chapters: mockChapters(seriesKey) });
    }),

    http.get(`${base}/sources/:sourceId/pages`, async ({ request }) => {
      await wait();
      const chapterKey = new URL(request.url).searchParams.get("chapterKey") ?? "";
      return guard(request, false) ?? ok({ pages: mockPages(chapterKey) });
    }),

    http.get(`${base}/sources/:sourceId/image`, async ({ request }) => {
      await wait();
      const denied = guard(request, false);
      if (denied) return denied;
      const ref = new URL(request.url).searchParams.get("ref") ?? "";
      if (!ref) return grpcError("INVALID_ARGUMENT", "ref is required");
      return new HttpResponse(fakeImage(ref), { headers: { "Content-Type": "image/svg+xml" } });
    }),

    // Icons: a generated image for the first sources and extensions, none for the rest.
    http.get(`${base}/sources/:sourceId/icon`, async ({ request, params }) => {
      const denied = guard(request, false);
      if (denied) return denied;
      return new HttpResponse(fakeImage(`icon-${params.sourceId}`), { headers: { "Content-Type": "image/svg+xml" } });
    }),
    http.get(`${base}/admin/extensions/:pkgName/icon`, async ({ request, params }) => {
      const denied = guard(request, true);
      if (denied) return denied;
      if (String(params.pkgName).endsWith("broken")) return grpcError("NOT_FOUND", "no icon");
      return new HttpResponse(fakeImage(`icon-${params.pkgName}`), { headers: { "Content-Type": "image/svg+xml" } });
    }),
    http.get(`${base}/admin/stores/:storeId/extensions/:pkgName/icon`, async ({ request, params }) => {
      const denied = guard(request, true);
      if (denied) return denied;
      return new HttpResponse(fakeImage(`icon-${params.pkgName}`), { headers: { "Content-Type": "image/svg+xml" } });
    }),

    http.get(`${base}/sources/:sourceId/settings/schema`, async ({ request, params }) => {
      await wait();
      const denied = guard(request, false);
      if (denied) return denied;
      return ok({ schema: params.sourceId === "1001" ? mockSettingsSchema : { type: "object", properties: {} } });
    }),

    http.get(`${base}/sources/:sourceId/settings`, async ({ request, params }) => {
      await wait();
      return guard(request, false) ?? ok({ values: db.settings.get(String(params.sourceId)) ?? {} });
    }),

    http.patch(`${base}/sources/:sourceId/settings`, async ({ request, params }) => {
      await wait();
      const denied = guard(request, false);
      if (denied) return denied;
      const body = (await request.json()) as { values?: JsonObject };
      const sourceId = String(params.sourceId);
      if (body.values?.mirror && typeof body.values.mirror === "string" && !body.values.mirror.startsWith("https://")) {
        return grpcError("INVALID_ARGUMENT", "mirror: must start with https://");
      }
      const next = { ...(db.settings.get(sourceId) ?? {}), ...(body.values ?? {}) };
      db.settings.set(sourceId, next);
      return ok({ values: next });
    }),

    // ----- MihonClientsService ----------------------------------------------------
    http.get(`${base}/whoami`, ({ request }) => {
      const who = principal(request);
      if (!who) return grpcError("UNAUTHENTICATED", "missing or invalid token");
      if (who === "client")
        return ok({
          kind: "PRINCIPAL_KIND_CLIENT",
          clientId: "c1a2b3c4",
          name: "panelarr-server",
          scopes: ["sources:read"],
        });
      return ok({ kind: "PRINCIPAL_KIND_ADMIN", name: "admin", scopes: ["admin"] });
    }),

    http.get(`${base}/admin/clients`, async ({ request }) => {
      await wait();
      return guard(request, true) ?? ok({ clients: db.clients });
    }),

    http.post(`${base}/admin/clients`, async ({ request }) => {
      await wait();
      const denied = guard(request, true);
      if (denied) return denied;
      const body = (await request.json()) as { name?: string; scopes?: string[] };
      if (!body.name?.trim()) return grpcError("INVALID_ARGUMENT", "name is required");
      if (!body.scopes?.length) return grpcError("INVALID_ARGUMENT", "at least one scope is required");
      const apiKey = newKey();
      const client: Client = {
        id: `mock${db.nextId++}`,
        name: body.name.trim(),
        scopes: body.scopes,
        keyPrefix: apiKey.slice(0, 9),
        createdAt: now(),
      };
      db.clients = [...db.clients, client];
      return ok({ client, apiKey });
    }),

    http.patch(`${base}/admin/clients/:clientId`, async ({ request, params }) => {
      await wait();
      const denied = guard(request, true);
      if (denied) return denied;
      const body = (await request.json()) as { name?: string; scopes?: string[] };
      const client = db.clients.find((c) => c.id === params.clientId);
      if (!client) return grpcError("NOT_FOUND", `no client with id ${params.clientId}`);
      if (body.name) client.name = body.name;
      if (body.scopes?.length) client.scopes = body.scopes;
      return ok({ client });
    }),

    http.post(`${base}/admin/clients/:clientId/rotate`, async ({ request, params }) => {
      await wait();
      const denied = guard(request, true);
      if (denied) return denied;
      const client = db.clients.find((c) => c.id === params.clientId);
      if (!client) return grpcError("NOT_FOUND", `no client with id ${params.clientId}`);
      const apiKey = newKey();
      client.keyPrefix = apiKey.slice(0, 9);
      client.rotatedAt = now();
      return ok({ client, apiKey });
    }),

    http.delete(`${base}/admin/clients/:clientId`, async ({ request, params }) => {
      await wait();
      const denied = guard(request, true);
      if (denied) return denied;
      if (!db.clients.some((c) => c.id === params.clientId))
        return grpcError("NOT_FOUND", `no client with id ${params.clientId}`);
      db.clients = db.clients.filter((c) => c.id !== params.clientId);
      return ok({});
    }),

    // ----- MihonAdminService ------------------------------------------------------
    http.get(`${base}/admin/extensions`, async ({ request }) => {
      await wait();
      return guard(request, true) ?? ok({ extensions: db.extensions });
    }),

    http.post(`${base}/admin/extensions/upload`, async ({ request }) => {
      await wait();
      const denied = guard(request, true);
      if (denied) return denied;
      const form = await request.formData();
      const file = form.get("file");
      if (!(file instanceof File)) return grpcError("INVALID_ARGUMENT", "the form needs a 'file' field");
      if (!/\.(apk|jar)$/i.test(file.name))
        return grpcError("INVALID_ARGUMENT", "only .jar and .apk files can be installed");
      return ok({ extension: installFake(db, file.name, { upload: file.name }) });
    }),

    http.post(`${base}/admin/extensions`, async ({ request }) => {
      await wait();
      const denied = guard(request, true);
      if (denied) return denied;
      const body = (await request.json()) as { url?: string; expectedSha256?: string };
      if (!body.url) return grpcError("INVALID_ARGUMENT", "url or data is required");
      if (body.expectedSha256 && body.expectedSha256 !== fakeHash("any")) {
        return grpcError("FAILED_PRECONDITION", "the file's SHA-256 doesn't match the expected value");
      }
      const name = body.url.split("/").pop() ?? "extension.apk";
      return ok({ extension: installFake(db, name, { url: body.url }) });
    }),

    http.get(`${base}/admin/extensions/:pkgName`, async ({ request, params }) => {
      await wait();
      const denied = guard(request, true);
      if (denied) return denied;
      const extension = db.extensions.find((e) => e.pkgName === params.pkgName);
      return extension ? ok({ extension }) : grpcError("NOT_FOUND", `no extension ${params.pkgName}`);
    }),

    http.delete(`${base}/admin/extensions/:pkgName`, async ({ request, params }) => {
      await wait();
      const denied = guard(request, true);
      if (denied) return denied;
      if (!db.extensions.some((e) => e.pkgName === params.pkgName))
        return grpcError("NOT_FOUND", `no extension ${params.pkgName}`);
      db.extensions = db.extensions.filter((e) => e.pkgName !== params.pkgName);
      for (const ext of db.available)
        if (ext.pkgName === params.pkgName) Object.assign(ext, { installed: false, updateAvailable: false });
      return ok({});
    }),

    http.post(`${base}/admin/extensions/:pkgName/reload`, async ({ request, params }) => {
      await wait();
      const denied = guard(request, true);
      if (denied) return denied;
      const extension = db.extensions.find((e) => e.pkgName === params.pkgName);
      if (!extension) return grpcError("NOT_FOUND", `no extension ${params.pkgName}`);
      if (extension.status === "EXTENSION_STATUS_ERROR")
        return grpcError("FAILED_PRECONDITION", extension.loadError ?? "the extension failed to load");
      return ok({ extension });
    }),

    // ----- MihonStoresService -----------------------------------------------------
    http.get(`${base}/admin/stores`, async ({ request }) => {
      await wait();
      return guard(request, true) ?? ok({ stores: db.stores });
    }),

    http.post(`${base}/admin/stores`, async ({ request }) => {
      await wait();
      const denied = guard(request, true);
      if (denied) return denied;
      const body = (await request.json()) as { url?: string; expectedSigningKeyFingerprint?: string };
      if (!body.url) return grpcError("INVALID_ARGUMENT", "url is required");
      const fingerprint = fakeHash(body.url);
      if (body.expectedSigningKeyFingerprint && body.expectedSigningKeyFingerprint !== fingerprint) {
        return grpcError("FAILED_PRECONDITION", `the store announces signing key ${fingerprint}, not the expected one`);
      }
      const id = new URL(body.url).hostname.replace(/\./g, "-");
      if (db.stores.some((s) => s.id === id)) return grpcError("ALREADY_EXISTS", `store ${id} is already added`);
      const store: Store = {
        id,
        url: body.url,
        name: `Store at ${new URL(body.url).hostname}`,
        signingKeyFingerprint: fingerprint,
        format: "STORE_FORMAT_EXTENSION_STORE",
        addedAt: now(),
        refreshedAt: now(),
        extensionCount: 0,
      };
      db.stores = [...db.stores, store];
      return ok({ store });
    }),

    http.delete(`${base}/admin/stores/:storeId`, async ({ request, params }) => {
      await wait();
      const denied = guard(request, true);
      if (denied) return denied;
      db.stores = db.stores.filter((s) => s.id !== params.storeId);
      db.available = db.available.filter((e) => e.storeId !== params.storeId);
      return ok({});
    }),

    http.post(`${base}/admin/stores/:storeId/refresh`, async ({ request, params }) => {
      await wait();
      const denied = guard(request, true);
      if (denied) return denied;
      const store = db.stores.find((s) => s.id === params.storeId);
      if (!store) return grpcError("NOT_FOUND", `no store ${params.storeId}`);
      if (store.format === "STORE_FORMAT_LEGACY_INDEX") {
        store.lastError = `Fetching the index failed: HTTP 503 (mock error, ${now()})`;
        return grpcError("UNAVAILABLE", store.lastError);
      }
      store.refreshedAt = now();
      store.lastError = "";
      return ok({ store });
    }),

    http.get(`${base}/admin/stores/available`, async ({ request }) => {
      await wait();
      const denied = guard(request, true);
      if (denied) return denied;
      const params = new URL(request.url).searchParams;
      const query = (params.get("query") ?? "").toLowerCase();
      const lang = params.get("lang");
      const storeId = params.get("storeId");
      const updatesOnly = params.get("updatesOnly") === "true";
      const extensions = db.available.filter(
        (e) =>
          (!storeId || e.storeId === storeId) &&
          (!lang || e.sources?.some((s) => s.lang === lang)) &&
          (!updatesOnly || e.updateAvailable) &&
          (!query ||
            (e.name ?? "").toLowerCase().includes(query) ||
            e.sources?.some((s) => (s.name ?? "").toLowerCase().includes(query))),
      );
      return ok({ extensions });
    }),

    http.post(`${base}/admin/stores/:storeId/extensions/:pkgName/install`, async ({ request, params }) => {
      await wait();
      const denied = guard(request, true);
      if (denied) return denied;
      const available = db.available.find((e) => e.storeId === params.storeId && e.pkgName === params.pkgName);
      if (!available) return grpcError("NOT_FOUND", `store ${params.storeId} has no ${params.pkgName}`);
      if (!available.supported)
        return grpcError("FAILED_PRECONDITION", `extensions-lib ${available.libVersion} isn't supported`);
      Object.assign(available, {
        installed: true,
        updateAvailable: false,
        installedVersionName: available.versionName,
        installedVersionCode: available.versionCode,
      });
      const extension: Extension = {
        pkgName: available.pkgName,
        name: available.name,
        versionName: available.versionName,
        versionCode: available.versionCode,
        libVersion: available.libVersion,
        format: available.hasJar ? "EXTENSION_FORMAT_JAR" : "EXTENSION_FORMAT_APK",
        contentWarning: available.contentWarning,
        status: "EXTENSION_STATUS_LOADED",
        sources: (available.sources ?? []).map(({ id, name, lang }) => ({ id, name, lang })),
        signingFingerprint: db.stores.find((s) => s.id === params.storeId)?.signingKeyFingerprint ?? "",
        sha256: fakeHash(`${available.pkgName}-${available.versionCode}`),
        origin: { storeId: String(params.storeId) },
        installedAt: now(),
      };
      db.extensions = [...db.extensions.filter((e) => e.pkgName !== extension.pkgName), extension];
      return ok({ extension });
    }),

    // ----- MihonIntegrationsService -----------------------------------------------
    http.get(`${base}/admin/solvers`, async ({ request }) => {
      await wait();
      return guard(request, true) ?? ok({ solvers: db.solvers });
    }),

    http.put(`${base}/admin/solvers/:id`, async ({ request, params }) => {
      await wait();
      const denied = guard(request, true);
      if (denied) return denied;
      const solver = (await request.json()) as Solver;
      if (!solver.url) return grpcError("INVALID_ARGUMENT", "url is required");
      const saved = { ...solver, id: String(params.id) };
      const isNew = !db.solvers.some((s) => s.id === saved.id);
      db.solvers = [...db.solvers.filter((s) => s.id !== saved.id), saved];
      // Like the service: the first solver, with no profiles at all, becomes the default profile's.
      if (isNew && db.profiles.length === 0 && !db.defaultProfileId) {
        db.profiles = [
          { id: "default", name: "Default", solverId: saved.id, userAgentMode: "USER_AGENT_MODE_FROM_SOLVER" },
        ];
        db.defaultProfileId = "default";
      }
      return ok({ solver: saved });
    }),

    http.delete(`${base}/admin/solvers/:id`, async ({ request, params }) => {
      await wait();
      const denied = guard(request, true);
      if (denied) return denied;
      const user = db.profiles.find((p) => p.solverId === params.id);
      if (user) return grpcError("FAILED_PRECONDITION", `profile ${user.id} still uses solver ${params.id}`);
      db.solvers = db.solvers.filter((s) => s.id !== params.id);
      return ok({});
    }),

    http.get(`${base}/admin/profiles`, async ({ request }) => {
      await wait();
      return (
        guard(request, true) ??
        ok({ profiles: db.profiles.map((p) => publicProfile(db, p)), defaultProfileId: db.defaultProfileId })
      );
    }),

    http.put(`${base}/admin/profiles/:id`, async ({ request, params }) => {
      await wait();
      const denied = guard(request, true);
      if (denied) return denied;
      const profile = (await request.json()) as NetworkProfile;
      const id = String(params.id);
      if (profile.solverId && !db.solvers.some((s) => s.id === profile.solverId)) {
        return grpcError("INVALID_ARGUMENT", `no solver ${profile.solverId}`);
      }
      if (profile.proxy?.password) db.passwords.set(id, profile.proxy.password);
      if (!profile.proxy) db.passwords.delete(id);
      const saved: NetworkProfile = { ...profile, id };
      if (saved.proxy) saved.proxy = { url: saved.proxy.url ?? "", username: saved.proxy.username ?? "" };
      db.profiles = [...db.profiles.filter((p) => p.id !== id), saved];
      return ok({ profile: publicProfile(db, saved) });
    }),

    http.delete(`${base}/admin/profiles/:id`, async ({ request, params }) => {
      await wait();
      const denied = guard(request, true);
      if (denied) return denied;
      const id = String(params.id);
      db.profiles = db.profiles.filter((p) => p.id !== id);
      db.passwords.delete(id);
      for (const [sourceId, profileId] of db.assignments) if (profileId === id) db.assignments.delete(sourceId);
      if (db.defaultProfileId === id) db.defaultProfileId = "";
      return ok({});
    }),

    http.get(`${base}/admin/assignments`, async ({ request }) => {
      await wait();
      return (
        guard(request, true) ??
        ok({
          assignments: mockSources.map((s) => assignmentFor(db, s.id ?? "")),
          defaultProfileId: db.defaultProfileId,
        })
      );
    }),

    http.put(`${base}/admin/assignments/:sourceId`, async ({ request, params }) => {
      await wait();
      const denied = guard(request, true);
      if (denied) return denied;
      const sourceId = String(params.sourceId);
      const body = (await request.json()) as { profileId?: string };
      if (body.profileId && !db.profiles.some((p) => p.id === body.profileId))
        return grpcError("NOT_FOUND", `no profile ${body.profileId}`);
      if (body.profileId) db.assignments.set(sourceId, body.profileId);
      else db.assignments.delete(sourceId);
      return ok({ assignment: assignmentFor(db, sourceId) });
    }),

    http.put(`${base}/admin/default-profile`, async ({ request }) => {
      await wait();
      const denied = guard(request, true);
      if (denied) return denied;
      const body = (await request.json()) as { profileId?: string };
      if (body.profileId && !db.profiles.some((p) => p.id === body.profileId))
        return grpcError("NOT_FOUND", `no profile ${body.profileId}`);
      db.defaultProfileId = body.profileId ?? "";
      return ok({});
    }),
  ];
}

export { MOCK_ADMIN_TOKEN };
