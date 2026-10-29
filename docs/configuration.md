# Configuration

mihon-source reads a few environment variables at start. Each one can also be given as a file: `NAME_FILE` holds the path of a file with the value (for Docker secrets), used when `NAME` itself isn't set. Everything else (extensions, stores, network profiles, API clients) is managed through the API or the admin UI, and kept as files in the data directory.

| Variable | Default | Meaning |
|---|---|---|
| `MIHON_DATA_DIR` | `/data` | Where all state is kept. `make run` uses `./data` in the repository. |
| `MIHON_TMP_DIR` | *(image: `/tmp`, or `$MIHON_DATA_DIR/tmp` when `/tmp` is noexec)* | The container image's temporary directory (`java.io.tmpdir`). It must allow running code: extensions load a native zstd decoder from it. See [deployment](deployment.md#why-tmp-needs-exec). Outside the image, set `-Djava.io.tmpdir` in `JAVA_OPTS` instead. |
| `MIHON_HOST` | `0.0.0.0` | Address to listen on. |
| `MIHON_PORT` | `8902` | Port for everything: gRPC, HTTP/JSON, OpenAPI and the admin UI. |
| `MIHON_ADMIN_TOKEN` | *(generated)* | The admin token. When unset, a token is generated on first start, printed once to standard output and stored only as a hash. Setting it overrides the stored one. |
| `MIHON_SECRET_KEY` | *(generated)* | Base64-encoded 32-byte key used to encrypt secrets at rest (proxy passwords). When unset, one is generated in `secret.key` in the data directory. Keep it with your backups: without it stored proxy passwords can't be decrypted. |
| `MIHON_MAX_EXTENSION_MB` | `50` | Largest extension file accepted, downloaded or uploaded. |
| `MIHON_LOG_FORMAT` | `json` | `json` (one JSON object per line: `time`, `level`, `logger`, `thread`, `msg`, `error`) or `text`. |
| `MIHON_LOG_LEVEL` | `INFO` | `TRACE`, `DEBUG`, `INFO`, `WARN` or `ERROR`. |
| `MIHON_METRICS_PORT` | *(none)* | Also serve `GET /metrics` on this port **without credentials**, e.g. for a scraper on an internal network. Without it, `/metrics` is only on the main port and needs a token with the `metrics` scope. |
| `MIHON_HEALTH_CHECK_INTERVAL` | *(off)* | How often the `source-health` job checks sources, e.g. `6h` or `1d` (plus up to 10% random delay). A round fetches the first page of popular from the selected sources (see the next two settings), two sources at a time in random order with a short random pause before each, through the source's own network profile and rate limits. Off by default; per-source opt-out in the admin UI or with `SetHealthCheck`. The job can always be run by hand. `SetHealthCheckSchedule` overrides this and the next two settings at runtime (see the [API](api.md#checking-sources)). |
| `MIHON_HEALTH_CHECK_LANGS` | *(all)* | Source languages scheduled health checks cover, comma-separated, e.g. `en,fr`. |
| `MIHON_HEALTH_CHECK_SAMPLE` | `10` | How many sources each scheduled round checks: the least recently checked first (ties in random order), so rounds rotate through every eligible source. `all` checks every eligible source each round. Combined with `MIHON_HEALTH_CHECK_LANGS`, the sample comes from those languages. |
| `MIHON_STORE_REFRESH_INTERVAL` | `12h` | How often the `store-refresh` job refreshes every store's index (plus up to 10% random delay), so updates show up. Refreshing never installs anything. `0` or `off` turns the schedule off; the job and single stores can always be refreshed by hand. |
| `MIHON_JOB_HISTORY` | `100` | How many runs of each job are kept. |
| `MIHON_CACHE_TTL_LIST` | `5m` | How long popular, latest and search results are reused. `0` or `off` turns caching off for them. |
| `MIHON_CACHE_TTL_DETAILS` | `10m` | The same for series details and chapter lists. |
| `MIHON_CACHE_TTL_PAGES` | `30m` | The same for a chapter's page list. Images themselves are never cached. |
| `MIHON_CACHE_TTL_FILTERS` | `1h` | The same for a source's filters. |
| `MIHON_CACHE_MAX_MB` | `64` | Memory the result cache may use; the least recently used results go first. `0` turns the cache off. |
| `MIHON_ADMIN_DIR` | *(bundled)* | Serve the admin UI from this directory instead of the copy in the jar. For development. |

