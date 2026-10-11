# API

mihon-source serves two APIs, both defined as protobuf in [`proto/`](../proto):

- **`panelarr.source.v1`** (`SourcePluginService`): the source API. List sources, browse (popular, latest, search with filters), series details, chapters, pages, images and source settings. Any Panelarr source plugin implements the same contract.
- **`panelarr.mihon.v1`**: the admin API. Extensions (`MihonAdminService`), extension stores (`MihonStoresService`), API clients (`MihonClientsService`) and network settings (`MihonIntegrationsService`).

Both are served on one port in three ways:

| Protocol | How to call it |
|---|---|
| gRPC (and gRPC-Web) | Any gRPC client. Server reflection is on, e.g. `grpcurl -H 'authorization: Bearer <admin token>' localhost:8080 list` |
| HTTP/JSON | The REST-style routes from the `google.api.http` annotations, e.g. `GET /v1/sources`. JSON field names are lowerCamelCase, 64-bit integers are strings and enums are their names. |
| OpenAPI | The document is at `/openapi.yaml` and Swagger UI at `/docs/`. It is generated from the protos (`make gen`). |

## Authentication

Every call except `GET /v1/health` (gRPC `Health`) needs a bearer token:

```sh
curl -H "Authorization: Bearer $TOKEN" localhost:8080/v1/sources
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
curl -H "Authorization: Bearer $ADMIN" -X POST localhost:8080/v1/admin/clients \
  -d '{"name": "my-script", "scopes": ["sources:read"]}'
# {"client": {"id": "…", "keyPrefix": "msk_…", …}, "apiKey": "msk_…"}

curl -H "Authorization: Bearer $ADMIN" -X POST localhost:8080/v1/admin/clients/<id>/rotate
curl -H "Authorization: Bearer $ADMIN" -X DELETE localhost:8080/v1/admin/clients/<id>
```

Request bodies are JSON. A body sent without a JSON content type (as `curl -d` does) is read as JSON anyway.

## Errors

Failures use gRPC status codes. Over HTTP they map to the usual HTTP status and a JSON body:

```json
{"code": 7, "grpc-code": "PERMISSION_DENIED", "message": "this call needs the 'admin' scope"}
```

| Situation | gRPC | HTTP |
|---|---|---|
| Missing or invalid token | `UNAUTHENTICATED` | 401 |
| Token lacks the scope | `PERMISSION_DENIED` | 403 |
| Bad input | `INVALID_ARGUMENT` | 400 |
| Unknown id | `NOT_FOUND` | 404 |
| Not implemented yet | `UNIMPLEMENTED` | 501 |
