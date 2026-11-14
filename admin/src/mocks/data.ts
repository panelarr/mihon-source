// SPDX-License-Identifier: GPL-3.0-or-later

// Fake data for mock mode and tests. Everything here is made up: names, URLs (only
// example.com / *.example) and hashes.
import type {
  AvailableExtension,
  Browser,
  Chapter,
  Client,
  Extension,
  Filter,
  Job,
  JobRun,
  JsonObject,
  NetworkProfile,
  Page,
  Series,
  Solver,
  Source,
  Store,
} from "../api/types";

export const MOCK_ADMIN_TOKEN = "mock-admin";

const fakeHash = (seed: string) => {
  let h = 2166136261;
  let out = "";
  for (let i = 0; out.length < 64; i++) {
    h ^= seed.charCodeAt(i % seed.length) + i;
    h = Math.imul(h, 16777619) >>> 0;
    out += h.toString(16).padStart(8, "0");
  }
  return out.slice(0, 64);
};

const daysAgo = (days: number) => new Date(Date.UTC(2026, 0, 31) - days * 86_400_000).toISOString();

export const mockSources: Source[] = [
  {
    id: "1001",
    name: "Example Source",
    lang: "en",
    contentRating: "CONTENT_RATING_SAFE",
    iconUrl: "https://example.com/icons/example-source.png",
    homeUrl: "https://example.com",
    capabilities: [
      "SOURCE_CAPABILITY_LATEST",
      "SOURCE_CAPABILITY_SEARCH",
      "SOURCE_CAPABILITY_FILTERS",
      "SOURCE_CAPABILITY_SETTINGS",
    ],
    health: { status: "SOURCE_HEALTH_STATUS_OK", latency: "0.420s", checkedAt: "2026-01-30T10:00:00Z" },
  },
  {
    id: "1002",
    name: "Example Source",
    lang: "fr",
    contentRating: "CONTENT_RATING_SAFE",
    homeUrl: "https://fr.example.com",
    capabilities: ["SOURCE_CAPABILITY_LATEST", "SOURCE_CAPABILITY_SEARCH", "SOURCE_CAPABILITY_FILTERS"],
  },
  {
    id: "2001",
    name: "Sample Comics",
    lang: "en",
    contentRating: "CONTENT_RATING_MIXED",
    homeUrl: "https://comics.example.org",
    capabilities: ["SOURCE_CAPABILITY_SEARCH", "SOURCE_CAPABILITY_FILTERS"],
    health: { checksDisabled: true },
  },
  {
    id: "3001",
    name: "Fake Webview Source",
    lang: "all",
    contentRating: "CONTENT_RATING_NSFW",
    homeUrl: "https://webview.example.net",
    capabilities: ["SOURCE_CAPABILITY_SEARCH", "SOURCE_CAPABILITY_NEEDS_WEBVIEW", "SOURCE_CAPABILITY_NSFW"],
    health: {
      status: "SOURCE_HEALTH_STATUS_FAILING",
      reason: "NEEDS_WEBVIEW",
      message: "the source needs a browser engine",
      checkedAt: "2026-01-30T10:05:00Z",
    },
  },
];

/** Sources whose browse calls fail, to show error handling. */
export const failingSourceIds = new Set(["3001"]);

export const mockFilters: Filter[] = [
  { name: "Matching", header: {} },
  { name: "Author", text: {} },
  { name: "Completed only", checkbox: {} },
  { name: "", separator: {} },
  { name: "Status", select: { options: ["Any", "Ongoing", "Completed", "On hiatus"] } },
  { name: "Order by", sort: { options: ["Popularity", "Latest update", "Title"], selection: { index: 0 } } },
  {
    name: "Genres",
    group: {
      filters: [
        { name: "Action", triState: {} },
        { name: "Comedy", triState: {} },
        { name: "Drama", triState: { value: "TRI_STATE_INCLUDE" } },
        { name: "Fantasy", triState: {} },
      ],
    },
  },
  {
    name: "Formats",
    group: {
      filters: [
        { name: "Long strip", checkbox: {} },
        { name: "Full colour", checkbox: { value: true } },
      ],
    },
  },
];

