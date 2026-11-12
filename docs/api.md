# API

mihon-source serves two APIs, both defined as protobuf:

- **`panelarr.source.v1`** (`SourcePluginService`): the source API, from [source-api](https://github.com/panelarr/source-api) (pinned in [`third_party/source-api/`](../third_party/source-api)). List sources, browse (popular, latest, search with filters), series details, chapters, pages, images and source settings. Any Panelarr source plugin implements the same contract. mihon-source has no plugin-level configuration (`GetConfig`/`SetConfig` answer UNIMPLEMENTED): it is configured through its own admin API and environment. Every source carries its Mihon source ID as `compat_ids: ["mihon:<id>"]`, the number Mihon backups use for it, so a host can match imported data to the source; the admin API's extension and store listings (`ExtensionSource`, `AvailableSource`) carry the same value.
- **`panelarr.mihon.v1`** (in [`proto/`](../proto)): the admin API. Extensions (`MihonAdminService`), extension stores (`MihonStoresService`), API clients (`MihonClientsService`) and network settings (`MihonIntegrationsService`).

Both are served on one port in three ways:

| Protocol | How to call it |
|---|---|
| gRPC (and gRPC-Web) | Any gRPC client. This is the primary interface between services. Server reflection is on (admin scope), e.g. `grpcurl -H 'authorization: Bearer <admin token>' -plaintext localhost:8902 list` |
| HTTP/JSON | The REST-style routes from the `google.api.http` annotations, e.g. `GET /v1/sources`. JSON field names **and query parameters** are lowerCamelCase (`?seriesKey=...`), 64-bit integers are strings, enums are their names, durations are strings like `"30s"`. |
| OpenAPI | The document is at `/openapi.yaml` and Swagger UI at `/docs/`. It is generated from the protos (`make gen`). |

Two HTTP routes have no RPC of their own:

| Route | Does |
|---|---|
| `GET /v1/sources/{sourceId}/image?ref=<ref>` | Streams an image through the source's client: a `Page.ref` from ListPages, or an absolute image URL such as a cover. The HTTP form of `FetchImage`. Scope `sources:read`. |
| `GET /v1/sources/{sourceId}/icon` | The icon of a source's extension. `Source.iconUrl` points here when there is one. Scope `sources:read`. |
| `GET /v1/admin/extensions/{pkgName}/icon` | An installed extension's icon. Scope `admin`. |
| `GET /v1/admin/stores/{storeId}/extensions/{pkgName}/icon` | A store's icon for an extension it offers, fetched through the service and cached for a week, so clients never load images from the store. Scope `admin`. |
| `POST /v1/admin/extensions/upload` | Installs an extension from `multipart/form-data`: field `file`, optional `expectedSha256` and `expectedSigningFingerprint`. Answers like `InstallExtension`. Scope `admin`. |

## Health and metrics

| Route | Does |
|---|---|
| `GET /healthz` | Liveness: 200 while the process serves requests. No credentials. |
| `GET /readyz` | Readiness: 200 once installed extensions are loaded and the server is up, 503 before. No credentials. |
| `GET /v1/health` | The source API's Health: `DEGRADED` when some extensions failed to load. No credentials. |
| `GET /metrics` | Prometheus metrics. Needs the `metrics` scope (or `admin`), or use the separate `MIHON_METRICS_PORT`. |

Metrics include the JVM's, `mihon_source_call_seconds` (count, sum and max per `source`, `operation` and `outcome`, where the outcome is `ok`, an error reason such as `SOURCE_DOWN`, or a gRPC code name), `mihon_solver_solves_total` (per `solver` and `outcome`), `mihon_extensions` (per `status`), `mihon_sources`, `mihon_source_health` (per `source`), `mihon_cache_lookups_total` (per `operation` and `result`: `hit`, `miss`, `shared` or `bypass`), and for [jobs](#jobs) `mihon_job_runs_total` (per `job`, `status` and `trigger`), `mihon_job_duration_seconds` (per `job`) and `mihon_job_running` (per `job`, 1 while it runs). Labels never contain URLs, queries or credentials.

A Prometheus scrape config with a key:

```yaml
scrape_configs:
  - job_name: mihon-source
    authorization:
      credentials: msk_...   # a client key with the metrics scope
    static_configs:
      - targets: ["mihon-source:8902"]
```

## Authentication

Every call except `GET /v1/health` (gRPC `Health`), `/healthz` and `/readyz` needs a bearer token:

```sh
curl -H "Authorization: Bearer $TOKEN" localhost:8902/v1/sources
```

Over gRPC, send the same value in the `authorization` metadata.

There are two kinds of token:

- **The admin token** can do everything. The admin UI logs in with it. See [configuration](configuration.md).
- **Client API keys** belong to clients (a Panelarr server, a script) and carry scopes:

| Scope | Allows |
|---|---|
| `sources:read` | Every read of the source API |
| `sources:write` | `sources:read`, plus changing source settings and pushing network profiles |
| `metrics` | Reading `/metrics`, nothing else |
| `admin` | Everything, including the admin API |

Any valid token may call `GET /v1/whoami` to see who it is and which scopes it holds.

Create a client with the admin token. The key is in the response and is **never shown again**: only a hash is stored. Rotate it to get a new one.

```sh
curl -H "Authorization: Bearer $ADMIN" -X POST localhost:8902/v1/admin/clients \
  -d '{"name": "my-script", "scopes": ["sources:read"]}'
# {"client": {"id": "…", "keyPrefix": "msk_…", …}, "apiKey": "msk_…"}

curl -H "Authorization: Bearer $ADMIN" -X POST localhost:8902/v1/admin/clients/<id>/rotate
curl -H "Authorization: Bearer $ADMIN" -X DELETE localhost:8902/v1/admin/clients/<id>
```

Request bodies are JSON. A body sent without a JSON content type (as `curl -d` does) is read as JSON anyway.

## Browsing a source

```sh
curl -H "Authorization: Bearer $KEY" localhost:8902/v1/sources
curl -H "Authorization: Bearer $KEY" "localhost:8902/v1/sources/$SOURCE/popular?page=1"
curl -H "Authorization: Bearer $KEY" "localhost:8902/v1/sources/$SOURCE/filters"
curl -H "Authorization: Bearer $KEY" -X POST "localhost:8902/v1/sources/$SOURCE/search" \
  -d '{"query": "abc", "page": 1, "filters": [{"path": [4, 0], "triState": "TRI_STATE_INCLUDE"}]}'
curl -H "Authorization: Bearer $KEY" "localhost:8902/v1/sources/$SOURCE/series?key=$SERIES"
curl -H "Authorization: Bearer $KEY" "localhost:8902/v1/sources/$SOURCE/chapters?seriesKey=$SERIES"
curl -H "Authorization: Bearer $KEY" "localhost:8902/v1/sources/$SOURCE/pages?chapterKey=$CHAPTER"
```

- **Languages:** an extension's enabled languages (`SetExtensionLanguages`) decide which of its sources are listed; the others answer NOT_FOUND. `Extension.enabled_langs` and `ExtensionSource.available` show the setting. See [extensions](extensions.md#languages).
- **Pasted URLs:** `ResolveUrl` (`POST /v1/resolve-url`) turns a web URL into a series. mihon-source tries each candidate source (the one asked for, or every source with its own resolver or on the URL's site) in this order:
  1. the source's own resolver, if it has one; a resolver that fails doesn't stop the others;
  2. the URL's path (and query) relative to the source's base URL as the series key, as most Mihon sources key series by path: it counts when the source turns that key back into the same web URL and the series' details load with a title;
  3. a search for the URL (many sources accept that), taking the result whose web URL matches.

  These answers are verified. Only when no source gives one does mihon-source fall back to a search that returned a single result whose web URL is different: that answer may be an unrelated series, so it sets `bestEffort`.
- **Keys** (series keys, chapter keys, page refs) are opaque: store them and pass them back unchanged.
- **Filters** have no stable names, so a `FilterValue` addresses a filter by its position in `GetFilters`: `[index]`, or `[group index, child index]` inside a group. Filters you don't send keep their defaults.
- **Network profile in use:** `GetNetworkProfile` (`GET /v1/sources/{sourceId}/network-profile`) says which profile a source uses: one a host pushed (`PUSHED`), one assigned in this service or its default (`PLUGIN`), or none (`NONE`). Proxy passwords never come back. A pushed profile wins and is kept across restarts; pushing an empty profile removes it.
- **Restarts:** `Health` carries `instance_id`, new on every start, so a host knows to push its profiles and settings again.
- **Settings** come as a JSON Schema (`GET .../settings/schema`). The display order is in `x-order`. Choices use `enum` with labels in `x-enumNames`. Change values with `PATCH .../settings`; only the keys you send change, and a source may refuse a value. Text settings the extension shows as a password (tokens, passwords) are marked `writeOnly`: they come back empty, and sending them back empty keeps the stored value.
- **Caching:** results are kept for a short time, so repeating a call doesn't hit the site again: lists (popular, latest, search) for 5 minutes, series and chapters for 10, pages for 30, filters for an hour. Identical calls made at the same time share one site request. Errors and images are never cached. Send `Cache-Control: no-cache` (an HTTP header or gRPC metadata) to fetch fresh, or set `refresh` on `GetSeries` and `ListChapters` (`?refresh=true` over HTTP), e.g. to check for new chapters now; the fresh result replaces the cached one. Changing a source's settings or network profile, or reinstalling, updating or removing its extension, drops its cached results. Lifetimes and the size limit are in [configuration](configuration.md).

## Checking sources

`CheckSources` (`POST /v1/admin/checks`, admin scope) runs checks now, on chosen sources or all of them. Steps: `POPULAR` (the default), `LATEST`, `SEARCH` (with `searchQuery`, or the first popular title), `DETAILS`, `CHAPTERS`, `PAGES`, `IMAGE`. Each step feeds the next (a series from the list, its last chapter, that chapter's first page), and steps a requested one needs are added. Each step reports `ok`, `skipped`, the error `reason`, a `detail` such as "20 series", and its duration.

```sh
curl -H "Authorization: Bearer $ADMIN" -X POST localhost:8902/v1/admin/checks \
  -d '{"sourceIds": ["123"], "steps": ["CHECK_STEP_IMAGE"]}'
```

The result becomes the source's health: `Source.health` in `ListSources`/`GetSource` has the status (`OK` or `FAILING` with a reason such as `SOURCE_DOWN`, `CLOUDFLARE_CHALLENGE` or `NEEDS_WEBVIEW`), the latency, when it was checked, and whether scheduled checks are off for it. The `source-health` [job](#jobs) records the same, on its schedule (see `MIHON_HEALTH_CHECK_INTERVAL` in [configuration](configuration.md)) or when run by hand. `SetHealthCheck` (`PUT /v1/admin/sources/{sourceId}/health-check`) turns them off or on per source.

The schedule (interval, languages, sample) comes from the `MIHON_HEALTH_CHECK_*` environment variables until it's set over the API: `GetHealthCheckSchedule` and `SetHealthCheckSchedule` (`GET`/`PUT /v1/admin/health-checks/schedule`, body `{"schedule": {"interval": "21600s", "langs": ["en"], "sample": 10}}`). No interval turns scheduled checks off; at least a minute otherwise. The job's next run follows the new interval at once, the schedule's languages and sample become its params' defaults, and the setting survives restarts. Sending no `schedule` goes back to the environment's. The `source` field says where the schedule in effect comes from.

A `source-health` run for given `source_ids` notes every step's result (what it found, or why it failed or was skipped), so asking for a check of one source shows the same detail as `CheckSources`. The metric `mihon_source_health{source}` is 1 for OK and 0 for failing.

## Jobs

Background work runs as jobs. `MihonJobsService` (admin scope) lists them, keeps their run history and runs them on request:

| Job | Does | Params | Schedule |
|---|---|---|---|
| `store-refresh` | Refreshes the stores' indexes, so updates show up. Never installs anything. Fails when a store fails to refresh (the store keeps its last good index). | `store_id`: one store only | `MIHON_STORE_REFRESH_INTERVAL` |
| `source-health` | Checks sources and records their [health](#checking-sources). Without `source_ids`: the sources whose scheduled checks are on, in `langs`, at most `sample` of them, least recently checked first. | `source_ids` (list; overrides `langs` and `sample`), `langs` (list of installed languages or `all`; default `MIHON_HEALTH_CHECK_LANGS`), `sample` (int, `0` for all; default `MIHON_HEALTH_CHECK_SAMPLE`), `steps` (list of `popular`, `latest`, `search`, `details`, `chapters`, `pages`, `image`; default `popular`) | `MIHON_HEALTH_CHECK_INTERVAL`, or `SetHealthCheckSchedule` |

```sh
curl -H "Authorization: Bearer $ADMIN" localhost:8902/v1/admin/jobs
curl -H "Authorization: Bearer $ADMIN" -X POST localhost:8902/v1/admin/jobs/source-health:run \
  -d '{"params": {"steps": "popular,latest"}}'
curl -H "Authorization: Bearer $ADMIN" localhost:8902/v1/admin/job-runs/$RUN
curl -H "Authorization: Bearer $ADMIN" "localhost:8902/v1/admin/jobs/source-health/runs?pageSize=20&status=JOB_RUN_STATUS_FAILED"
curl -H "Authorization: Bearer $ADMIN" -X POST localhost:8902/v1/admin/job-runs/$RUN:cancel
```

- **Jobs** have a stable ID, a schedule (an interval, read-only: it comes from the configuration; without one the job only runs on request), the next scheduled run, the last finished run and the params `RunJob` accepts. List params are comma-separated. A param left out gets its `default`; scheduled runs use the defaults, so they show what the schedule runs with.
- **Runs** have a status (`RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `SKIPPED`), a trigger (`SCHEDULE` or `MANUAL`, with who started it), start and finish times, a one-line result, counts such as `{"ok": 18, "failing": 2}`, and notes: the warnings and errors of the run (at most 200). A running run also reports its progress.
- **One run at a time:** `RunJob` returns the new run at once. While the job runs, another `RunJob` fails with `ALREADY_EXISTS` (HTTP 409, reason `ALREADY_RUNNING`) and the running run's ID (the `JobAlreadyRunning` detail; `currentRunId` over HTTP). A scheduled run that comes due meanwhile is recorded as `SKIPPED`.
- **Cancelling** stops a run between steps. Cancelling a run that isn't running fails with `FAILED_PRECONDITION`.
- **History** keeps the last `MIHON_JOB_HISTORY` runs per job (100). A run that was going on when the service stopped is marked `FAILED` with the message `interrupted`.

## Errors

Failures use gRPC status codes. When the source (not the request) is the problem, the status carries a `google.rpc.ErrorInfo` with domain `source.panelarr` and one of these reasons:

| Reason | Meaning | gRPC | HTTP |
|---|---|---|---|
| `CLOUDFLARE_CHALLENGE` | An anti-bot challenge blocked the request and no solver passed it | `UNAVAILABLE` | 503 |
| `RATE_LIMITED` | The site rate-limited the request (with `RetryInfo`) | `RESOURCE_EXHAUSTED` | 429 |
| `SOURCE_DOWN` | The site is unreachable, timed out or failed | `UNAVAILABLE` | 503 |
| `NOT_FOUND` | The source, series, chapter or page doesn't exist | `NOT_FOUND` | 404 |
| `LOGIN_REQUIRED` | The site wants a login | `UNAUTHENTICATED` / `PERMISSION_DENIED` | 401 / 403 |
| `UNSUPPORTED` | The source doesn't support this call | `UNIMPLEMENTED` | 501 |

Store calls (`AddStore`, `RefreshStore`, `InstallFromStore`) say what failed when a store or a file it lists can't be used, with an `ErrorInfo` in domain `mihon.panelarr`: `STORE_FETCH_FAILED` (UNAVAILABLE, 503) when a download failed, `STORE_INVALID` (INVALID_ARGUMENT, 400) when the store's content can't be used or its signing key doesn't match. So an UNAVAILABLE with `STORE_FETCH_FAILED` is the store's problem, and one without a reason is this service's.

Over HTTP, errors are [problem details](https://www.rfc-editor.org/rfc/rfc9457) (`application/problem+json`):

```json
{"type": "about:blank", "title": "Service Unavailable", "status": 503, "detail": "the source answered HTTP 503",
 "code": "UNAVAILABLE", "message": "the source answered HTTP 503", "reason": "SOURCE_DOWN"}
```

`code` is the gRPC code name, `reason` the ErrorInfo reason (when there is one) and `retryAfter` the RetryInfo delay in seconds. `message` repeats `detail`. Request errors work the same way: 400 for bad input, 401 for a missing or invalid token, 403 for a missing scope, 404 for unknown ids and routes.
