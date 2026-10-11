# Architecture (for contributors)

mihon-source is one JVM process with no database. Its state is plain files under the data directory.

## Modules

| Gradle module | Contents |
|---|---|
| `api` | Code generated from `proto/`: messages, gRPC stubs, Kotlin coroutine stubs. Nothing hand-written |
| `service` | The service itself |
| `build-logic` | Convention plugins: Kotlin/JVM setup with detekt and ktlint (`mihon.kotlin-jvm`), and buf tasks (`mihon.buf`) |

## The service

| Package | Role |
|---|---|
| `http` | The network edge (Armeria): gRPC, HTTP/JSON transcoding, OpenAPI document, Swagger UI, security headers |
| `api` | Implementations of the gRPC services. Plain grpc-kotlin services that don't know about Armeria |
| `auth` | Admin token, client API keys, scopes, and the interceptor that checks them on every call |
| `config` | Settings from environment variables |
| `storage` | Small helpers for JSON files written atomically |

Planned parts, added slice by slice (see [PROGRESS.md](../PROGRESS.md)): the extension loader (child-first classloader per extension), the host classpath and Android shims, per-source networking, extension stores and the admin UI.

## Why these choices

- **Protobuf first.** The protos are the contract. Kotlin code, HTTP routes and the OpenAPI document all come from them, so the three can't drift apart.
- **Armeria** serves gRPC, gRPC-Web and HTTP/JSON transcoding from one service, with no proxy or second server. The services themselves are standard grpc-kotlin services and auth is a standard gRPC interceptor, so only the `http` package would change if the server library ever did.
- **Auth checks every RPC by name.** `MethodScopes` maps each RPC to the scope it needs. An RPC that isn't listed needs `admin`, so new endpoints are closed until someone opens them on purpose.
- **Tokens are hashed with SHA-256.** They are 256 random bits, so they can't be guessed. A slow password hash would add nothing but latency on every request.
- **Files, not a database.** The state is small, and each kind of state lives in its own JSON file, written to a temporary file and moved into place.