export const mockSettingsSchema: JsonObject = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  properties: {
    preferredQuality: {
      type: "string",
      title: "Image quality",
      description: "Quality of the page images.",
      enum: ["original", "high", "low"],
      "x-enumNames": ["Original", "High", "Data saver"],
    },
    showAdult: { type: "boolean", title: "Show adult titles" },
    mirror: { type: "string", title: "Mirror host", description: "Leave empty for the main host." },
    languages: {
      type: "array",
      title: "Chapter languages",
      items: {
        type: "string",
        oneOf: [
          { const: "en", title: "English" },
          { const: "fr", title: "French" },
          { const: "es", title: "Spanish" },
        ],
      },
      uniqueItems: true,
    },
    advanced: { type: "object", title: "Advanced", description: "Anything else, as JSON." },
  },
};

export const mockSettingsValues: JsonObject = {
  preferredQuality: "high",
  showAdult: false,
  mirror: "",
  languages: ["en"],
  advanced: { retries: 2 },
};

const words = ["Silent", "Crimson", "Paper", "Last", "Hidden", "Iron", "Little", "Endless", "Northern", "Glass"];
const nouns = ["Garden", "Voyage", "Archive", "Signal", "Harbor", "Lantern", "Kingdom", "Notebook", "Orbit", "Bakery"];

export const PAGE_SIZE = 20;
export const PAGE_COUNT = 3;

export function seriesTitle(n: number): string {
  return `${words[n % words.length]} ${nouns[Math.floor(n / words.length) % nouns.length]} (Example ${n})`;
}

export function mockSeries(sourceId: string, n: number, details = false): Series {
  return {
    key: `/series/${sourceId}-${n}`,
    title: seriesTitle(n),
    coverUrl: `https://example.com/covers/${sourceId}-${n}.png`,
    detailsLoaded: details,
    ...(details
      ? {
          altTitles: [`Example Alt Title ${n}`],
          authors: ["A. Example"],
          artists: ["B. Sample"],
          description: `Fake description of example series ${n}. Mock data for the admin UI.\n\nSecond paragraph.`,
          genres: ["Action", "Drama", "Fantasy"].slice(0, (n % 3) + 1),
          status: n % 2 === 0 ? "SERIES_STATUS_ONGOING" : "SERIES_STATUS_COMPLETED",
          url: `https://example.com/series/${n}`,
        }
      : {}),
  };
}

export function mockChapters(seriesKey: string): Chapter[] {
  return Array.from({ length: 12 }, (_, i) => {
    const number = 12 - i;
    return {
      key: `${seriesKey}/chapter-${number}`,
      name: `Chapter ${number}`,
      number,
      volume: String(Math.ceil(number / 4)),
      scanlator: i % 3 === 0 ? "Example Group" : "",
      uploadedAt: daysAgo(i * 7),
      url: `https://example.com${seriesKey}/chapter-${number}`,
    };
  });
}

export function mockPages(chapterKey: string): Page[] {
  return Array.from({ length: 6 }, (_, index) => ({ index, ref: `${chapterKey}#page-${index + 1}` }));
}

export function mockExtensions(): Extension[] {
  return [
    {
      pkgName: "eu.example.extension.examplesource",
      name: "Example Source",
      versionName: "1.4.12",
      versionCode: "12",
      libVersion: "1.4",
      format: "EXTENSION_FORMAT_JAR",
      contentWarning: "CONTENT_WARNING_SAFE",
      status: "EXTENSION_STATUS_LOADED",
      sources: [
        { id: "1001", name: "Example Source", lang: "en" },
        { id: "1002", name: "Example Source", lang: "fr" },
      ],
      signingFingerprint: fakeHash("example-signer"),
      sha256: fakeHash("examplesource-12"),
      origin: { storeId: "example-store" },
      installedAt: daysAgo(10),
    },
    {
      pkgName: "eu.example.extension.samplecomics",
      name: "Sample Comics",
      versionName: "1.5.3",
      versionCode: "3",
      libVersion: "1.5",
      format: "EXTENSION_FORMAT_APK",
      contentWarning: "CONTENT_WARNING_MIXED",
      status: "EXTENSION_STATUS_LOADED",
      sources: [{ id: "2001", name: "Sample Comics", lang: "en" }],
      signingFingerprint: fakeHash("another-signer"),
      sha256: fakeHash("samplecomics-3"),
      origin: { url: "https://example.com/downloads/sample-comics.apk" },
      installedAt: daysAgo(3),
    },
    {
      pkgName: "eu.example.extension.webview",
      name: "Fake Webview Source",
      versionName: "1.4.1",
      versionCode: "1",
      libVersion: "1.4",
      format: "EXTENSION_FORMAT_APK",
      contentWarning: "CONTENT_WARNING_NSFW",
      status: "EXTENSION_STATUS_LOADED",
      sources: [{ id: "3001", name: "Fake Webview Source", lang: "all" }],
      signingFingerprint: "",
      sha256: fakeHash("webview-1"),
      origin: { upload: "fake-webview.apk" },
      installedAt: daysAgo(1),
    },
    {
      pkgName: "eu.example.extension.broken",
      name: "Broken Example",
      versionName: "1.2.0",
      versionCode: "1",
      libVersion: "1.2",
      format: "EXTENSION_FORMAT_APK",
      contentWarning: "CONTENT_WARNING_SAFE",
      status: "EXTENSION_STATUS_ERROR",
      loadError: "Unsupported extensions-lib version 1.2: this service loads 1.4 to 1.6.",
      sources: [],
      signingFingerprint: fakeHash("example-signer"),
      sha256: fakeHash("broken-1"),
      origin: { url: "https://example.com/downloads/broken.apk" },
      installedAt: daysAgo(20),
    },
  ];
}

