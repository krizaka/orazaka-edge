<!-- krizaka-header -->
<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/krizaka/.github/main/profile/assets/sovkora-dark.svg">
  <img src="https://raw.githubusercontent.com/krizaka/.github/main/profile/assets/sovkora-light.svg" alt="Sovkora" width="96">
</picture>

# Sovkora Edge

**The AI that never leaves home.**

Transparent HTTP edge in front of every Sovkora service: routing, API-key → JWT exchange, CORS and tracing. Spring MVC + virtual threads.

[![CI](https://github.com/krizaka/sovkora-edge-service/actions/workflows/ci.yml/badge.svg)](https://github.com/krizaka/sovkora-edge-service/actions/workflows/ci.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/krizaka/sovkora-edge-service/badge)](https://scorecard.dev/viewer/?uri=github.com/krizaka/sovkora-edge-service)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Sovkora](https://img.shields.io/badge/part%20of-Sovkora-f67e23)](https://github.com/krizaka/sovkora#repositories)
[![Docs](https://img.shields.io/badge/docs-krizaka.com-f67e23)](https://www.krizaka.com/en/products/sovkora)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-edge&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-edge) [![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-edge&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-edge) [![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-edge&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-edge) [![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-edge&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-edge) [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-edge&metric=coverage)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-edge)

[Documentation](https://www.krizaka.com/en/products/sovkora) · [Website](https://www.krizaka.com) · [Krizaka on GitHub](https://github.com/krizaka)

</div>
<!-- /krizaka-header -->

**Layer:** Foundation — reusable by any Krizaka application · **Version:** `1.0.0-SNAPSHOT` · **License:** Apache-2.0 ·
part of the [Sovkora platform](https://github.com/krizaka/sovkora) by [Krizaka](https://krizaka.com)

## What it provides

The single HTTP entry point (port `8088`): routes `/api/v1/**` to the owning service, exchanges API
keys for JWTs (`ApiKeyExchangeFilter`), CORS, tracing. Spring MVC + virtual threads — not WebFlux,
not Spring Cloud Gateway. Reusable in front of any Krizaka service set.

## Position in the platform

| | |
|:---|:---|
| Depends on | [`sovkora-build`](https://github.com/krizaka/sovkora-build) |
| Used by | _no other Sovkora repository._ |
| Workspace path | `orazaka-apps/services/orazaka-edge` |

## Build

**Inside the Sovkora workspace** (recommended — every dependency is built from source):

```bash
git clone https://github.com/krizaka/sovkora.git && cd sovkora
node scripts/workspace.mjs clone          # clones every repository at its workspace path
./mvnw -f orazaka-apps/services/orazaka-edge/pom.xml verify
```

**Standalone** — upstream artifacts must be in `~/.m2` (built by the workspace) or resolvable from
GitHub Packages (`https://maven.pkg.github.com/krizaka/<repository>`, see the
[workspace README](https://github.com/krizaka/sovkora#consuming-packages)):

```bash
./mvnw verify
```

Requirements: JDK 21, Docker (Testcontainers integration tests).

## Governance

This repository follows the Sovkora governance contract — [AGENTS.md](https://github.com/krizaka/sovkora/blob/main/AGENTS.md)
in the workspace is normative; the local [AGENTS.md](AGENTS.md) only scopes it to this repository.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

---

<sub>A Krizaka product · [krizaka.com](https://www.krizaka.com) · Apache-2.0</sub>
