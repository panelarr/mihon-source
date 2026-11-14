# Extensions and stores

mihon-source runs Mihon extensions. It ships with **none** and with **no store configured**: you choose what to install.

## Installing

- **From a URL or an upload** (`InstallExtension`, or `POST /v1/admin/extensions/upload`): a `.jar` or an `.apk`. You can pass the expected SHA-256 of the file and the expected signing fingerprint; a mismatch aborts the install.
- **From a store** (`MihonStoresService`): store indexes are refreshed every 12 hours by default (`MIHON_STORE_REFRESH_INTERVAL`), so available updates are listed, but updates are only installed when you ask. Add the store's `repo.json` URL (or an index URL), browse what it offers, install and update. When a store is added, the signing key it announces is **pinned**: every file installed from it must be signed with that key, and a later refresh that sees a different key fails instead of changing the pin. To trust a new key, remove the store and add it again.

Supported: extensions built against extensions-lib **1.4 to 1.6**. Others are refused with an error that says why.

Icons: an extension's launcher icon is taken from its file when it uses the usual resource name, otherwise from the store it was installed from. They're served by the service (see [the API](api.md)).

What's reported for each installed extension: version, lib version, sources, the SHA-256 of the file, the signing certificate fingerprint (empty when unsigned), and load errors.

## Languages

Many extensions provide one source per language. An extension's **languages** setting (the Languages control on its card in the admin, or `SetExtensionLanguages`, `PUT /v1/admin/extensions/{pkgName}/languages`) decides which of them exist: sources in other languages aren't listed, health checks skip them, and calls to them answer NOT_FOUND. None set means all. The setting survives updates. `"all"` and `"multi"` are languages like any other: tick them to keep multi-language sources.

Installing the version that's already installed (and working) is a no-op, so retrying an install is safe. An install that's cancelled while downloading leaves the previous version in place; once the download is done, it runs to the end.

## Store formats

`repo.json` with an `index_v2` pointer to a protobuf index (`index.pb`, possibly gzipped) or a JSON index, including the optional `jarUrl` field some stores add (jars skip the APK conversion). A legacy `index.min.json` works as a fallback; it has no signing key.

## Settings and data

A source's settings come from its preference screen and are stored like on Android, per source, under `prefs/`. Its cookies are kept under `cookies/`. Uninstalling keeps both unless you ask to delete the data. Some extensions read settings once when they're created; reload the extension if a change doesn't take effect.

## Android behaviour

Extensions are written for Android, and some Android behaviour differs from the JVM's. Where it matters, the host follows Android:

- **Dates:** Android's `SimpleDateFormat` is ICU's, which accepts more pattern letters, reads `ZZZZZ` as an ISO offset (`+02:00`) and `SSSSSS` as a fraction of a second. When an extension is loaded, its `new SimpleDateFormat(…)` calls are rewritten to create an ICU-backed subclass, so dates parse as they do on a phone. Nothing else in the extension changes.

## WebView

mihon-source has no browser engine of its own. Extensions that subclass `WebViewClient` are flagged when they load and report the `needs_webview` capability. Without the [optional browser service](configuration.md#the-browser-service-for-webview-sources) creating a WebView throws, so most of their calls fail, and checks report `NEEDS_WEBVIEW` with a hint. With it, every WebView an extension creates drives a page in the browser over CDP:

| Android | In the browser |
|---|---|
| `loadUrl` (with extra headers), `loadData`, `reload`, `stopLoading` | `Page.navigate`; extra headers are added to the main request only |
| `loadDataWithBaseURL` | The base URL is loaded and its document is answered with the data, so the page gets the base URL's origin (a data URL when the base isn't http(s)) |
| `evaluateJavascript` | `Runtime.evaluate`; the callback gets JSON, as on Android: strings quoted, `undefined`, functions and failures as `null`. Promises aren't awaited |
| `addJavascriptInterface` | A CDP binding plus an injected object with the `@JavascriptInterface` methods, from the next page load on |
| `getSettings`: user agent, JavaScript enabled | `Emulation.setUserAgentOverride`, `Emulation.setScriptExecutionDisabled`. JavaScript is off by default, as on Android |
| `onPageStarted`, `onPageFinished` | The main frame's navigation and load events |
| `shouldInterceptRequest`, `shouldOverrideUrlLoading` | Requests are paused (`Fetch.requestPaused`) and answered with `fulfillRequest`, `continueRequest` or, for an overridden navigation, `failRequest`. Only clients that override these pay for it |
| `onReceivedError` | A main-frame load that failed or timed out, followed by `onPageFinished` for the failed URL. Chromium's own error page fires no callbacks, and `getUrl`/`getTitle` give the failed URL |
| `CookieManager` | Before a load the manager's cookies for the URL go into the page; after it, the page's cookies come back into the manager |
| `destroy` | The page and its browser context close |

**Threading.** Extensions use a WebView as on Android: they create and call it on the main looper (`Handler(Looper.getMainLooper())` or `Dispatchers.Main`) and wait on their own thread for a callback. A source call runs on one of the service's I/O threads, never an RPC thread, so that wait blocks only the call. Every WebView method just queues a command and returns, whatever thread calls it, so the main looper never waits for the browser. Each WebView runs its commands in order on its own coroutine. Those coroutines, and the CDP traffic, run on threads of their own, never on the I/O threads that waiting source calls hold, so many blocked calls can't starve the callbacks they wait for. Page callbacks, URL-loading decisions and script results are posted to the main looper. `shouldInterceptRequest` and JavaScript interface methods run on a separate pool of background threads, as on Android. Anything extension code throws in a callback is logged and handled as Android would: a throwing `shouldInterceptRequest` lets the request through, and a broken response stream fails only that request. The WebView keeps working.

Each WebView has its own browser context: WebViews share cookies only through `CookieManager`. A WebView the extension leaves idle for the load timeout (`MIHON_WEBVIEW_LOAD_TIMEOUT`) gives its page back, so a forgotten WebView can't hold one of the `MIHON_WEBVIEW_MAX_PAGES` pages; using it again opens a fresh, blank page (`getUrl` and `getTitle` are null until the next load).

Not supported yet: JavaScript interface methods can't return values to the page (CDP bindings are one-way), so sources that need that don't work. `WebChromeClient` and `onReceivedHttpError` are never called, and subresource failures aren't reported. Pages don't use the source's network profile (proxy, user agent) yet, and cookies don't sync with the source's own cookie jar. `CookieManager` is shared by every source, as on Android, so a cookie one source's WebView picked up reaches another source's WebView on the same host. A challenge built to need a person may still fail headless.

## Limits

- **JavaScript:** QuickJS and Duktape are emulated on GraalJS, started on first use. Scripts get no host access and a 30-second limit per evaluation.
- **Images:** WebP is decoded but not encoded. Extensions that re-encode images as WebP get JPEG or PNG instead.
- **Untrusted code:** extensions run inside the service's JVM. Their classloader hides the service's own classes, but it's not a sandbox. Install only extensions you trust, verify fingerprints, and run the service in its own hardened container ([deployment](deployment.md)).
