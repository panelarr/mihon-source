# Compatibility checker

`compat-check` tells you which extensions from a store work in mihon-source. It talks to a **running** service through the admin API: it installs the chosen extensions from a store, records whether each one loads, optionally runs source checks, and uninstalls what it installed again.

```sh
make run                                    # or a container
export MIHON_ADMIN_TOKEN=...                # the service's admin token
make compat ARGS="--store <repo.json URL> --random 20"
make compat ARGS="--pkg <package> --checks all --keep"
```

By default it only **loads** extensions: nothing contacts the sites behind them. Ask for calls explicitly with `--checks`.

| Option | |
|---|---|
| `--url URL` | The service (default `http://localhost:8902`) |
| `--token TOKEN` | Admin token (default `$MIHON_ADMIN_TOKEN`) |
| `--store URL` | Add this store first, unless it's already added. Without it, every added store is used |
| `--all` / `--random N [--seed S]` / `--pkg A,B` | Which extensions: all (default), a random pick (repeatable with a seed), or these package names |
| `--lang L`, `--query TEXT` | Narrow the offer by source language or name |
| `--checks LIST` | `none` (default), `all`, or a list of `popular,latest,search,details,chapters,pages,image`. Each step feeds the next; needed steps are added |
| `--search-query Q` | Query for the search step (default: the first popular title) |
| `--concurrency N` | Extensions handled at once (default 4) |
| `--keep` | Keep what this run installed |
| `--out FILE` | Report path (default `build/compat-reports/<time>.json`, which git ignores) |

The output lists each extension as it's done, then a summary. Load failures are grouped by likely cause:

| Problem | Usually means |
|---|---|
| `missing class` / `missing member` | The extension uses an Android or library API the host doesn't provide (yet) |
| `bad bytecode` | The APK conversion produced classes the JVM rejects |
| `needs webview` | The extension needs a browser engine (see below) |
| `unsupported lib` | Built against an extensions-lib version outside 1.4–1.6 |
| `signature` | The file isn't signed by the store's pinned key |

Failing checks are grouped by step and error reason (for example `popular: CLOUDFLARE_CHALLENGE`). Sources that drive a WebView fail with `NEEDS_WEBVIEW` while the service has no [browser service](configuration.md#the-browser-service-for-webview-sources), and the summary says how to enable it. With the browser configured they're checked like any other source. The JSON report has every detail, including load error messages and each step's timing. Checks run through each source's own network profile and count towards its health (see [API](api.md#checking-sources)).

Be considerate: `--checks` makes real requests to every chosen site. Prefer `--random` or `--pkg` over checking a whole store.
