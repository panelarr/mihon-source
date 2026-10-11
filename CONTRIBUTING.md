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

## Code style

- Kotlin, official code style, enforced by ktlint (`make fmt` fixes most issues) and detekt.
- Explain *why* in comments when the code can't say it. Don't comment the obvious.
- New behaviour comes with tests.

## Reporting security issues

See [SECURITY.md](SECURITY.md). Please don't open public issues for vulnerabilities.
