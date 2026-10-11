# Configuration

mihon-source reads a few environment variables at start. Everything else (extensions, stores, network profiles, API clients) is managed through the API and kept as files in the data directory.

| Variable | Default | Meaning |
|---|---|---|
| `MIHON_DATA_DIR` | `/data` | Where all state is kept. `make run` uses `./data` in the repository. |
| `MIHON_HOST` | `0.0.0.0` | Address to listen on. |
| `MIHON_PORT` | `8902` | Port for everything: gRPC, HTTP/JSON, OpenAPI and the admin UI. |
| `MIHON_ADMIN_TOKEN` | *(generated)* | The admin token. When unset, a token is generated on first start, printed once to standard output and stored only as a hash. Setting it later replaces the stored one. |

## Data directory

| Path | Contents |
|---|---|
| `auth/admin-token.json` | Hash of the generated admin token (not used when `MIHON_ADMIN_TOKEN` is set) |
| `auth/clients.json` | API clients: names, scopes and key hashes |

Files that hold hashes are created readable by their owner only. More files appear here as features land (extensions, source settings, cookies, stores, network profiles).

## Lost the admin token?

Stop the service, delete `auth/admin-token.json` and start it again: a new token is printed. Or set `MIHON_ADMIN_TOKEN`.