export function mockStores(): Store[] {
  return [
    {
      id: "example-store",
      url: "https://example.com/store/repo.json",
      name: "Example Store",
      badgeLabel: "EX",
      contact: "https://example.com/contact",
      signingKeyFingerprint: fakeHash("example-signer"),
      format: "STORE_FORMAT_EXTENSION_STORE",
      addedAt: daysAgo(30),
      refreshedAt: daysAgo(0.1),
      lastError: "",
      extensionCount: 5,
    },
    {
      id: "legacy-example",
      url: "https://legacy.example.org/index.min.json",
      name: "Legacy Example Index",
      format: "STORE_FORMAT_LEGACY_INDEX",
      addedAt: daysAgo(12),
      refreshedAt: daysAgo(2),
      lastError: "Fetching the index failed: HTTP 503 from legacy.example.org",
      extensionCount: 1,
    },
  ];
}

export function mockAvailable(): AvailableExtension[] {
  const base = (pkg: string, name: string, lang: string[], extra: Partial<AvailableExtension>): AvailableExtension => ({
    storeId: "example-store",
    pkgName: `eu.example.extension.${pkg}`,
    name,
    versionName: "1.4.1",
    versionCode: "1",
    libVersion: "1.4",
    contentWarning: "CONTENT_WARNING_SAFE",
    sources: lang.map((l, i) => ({ id: `${pkg}-${i}`, name, lang: l, homeUrl: "https://example.com" })),
    supported: true,
    hasJar: true,
    installed: false,
    ...extra,
  });
  return [
    base("examplesource", "Example Source", ["en", "fr"], {
      versionName: "1.4.13",
      versionCode: "13",
      installed: true,
      installedVersionName: "1.4.12",
      installedVersionCode: "12",
      updateAvailable: true,
    }),
    base("demoreader", "Demo Reader", ["en"], {}),
    base("placeholder", "Placeholder Scans", ["es", "pt-BR"], { hasJar: false }),
    base("adultexample", "Adult Example", ["en"], { contentWarning: "CONTENT_WARNING_NSFW" }),
    base("oldexample", "Old Example", ["ja"], { libVersion: "1.2", supported: false }),
    {
      ...base("legacyonly", "Legacy Only Example", ["de"], { hasJar: false }),
      storeId: "legacy-example",
    },
  ];
}

export function mockClients(): Client[] {
  return [
    {
      id: "c1a2b3c4",
      name: "panelarr-server",
      scopes: ["sources:write"],
      keyPrefix: "msk_Ab3dE",
      createdAt: daysAgo(40),
      lastUsedAt: daysAgo(0.02),
    },
    {
      id: "d5e6f7a8",
      name: "backup-script",
      scopes: ["sources:read"],
      keyPrefix: "msk_Zx9Qw",
      createdAt: daysAgo(15),
      rotatedAt: daysAgo(5),
    },
  ];
}

export function mockSolvers(): Solver[] {
  return [
    {
      id: "solver-main",
      name: "Main solver",
      type: "SOLVER_TYPE_FLARESOLVERR",
      url: "http://solver.example:8191",
      sessionTtl: "1800s",
      maxTimeout: "60s",
      asResponseFallback: false,
    },
  ];
}

