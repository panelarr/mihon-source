# Configuration

mihon-source reads a few environment variables at start. Everything else (extensions, stores, network profiles, API clients) is managed through the API or the admin UI, and kept as files in the data directory.

| Variable | Default | Meaning |
|---|---|---|
| `MIHON_DATA_DIR` | `/data` | Where all state is kept. `make run` uses `./data` in the repository. |
| `MIHON_HOST` | `0.0.0.0` | Address to listen on. |
| `MIHON_PORT` | `8902` | Port for everything: gRPC, HTTP/JSON, OpenAPI and the admin UI. |
| `MIHON_ADMIN_TOKEN` | *(generated)* | The admin token. When unset, a token is generated on first start, printed once to standard output and stored only as a hash. Setting it overrides the stored one. |
| `MIHON_SECRET_KEY` | *(generated)* | Base64-encoded 32-byte key used to encrypt secrets at rest (proxy passwords). When unset, one is generated in `secret.key` in the data directory. Keep it with your backups: without it stored proxy passwords can't be decrypted. |
| `MIHON_MAX_EXTENSION_MB` | `50` | Largest extension file accepted, downloaded or uploaded. |
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

Files that hold hashes, keys, cookies or encrypted secrets are created readable by their owner only.

## Lost the admin token?

Stop the service, delete `auth/admin-token.json` and start it again: a new token is printed. Or set `MIHON_ADMIN_TOKEN`.
