# Admin UI

The admin web UI of mihon-source: browse and test sources, manage extensions, extension stores, API clients and network settings. It's a static single-page app (Vite, React, TypeScript, TanStack Router and TanStack Query) that the service serves at `/`.

The UI has no private endpoints and no logic of its own: every action is a plain call to the [documented API](../docs/api.md), with the admin token as a bearer token. Whatever the UI can do, a script can do too.

## Building and checking

Gradle runs everything here and downloads Node.js itself (the version is `nodejs` in `gradle/libs.versions.toml`), so you only need a JDK:

| Command | What it runs for the UI |
|---|---|
| `make build` / `./gradlew assemble` | `npm run build`: the static files in `admin/dist`, then a check that they have no inline scripts, `eval` or mock code |
| `make lint` / `./gradlew lint` | Biome (lint and format check), `tsc --noEmit`, and the check that the generated API types are up to date |
| `make test` / `./gradlew test` | Vitest unit and page tests |
| `make check` / `./gradlew check` | lint plus tests |
| `make fmt` / `./gradlew fmt` | Biome formatting and safe fixes |

Run just the UI's tasks with `./gradlew :admin:check`, `:admin:assemble` and so on.

## Development

With your own Node.js 22 or newer you can use npm directly (`npm ci` once). Without it, every npm script below also runs through Gradle with the Node.js it downloaded.

### Against a running service

```sh
# Terminal 1, from the repository root: the service, with a known admin token
MIHON_PORT=8902 MIHON_ADMIN_TOKEN=dev ./gradlew :service:run

# Terminal 2: the dev server on http://localhost:5173
cd admin
npm run dev            # or: ./gradlew :admin:dev
```

Sign in with the admin token (`dev` above). The dev server proxies `/v1`, `/openapi.yaml` and `/docs` to the service, `http://localhost:8902` by default. Point it elsewhere with `MIHON_BACKEND`, and move the dev server with `ADMIN_PORT`:

```sh
MIHON_BACKEND=http://localhost:18917 ADMIN_PORT=5174 npm run dev
```

### Mock mode (no service needed)

```sh
npm run dev:mock       # or: ./gradlew :admin:devMock
```

A service worker ([MSW](https://mswjs.io)) answers every API call from clearly fake, in-memory data in `src/mocks/`, so all pages work even where the service doesn't implement an endpoint yet. Changes last until you reload the page.

- Any token signs you in as admin, except `bad` (rejected with a 401) and tokens starting with `msk_` (treated as a client API key, which the UI refuses).
- The source "Fake Webview Source" fails on purpose, the "Legacy Example Index" store fails to refresh, and "Broken Example" is an extension that failed to load, to show error states.

Mock mode is only for the dev server: the build refuses `VITE_MOCK=true`, and the mock code and worker never reach `dist/`. The tests use the same handlers.

## The API client

`src/api/schema.d.ts` holds TypeScript types generated from the OpenAPI document (`service/src/main/resources/openapi/openapi.yaml`, itself generated from the protos), used with [openapi-fetch](https://openapi-ts.dev/openapi-fetch/). After the API changes (`make gen`), regenerate and commit it:

```sh
npm run gen:api        # or: ./gradlew :admin:genApi
```

`make check` fails while it's out of date. Two endpoints aren't in the OpenAPI document and have hand-written calls in `src/api/extras.ts`: `GET /v1/sources/{sourceId}/image?ref=` and `POST /v1/admin/extensions/upload`.

## How it works

- **Auth:** the login page checks the token with `GET /v1/whoami` and accepts only the admin token. The token is kept in `sessionStorage` (gone when the tab closes; never a cookie) and sent as `Authorization: Bearer`. A 401 for it signs out and returns to the login page.
- **Errors:** API errors show the `message` of the service's error body. Errors the service answered are not retried; network failures are retried twice.
- **Images** (covers, pages, source icons) come from the image endpoint, which needs the bearer header, so they're fetched with `fetch()` and shown from `blob:` URLs.
- **Routes** are client-side (`/sources`, `/sources/{id}`, `/extensions`, `/stores`, `/clients`, `/integrations`, `/login`). The server must answer unknown paths outside `/v1`, `/docs` and `/openapi.yaml` with `index.html`.
- **CSP:** the build is plain files: one script, one stylesheet, no inline scripts or styles, no `eval`. It works under `default-src 'self'; img-src 'self' blob:; connect-src 'self'` (external links open in a new tab and need nothing).

## Layout

| Path | Contents |
|---|---|
| `src/api/` | Generated types, the API client, error handling, the extra endpoints |
| `src/auth/` | The session token and the login check |
| `src/lib/` | Logic with tests: search filters ↔ `FilterValue`, settings JSON Schema → form fields, network profile form, durations |
| `src/pages/` | One file per page; `src/pages/sources/` for the source browser |
| `src/components/` | Small shared pieces: dialogs, images, badges, error and empty states |
| `src/mocks/` | Fake data and MSW handlers for mock mode and tests |
| `scripts/` | API type generation and the build output check |
