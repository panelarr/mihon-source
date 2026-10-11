# API

mihon-source serves two APIs, both defined as protobuf in [`proto/`](../proto):

- **`panelarr.source.v1`** (`SourcePluginService`): the source API. List sources, browse (popular, latest, search with filters), series details, chapters, pages, images and source settings. Any Panelarr source plugin implements the same contract.
- **`panelarr.mihon.v1`**: the admin API. Extensions (`MihonAdminService`), extension stores (`MihonStoresService`), API clients (`MihonClientsService`) and network settings (`MihonIntegrationsService`).

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
| `POST /v1/admin/extensions/upload` | Installs an extension from `multipart/form-data`: field `file`, optional `expectedSha256` and `expectedSigningFingerprint`. Answers like `InstallExtension`. Scope `admin`. |

## Authentication

Every call except `GET /v1/health` (gRPC `Health`) needs a bearer token:

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

- **Keys** (series keys, chapter keys, page refs) are opaque: store them and pass them back unchanged.
- **Filters** have no stable names, so a `FilterValue` addresses a filter by its position in `GetFilters`: `[index]`, or `[group index, child index]` inside a group. Filters you don't send keep their defaults.
- **Settings** come as a JSON Schema (`GET .../settings/schema`). The display order is in `x-order`. Choices use `enum` with labels in `x-enumNames`. Change values with `PATCH .../settings`; only the keys you send change, and a source may refuse a value.

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

Over HTTP, errors are [problem details](https://www.rfc-editor.org/rfc/rfc9457) (`application/problem+json`):

```json
{"type": "about:blank", "title": "Service Unavailable", "status": 503, "detail": "the source answered HTTP 503",
 "code": "UNAVAILABLE", "message": "the source answered HTTP 503", "reason": "SOURCE_DOWN"}
```

`code` is the gRPC code name, `reason` the ErrorInfo reason (when there is one) and `retryAfter` the RetryInfo delay in seconds. `message` repeats `detail`. Request errors work the same way: 400 for bad input, 401 for a missing or invalid token, 403 for a missing scope, 404 for unknown ids and routes.
