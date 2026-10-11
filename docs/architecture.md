# Architecture (for contributors)

mihon-source is one JVM process with no database. Its state is plain files under the data directory ([configuration](configuration.md)).

## Modules

| Gradle module | Contents |
|---|---|
| `api` | Code generated from `proto/`: messages, gRPC stubs, Kotlin coroutine stubs. Nothing hand-written |
| `extension-api` | The Mihon source API extensions are compiled against (`eu.kanade.tachiyomi.*`), binary compatible with extensions-lib 1.4 and 1.6, plus the libraries extensions expect from their host |
| `android-compat` | Our own replacements for the Android APIs extensions use: Context and SharedPreferences, Uri, Base64, Bitmap/Canvas (on Java 2D), Html, Looper/Handler and `Dispatchers.Main`, preference classes, QuickJS/Duktape on GraalJS, and inert stubs for UI and WebView |
| `service` | The service itself |
| `admin` | The admin web UI (React + TanStack), built with Node through Gradle and bundled into the service jar |
| `test-extensions/*` | Small extensions built the way real ones are, used as test fixtures |
| `build-logic` | Convention plugins: Kotlin/JVM setup with detekt and ktlint, buf tasks, test extensions |

## The service

| Package | Role |
|---|---|
| `http` | The network edge (Armeria): gRPC, HTTP/JSON transcoding, problem+json errors, OpenAPI and Swagger UI, image and upload routes, the admin UI |
| `api` | gRPC service implementations. Plain grpc-kotlin services that don't know about Armeria |
| `auth` | Admin token, client API keys, scopes, and the interceptor that checks them on every call |
| `extension` | Installing extensions: inspection, signatures, APK conversion, classloaders, the extension manager |
| `mihon` | Running Mihon sources: the host setup, instantiation, mapping to the protos, settings, errors |
| `network` | Per-source networking: profiles and their store, the per-source client, solvers, cookies, rate limits |
| `store` | Extension stores: index formats, pinning, install and update |
| `secret`, `storage`, `config`, `net` | Encryption of secrets at rest, atomic JSON files, environment settings, downloads |

## Loading an extension

1. **Inspect.** An APK is told from a jar by content. APK signatures are verified with apksig; jar (v1) signatures by reading every entry. The fingerprint is the SHA-256 of the signing certificate. The manifest (binary in APKs, usually plain text in jars) gives the package, version, lib version, content warning and source classes.
2. **Convert.** APKs go through dex2jar. Its output has no stack map frames, so they're recomputed with ASM against the extension's own classes and the host API. The APK's `assets/` are copied in.
3. **Load.** Each extension gets a classloader whose parent exposes only the host API packages (`eu.kanade.tachiyomi.*`, `android.*`, OkHttp, jsoup, kotlinx, Injekt, …) and hides the rest of the service. Delegation is parent first, as on Android, so an extension can't replace the API, yet its own classes in those packages (obfuscated ones, for example) still load from its jar.
4. **Instantiate.** The classes named in the manifest are created; each is a `Source` or a `SourceFactory`. Any failure is recorded on that extension only.

## Per-source networking

Extensions get their HTTP client from `network.client` on `HttpSource`. In Mihon that's one global client; here `network` is a separate helper per source instance. Its client exists before the source's id is known (extensions build clients in constructors), so everything that depends on the source's network profile is looked up when a request or connection happens: the proxy (a `ProxySelector` and proxy authenticator), cookies (a per-source jar, or a named shared one), user agent, extra headers, timeouts, concurrency, rate limits and the challenge solver. Each client has its own connection pool, so a connection made through one source's proxy is never reused by another. No JVM-wide proxy settings are used.

The client's interceptors are named `UncaughtExceptionInterceptor`, `UserAgentInterceptor` and `CloudflareInterceptor` on purpose: some extensions look for those names. Solves for the same host are coalesced, and the solver is told about the source's proxy, because clearance cookies are tied to the client's IP address.

A source's profile is, in order: one pushed by a host (`SetNetworkProfile`), the one assigned in the admin, the default profile, or a direct connection. All of it sits behind one `IntegrationsStore` interface, so configuration from a host can replace the local files later.

## Why these choices

- **Protobuf first.** The protos are the contract. Kotlin code, HTTP routes and the OpenAPI document all come from them, so the three can't drift apart.
- **Armeria** serves gRPC, gRPC-Web and HTTP/JSON transcoding from one service, with no proxy or second server. The services are standard grpc-kotlin services and auth is a standard gRPC interceptor, so only the `http` package would change if the server library ever did.
- **Auth checks every RPC by name.** `MethodScopes` maps each RPC to the scope it needs. An RPC that isn't listed needs `admin`, so new endpoints are closed until someone opens them on purpose.
- **Tokens are hashed with SHA-256.** They are 256 random bits, so they can't be guessed. A slow password hash would add nothing but latency on every request.
- **Our own Android shims**, written from the public API documentation, cover exactly what extensions reference.
- **Files, not a database.** The state is small, and each kind of state lives in its own JSON file, written to a temporary file and moved into place.
