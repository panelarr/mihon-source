# Progress

The v0 plan, in slices. Each slice works end to end, has tests and ends with `make check` green.

- [x] **1. Scaffold:** Gradle build, `make` targets, lint, repo docs, CI. *(`chore/repo-scaffold`)*
- [x] **2. Protos, server skeleton and auth:** `panelarr.source.v1` and `panelarr.mihon.v1` protos, gRPC + HTTP/JSON + OpenAPI, admin token and scoped client API keys. *(`feat/api-protos-auth`)*
- [x] **3. Extension loading and admin API:** install by URL or upload (jar, APK via dex2jar), lib 1.4–1.6 check, signing fingerprint, child-first classloader per extension, list/uninstall/reload.
- [x] **4. Host classpath and Android shims:** vendored source API, host libraries, our own Android shims, GraalJS-backed JS engines.
- [x] **5. Source API:** popular/latest/search with filters, series, chapters, pages, images, settings.
- [x] **6. Per-source networking and integrations:** OkHttp per source from network profiles, solvers, cookies, rate limits.
- [x] **7. Extension stores:** add, refresh, list available, install and update, signing-key pinning.
- [x] **8. Admin SPA:** React + TanStack admin served at `/`.
- [x] **9. Image and docs:** multi-arch Docker image, quick start, configuration and API docs.

All v0 slices are done. The image is built by `make docker` (local platform) and by the Docker workflow (linux/amd64 and linux/arm64, pushed on main and tags).

## Optional browser service for WebView sources

- [x] **M1. Browser connection:** `MIHON_WEBVIEW_*` settings, a CDP client on OkHttp WebSockets (connect, reconnect, sessions, timeouts, page limit, idle contexts), an optional health check (degraded when unreachable) and metrics. Tested against a fake CDP server and a real headless Chromium (testcontainers).
- [x] **M2. `android.webkit` on CDP:** WebView, WebViewClient and CookieManager shims on the browser, with the threading rule documented; checks report WebView sources as `NEEDS_WEBVIEW` with a hint without a browser. Tested with a fixture extension that drives a WebView, against a real headless Chromium (testcontainers).
- [x] **M3. Per-source contexts and networking:** one browser context per source with its profile's proxy (and HTTP proxy credentials) and user agent, cookies synced both ways with the source's jar (and `CookieManager` backed by it), loads per host coalesced, limits per source context, `WEBVIEW_UNAVAILABLE` with recovery, and the admin Integrations card (status, Test). Tested against a fake CDP server and a real headless Chromium.
- [ ] **M4. Cookie import and clear** per source in the admin API and UI.
