# Progress

The v0 plan, in slices. Each slice works end to end, has tests and ends with `make check` green.

- [x] **1. Scaffold:** Gradle build, `make` targets, lint, repo docs, CI. *(`chore/repo-scaffold`)*
- [x] **2. Protos, server skeleton and auth:** `panelarr.source.v1` and `panelarr.mihon.v1` protos, gRPC + HTTP/JSON + OpenAPI, admin token and scoped client API keys. *(`feat/api-protos-auth`)*
- [ ] **3. Extension loading and admin API:** install by URL or upload (jar, APK via dex2jar), lib 1.4–1.6 check, signing fingerprint, child-first classloader per extension, list/uninstall/reload.
- [ ] **4. Host classpath and Android shims:** vendored source API, host libraries, our own Android shims, GraalJS-backed JS engines.
- [ ] **5. Source API:** popular/latest/search with filters, series, chapters, pages, images, settings.
- [ ] **6. Per-source networking and integrations:** OkHttp per source from network profiles, solvers, cookies, rate limits.
- [ ] **7. Extension stores:** add, refresh, list available, install and update, signing-key pinning.
- [ ] **8. Admin SPA:** React + TanStack admin served at `/`.
- [ ] **9. Image and docs:** multi-arch Docker image, quick start, configuration and API docs.

**Next:** slice 3.
