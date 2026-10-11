# Architecture (for contributors)

mihon-source is one JVM process. It is planned in these parts:

| Part | Role |
|---|---|
| API layer | Serves `panelarr.source.v1` (sources) and `panelarr.mihon.v1` (admin) over gRPC and HTTP/JSON, checks tokens and scopes |
| Extension loader | Installs extensions (jar, or APK via dex2jar), checks their metadata, loads each one in its own child-first classloader |
| Host classpath and Android shims | The Mihon source API and the libraries extensions expect, plus small replacements for the Android APIs they use |
| Networking | One HTTP client per source, built from that source's network profile |
| Stores | Extension store indexes, signing-key pinning, installs and updates |
| Admin UI | A small React SPA that only uses the public API |
| State | Plain files under `/data`, no database |

This page grows with each part as it's built.
