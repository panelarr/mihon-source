# mihon-source

> **Status: early development.** Nothing here works yet. See [PROGRESS.md](PROGRESS.md).

mihon-source is a small, self-hosted service that runs [Mihon](https://mihon.app) extensions on a plain JVM and serves their sources over a typed API. It is part of the Panelarr project, but it runs fine on its own.

- **Extensions:** install them by URL, by upload or from extension stores you add yourself. Jar and APK are both supported (APKs are converted with dex2jar). Each extension gets its own classloader, and a broken extension can't take the service down.
- **API:** the Panelarr source API (`panelarr.source.v1`) and a small admin API (`panelarr.mihon.v1`), both over gRPC and HTTP/JSON, with OpenAPI docs.
- **Per-source networking:** every source gets its own HTTP client with its own proxy, user agent, rate limit, cookie jar and optional FlareSolverr/Byparr solver.
- **Minimal admin:** a small web UI to browse and test sources, manage extensions, stores, API clients and network settings.
- **Small:** no database and no users. All state is plain files under `/data`.

mihon-source ships with **no extensions and no extension stores**. You choose and add them yourself.

## Quick start

The Docker image doesn't exist yet. Until then, run from source:

```sh
make setup   # downloads Gradle, a JDK 21 toolchain, buf and dependencies
make run     # listens on :8902, keeps its state in ./data
```

On first start an admin token is printed once. Use it to create an API key and call the API:

```sh
curl localhost:8902/v1/health
curl -H "Authorization: Bearer $ADMIN" -X POST localhost:8902/v1/admin/clients \
  -d '{"name": "me", "scopes": ["sources:read"]}'
curl -H "Authorization: Bearer $KEY" localhost:8902/v1/sources
```

API docs are at `http://localhost:8902/docs/` (OpenAPI document at `/openapi.yaml`). See [docs/api.md](docs/api.md) and [docs/configuration.md](docs/configuration.md).

## Development

Everything runs through `make`, which wraps Gradle (`make help` lists the targets). You need a JDK 17+ to start Gradle. Gradle downloads JDK 21 for the build if you don't have it.

```sh
make check   # lint + tests, run before every commit
```

See [CONTRIBUTING.md](CONTRIBUTING.md) and [docs/](docs/README.md).

## Licence

mihon-source is licensed under the [GNU General Public License v3.0 or later](LICENSE).

The protobuf definitions in [`proto/`](proto) are licensed under the [Apache License 2.0](proto/LICENSE), so that other implementations of the API can use them freely.
