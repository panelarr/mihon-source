# Extensions and stores

mihon-source runs Mihon extensions. It ships with **none** and with **no store configured**: you choose what to install.

## Installing

- **From a URL or an upload** (`InstallExtension`, or `POST /v1/admin/extensions/upload`): a `.jar` or an `.apk`. You can pass the expected SHA-256 of the file and the expected signing fingerprint; a mismatch aborts the install.
- **From a store** (`MihonStoresService`): store indexes are refreshed every 12 hours by default (`MIHON_STORE_REFRESH_INTERVAL`), so available updates are listed, but updates are only installed when you ask. Add the store's `repo.json` URL (or an index URL), browse what it offers, install and update. When a store is added, the signing key it announces is **pinned**: every file installed from it must be signed with that key, and a later refresh that sees a different key fails instead of changing the pin. To trust a new key, remove the store and add it again.

Supported: extensions built against extensions-lib **1.4 to 1.6**. Others are refused with an error that says why.

Icons: an extension's launcher icon is taken from its file when it uses the usual resource name, otherwise from the store it was installed from. They're served by the service (see [the API](api.md)).

What's reported for each installed extension: version, lib version, sources, the SHA-256 of the file, the signing certificate fingerprint (empty when unsigned), and load errors.

## Store formats

`repo.json` with an `index_v2` pointer to a protobuf index (`index.pb`, possibly gzipped) or a JSON index, including the optional `jarUrl` field some stores add (jars skip the APK conversion). A legacy `index.min.json` works as a fallback; it has no signing key.

## Settings and data

A source's settings come from its preference screen and are stored like on Android, per source, under `prefs/`. Its cookies are kept under `cookies/`. Uninstalling keeps both unless you ask to delete the data. Some extensions read settings once when they're created; reload the extension if a change doesn't take effect.

## Android behaviour

Extensions are written for Android, and some Android behaviour differs from the JVM's. Where it matters, the host follows Android:

- **Dates:** Android's `SimpleDateFormat` is ICU's, which accepts more pattern letters, reads `ZZZZZ` as an ISO offset (`+02:00`) and `SSSSSS` as a fraction of a second. When an extension is loaded, its `new SimpleDateFormat(…)` calls are rewritten to create an ICU-backed subclass, so dates parse as they do on a phone. Nothing else in the extension changes.

## Limits

- **WebView:** there's no browser engine. Sources that drive a WebView report the `needs_webview` capability and most of their calls fail.
- **JavaScript:** QuickJS and Duktape are emulated on GraalJS, started on first use. Scripts get no host access and a 30-second limit per evaluation.
- **Images:** WebP is decoded but not encoded. Extensions that re-encode images as WebP get JPEG or PNG instead.
- **Untrusted code:** extensions run inside the service's JVM. Their classloader hides the service's own classes, but it's not a sandbox. Install only extensions you trust, verify fingerprints, and run the service in its own hardened container ([deployment](deployment.md)).
