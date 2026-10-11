# Contributing

Thanks for helping. This file explains how to set up, how we name things and how changes get merged.

## Setup

You need a JDK 17 or newer to start Gradle, plus `make`. Gradle downloads the JDK 21 toolchain and every dependency for you:

```sh
make setup
make check   # detekt, ktlint and all tests
```

`make help` lists every target. Each target wraps a Gradle task, so `./gradlew <task>` works too.

## Working conventions

- **Tasks:** everything runs through `make` (`setup`, `build`, `test`, `lint`, `fmt`, `check`, `run`, `docker`, `gen`, `clean`).
- **Branches:** `<type>/<scope>-<slug>`, e.g. `feat/loader-dex2jar`.
- **Commits and PR titles:** [Conventional Commits](https://www.conventionalcommits.org/): `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `perf`, `build`, `ci`. Add `!` for breaking changes. PRs are squash-merged, so the PR title becomes the commit message on `main`.
- **Reviews:** at least one approval and green checks before merge.
- **AI agents:** agents may open PRs and post reviews. Anything an agent posts from a person's account starts with a **⚠️ AI agent** line. Humans stay responsible for what they merge.

## What not to contribute

- **No third-party extensions, extension stores or links to them.** Not in code, tests, fixtures, docs or issues. Tests use the small test extensions built in this repo.
- No site names or URLs of content sources.

## Changing the API

The admin API protos in `proto/` are the source of truth for `panelarr.mihon.v1`. The source API (`panelarr.source.v1`) is shared by every Panelarr source plugin and lives in [source-api](https://github.com/panelarr/source-api). mihon-source pins a copy in `third_party/source-api/`: don't edit it (the build fails if you do). Change the protos in source-api, then pin the new version:

```sh
make sync-source-api REF=main   # or a tag; SOURCE_API_DIR defaults to ../source-api
```

After changing or pinning protos:

```sh
make fmt    # buf format
make gen    # regenerates the OpenAPI document in service/src/main/resources/openapi
make check  # includes buf lint
```

Commit the regenerated OpenAPI document with the proto change. CI fails when it is out of date. Generated Kotlin code is not committed; Gradle builds it.

## Code style

- Kotlin, official code style, enforced by ktlint (`make fmt` fixes most issues) and detekt.
- Explain *why* in comments when the code can't say it. Don't comment the obvious.
- New behaviour comes with tests.
- Every source file starts with an SPDX licence line: `// SPDX-License-Identifier: GPL-3.0-or-later` for code, `// SPDX-License-Identifier: Apache-2.0` for files in `proto/` and `third_party/source-api/`.

## Reporting security issues

See [SECURITY.md](SECURITY.md). Please don't open public issues for vulnerabilities.