/** A configured browser service that answers, with one page open. */
export function mockBrowser(): Browser {
  return {
    configured: true,
    address: "webview.example:9222",
    up: true,
    product: "HeadlessChrome/140.0.0.0",
    checkedAt: "2026-01-01T00:00:00Z",
    pagesOpen: 1,
    maxPages: 2,
    contextsOpen: 1,
    loadTimeout: "60s",
    idleTimeout: "300s",
  };
}

export function mockProfiles(): NetworkProfile[] {
  return [
    {
      id: "polite",
      name: "Polite direct",
      rateLimit: { requests: 2, period: "1s", perHost: false },
      concurrency: 2,
      userAgentMode: "USER_AGENT_MODE_RANDOM_DESKTOP",
      connectTimeout: "10s",
      readTimeout: "30s",
    },
    {
      id: "via-proxy",
      name: "Through the example proxy",
      proxy: { url: "socks5://proxy.example:1080", username: "example-user", hasPassword: true },
      solverId: "solver-main",
      userAgentMode: "USER_AGENT_MODE_FROM_SOLVER",
      headers: { "Accept-Language": "en-US" },
      sharedCookieJar: "shared-example",
      callTimeout: "90s",
    },
  ];
}

export function mockJobs(): Job[] {
  return [
    {
      id: "store-refresh",
      name: "Refresh stores",
      description:
        "Downloads every store's index again, so new extensions and updates show up. Never installs anything.",
      group: "Stores",
      schedule: { interval: "43200s", enabled: true, source: "MIHON_STORE_REFRESH_INTERVAL" },
      cancellable: true,
      params: [
        {
          name: "store_id",
          type: "string",
          description: "Refresh only this store (its ID). All stores when empty.",
          required: false,
          default: "",
          choices: [],
        },
      ],
    },
    {
      id: "source-health",
      name: "Check sources",
      description: "Fetches from each source to see whether it works, and records the result as the source's health.",
      group: "Sources",
      schedule: { enabled: false, source: "MIHON_HEALTH_CHECK_INTERVAL" },
      cancellable: true,
      params: [
        {
          name: "source_ids",
          type: "string_list",
          description: "Check only these sources (IDs). Overrides langs and sample.",
          required: false,
          default: "",
          choices: [],
        },
        {
          name: "langs",
          type: "string_list",
          description:
            'Only sources in these languages; "all" for every language. Defaults to MIHON_HEALTH_CHECK_LANGS.',
          required: false,
          default: "en,fr",
          choices: ["all", "en", "fr"],
        },
        {
          name: "sample",
          type: "int",
          description:
            "How many sources to check, least recently checked first; 0 for all. Defaults to MIHON_HEALTH_CHECK_SAMPLE.",
          required: false,
          default: "10",
          choices: [],
        },
        {
          name: "steps",
          type: "string_list",
          description: "What to check.",
          required: false,
          default: "popular",
          choices: ["popular", "latest", "search", "details", "chapters", "pages", "image"],
        },
      ],
    },
  ];
}

/** Finished runs, newest first. */
export function mockJobRuns(): JobRun[] {
  const hoursAgo = (hours: number, seconds = 0) =>
    new Date(Date.now() - hours * 3_600_000 + seconds * 1000).toISOString();
  return [
    {
      summary: {
        id: "0190a1b2c3d4e5",
        jobId: "store-refresh",
        status: "JOB_RUN_STATUS_FAILED",
        trigger: "JOB_TRIGGER_SCHEDULE",
        startedAt: hoursAgo(2),
        finishedAt: hoursAgo(2, 3),
        message: "1 of 2 stores failed to refresh; 1 update available",
        counts: { stores: "1", failed: "1", updates: "1" },
      },
      notes: [
        {
          at: hoursAgo(2, 2),
          level: "warn",
          message: "Legacy Example Store: Fetching the index failed: HTTP 503",
          ref: "legacy-example",
        },
      ],
    },
    {
      summary: {
        id: "0190a0a1b2c3d4",
        jobId: "store-refresh",
        status: "JOB_RUN_STATUS_SUCCEEDED",
        trigger: "JOB_TRIGGER_MANUAL",
        triggeredBy: "admin",
        startedAt: hoursAgo(14),
        finishedAt: hoursAgo(14, 2),
        message: "Refreshed 2 stores; 1 update available",
        counts: { stores: "2", updates: "1" },
      },
    },
  ];
}

export { fakeHash };