## Network settings: solvers, profiles, assignments

Network settings are managed in the admin UI (Integrations) or with `MihonIntegrationsService`. They form a chain:

1. A **solver** is a FlareSolverr-compatible endpoint (FlareSolverr, Byparr), e.g. `http://flaresolverr:8191`. On its own it does nothing.
2. A **network profile** uses at most one solver, plus an optional proxy, user agent, headers, rate limit, concurrency limit, timeouts and cookie jar.
3. A profile applies to the sources **assigned** to it, or, as the **default profile**, to every source without an assignment.

Each source uses, in order: a profile pushed by a host (`SetNetworkProfile`), its assigned profile, the default profile, or a direct connection with no solver.

The common setup, a solver for every source, is one step: add the solver while no profiles exist and a profile `default` that uses it is created and made the default. Later, the Integrations page flags solvers that no profile uses, with a button to put one on the default profile.

A request counts as an anti-bot challenge when the site answers 403 or 503 with `cf-mitigated: challenge`, with a Cloudflare `Server` header, or with a challenge page in the body. A challenge is sent to the source's solver. Requests made after that use the clearance cookies and the user agent the solver reported, unless the profile pins a fixed user agent. A challenge with no solver configured fails with the `CLOUDFLARE_CHALLENGE` error reason. Other 403 and 503 responses are logged with the host, status and `Server` header, which helps when a challenge isn't recognized.

## Data directory

| Path | Contents |
|---|---|
| `auth/admin-token.json` | Hash of the generated admin token |
| `auth/clients.json` | API clients: names, scopes and key hashes |
| `secret.key` | The generated encryption key (unless `MIHON_SECRET_KEY` is set) |
| `extensions/<package>/` | Each installed extension: `extension.jar` (APKs are converted at install) and `extension.json` |
| `prefs/<source id>.json` | Each source's settings (the extension's SharedPreferences) |
| `prefs/named/` | Other SharedPreferences extensions create |
| `cookies/<source id>.json` | Each source's cookie jar. Shared jars are `cookies/shared-<name>.json` |
| `cache/` | The cache directory extensions see |
| `config/integrations.json` | Solvers, network profiles, assignments and the default profile. Proxy passwords are encrypted |
| `config/pushed-profiles.json` | Network profiles pushed by a host (SetNetworkProfile) |
| `stores/stores.json` | Added extension stores and their pinned signing keys |
| `stores/<store id>.json` | The last good copy of each store's extension list |
| `config/source-health.json` | Each source's last health check, and the sources scheduled checks skip |
| `config/health-check-schedule.json` | The health-check schedule set with `SetHealthCheckSchedule`, which overrides the `MIHON_HEALTH_CHECK_*` variables; absent until set |
| `jobs/<job id>.json` | Each job's recent runs, newest first |

Files that hold hashes, keys, cookies or encrypted secrets are created readable by their owner only.

Every file is saved by writing a temporary file, flushing it to disk, and renaming it over the old one, so a crash or a power cut leaves either the old version or the new one. A file that can't be read anyway (damaged by something outside the service) doesn't stop the service: it's renamed to `<name>.corrupt-<time>`, an error is logged, and the service carries on as if the file were missing. What that means depends on the file: a damaged `auth/clients.json` loses the API clients (create new keys with the admin token), a damaged `auth/admin-token.json` prints a new admin token, and a damaged store or settings file starts empty. The renamed file is kept for inspection; delete it once you no longer need it.

## Lost the admin token?

Stop the service, delete `auth/admin-token.json` and start it again: a new token is printed. Or set `MIHON_ADMIN_TOKEN`.
