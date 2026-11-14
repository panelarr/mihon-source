# mihon-source

> **Status: early development.** The service works end to end; the Docker image is next. See [PROGRESS.md](PROGRESS.md).

mihon-source is a small, self-hosted service that runs [Mihon](https://mihon.app) extensions on a plain JVM and serves their sources over a typed API. It is part of the Panelarr project, but it runs fine on its own.

- **Extensions:** install them by URL, by upload or from extension stores you add yourself. Jar and APK are both supported (APKs are converted with dex2jar). Each extension gets its own classloader, and a broken extension can't take the service down.
- **API:** the Panelarr source API (`panelarr.source.v1`) and a small admin API (`panelarr.mihon.v1`), both over gRPC and HTTP/JSON, with OpenAPI docs.
- **Per-source networking:** every source gets its own HTTP client with its own proxy, user agent, rate limit, cookie jar and optional FlareSolverr/Byparr solver.
- **Minimal admin:** a small web UI to browse and test sources, manage extensions, stores, API clients and network settings.
- **Small:** no database and no users. All state is plain files under `/data`.

mihon-source ships with **no extensions and no extension stores**. You choose and add them yourself.

## Quick start

```sh
docker run -d --name mihon-source -p 8902:8902 -v mihon-source-data:/data panelarr/mihon-source
docker logs mihon-source   # shows the generated admin token, once
```

Open `http://localhost:8902/` and sign in with the admin token. From there you can add an extension store or install an extension by URL or upload, then browse its sources. Or use the API:

```sh
ADMIN=msa_...   # the token from the logs
curl localhost:8902/v1/health
curl -H "Authorization: Bearer $ADMIN" -X POST localhost:8902/v1/admin/extensions \
  -d '{"url": "https://example.com/some-extension.jar"}'
curl -H "Authorization: Bearer $ADMIN" -X POST localhost:8902/v1/admin/clients \
  -d '{"name": "me", "scopes": ["sources:read"]}'      # prints an API key, once
curl -H "Authorization: Bearer $KEY" localhost:8902/v1/sources
curl -H "Authorization: Bearer $KEY" "localhost:8902/v1/sources/$SOURCE_ID/popular?page=1"
```

API docs are at `http://localhost:8902/docs/` (OpenAPI document at `/openapi.yaml`); gRPC is served on the same port. See [docs/api.md](docs/api.md), [docs/extensions.md](docs/extensions.md) and [docs/configuration.md](docs/configuration.md).

Notes:

- All state is in `/data`. The image runs as user 10001. With a bind mount instead of a named volume, make the directory writable for that user, or run with `--user "$(id -u):$(id -g)"`.
- Set `MIHON_ADMIN_TOKEN` to choose the admin token yourself.
- The image has no browser engine. Sources that need a WebView work only with the optional browser service, a headless Chromium you run beside it (`MIHON_WEBVIEW_URL`, see [docs/configuration.md](docs/configuration.md#the-browser-service-for-webview-sources)).
- Extensions are untrusted code that runs inside the service. See [docs/deployment.md](docs/deployment.md) for a hardened setup (read-only container, no capabilities, secrets, VPNs) and [compose.example.yaml](compose.example.yaml).

### From source

```sh
make setup   # downloads Gradle, a JDK 21 toolchain, Node.js, buf and dependencies
make run     # builds the admin UI, listens on :8902, keeps its state in ./data
make docker  # builds panelarr/mihon-source:dev for this machine
```

## Development

Everything runs through `make`, which wraps Gradle (`make help` lists the targets). You need a JDK 17+ to start Gradle. Gradle downloads JDK 21 for the build if you don't have it.

```sh
make check   # lint + tests, run before every commit
```

See [CONTRIBUTING.md](CONTRIBUTING.md) and [docs/](docs/README.md).

## Licence

mihon-source is licensed under the [GNU General Public License v3.0 or later](LICENSE).

The protobuf definitions in [`proto/`](proto) are licensed under the [Apache License 2.0](proto/LICENSE), so that other implementations of the API can use them freely. The source API protos in [`third_party/source-api/`](third_party/source-api) come from [source-api](https://github.com/panelarr/source-api), also under the [Apache License 2.0](third_party/source-api/LICENSE).

The Panelarr logo ([`admin/src/assets/panelarr-icon.svg`](admin/src/assets/panelarr-icon.svg), [`panelarr-icon-mono.svg`](admin/src/assets/panelarr-icon-mono.svg), and the favicons in [`admin/public/`](admin/public)) is licensed under [Creative Commons Attribution 4.0](https://creativecommons.org/licenses/by/4.0/), © the Panelarr authors.
