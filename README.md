# Pato Commit

> What is happening across your GitHub repositories, and what needs you now?

Pato Commit is a self-hosted **Developer Inbox**. It reads a GitHub App's
installations, webhooks and REST API, and tells a developer which pull requests,
issues, failing workflows and security alerts are waiting on *them* right now.

It is a modular monolith. No microservices, no message broker, no Redis.

## Stack

| Layer | Choice |
|---|---|
| Web | Next.js 16.3 (App Router), TypeScript, Tailwind CSS 4, shadcn/ui |
| API | Java 25 LTS, Spring Boot 4.1.1, Maven |
| Data | PostgreSQL 18 (source of truth), Flyway (schema) |
| Local infra | Docker Compose |

Redis is intentionally absent: nothing in the MVP needs it. See `docs/adr/` when it
arrives (M8, alongside authentication).

## Layout

```
apps/
  api/    Spring Boot API
  web/    Next.js UI
compose.yaml    Local PostgreSQL
docs/adr/       Architecture decision records
```

## Prerequisites

- JDK 25 (Temurin)
- Node.js 24 (LTS) — see `apps/web/.nvmrc`
- Docker Desktop

Maven is **not** required: the API ships a Maven Wrapper (`apps/api/mvnw`) that
pins the Maven version inside the repository.

## Running locally

```bash
# 1. Start PostgreSQL
docker compose up -d

# 2. API  -> http://127.0.0.1:8080
cd apps/api && ./mvnw spring-boot:run

# 3. Web  -> http://localhost:3000
cd apps/web && npm install && npm run dev
```

The API has **no authentication yet** and is therefore bound to `127.0.0.1` only.
Do not widen `server.address` before M8.

### Configuration

Every setting has a working local default; override through the environment.

| Variable | Default |
|---|---|
| `PATO_COMMIT_DATASOURCE_URL` | `jdbc:postgresql://127.0.0.1:5432/pato_commit` |
| `PATO_COMMIT_DATASOURCE_USERNAME` | `pato_commit` |
| `PATO_COMMIT_DATASOURCE_PASSWORD` | `pato_commit` |
| `PATO_COMMIT_API_PORT` | `8080` |
| `PATO_COMMIT_API_ORIGIN` (web) | `http://127.0.0.1:8080` |

GitHub App credentials (`PATO_COMMIT_GITHUB_APP_ID`,
`PATO_COMMIT_GITHUB_PRIVATE_KEY`, `PATO_COMMIT_GITHUB_WEBHOOK_SECRET`) arrive in M2.
They are read from the environment and must never be committed.

## Validation

```bash
cd apps/api && ./mvnw -B -ntp clean verify
cd apps/web && npm run lint && npm run typecheck && npm run build
```

Observability endpoints on the API: `/actuator/health`, `/actuator/info`,
`/actuator/metrics`, `/actuator/prometheus`.

## Schema

Flyway owns the schema. Hibernate runs with `ddl-auto: validate`, so a mismatch
between an entity and the migrations fails the boot instead of silently drifting.
There are no migrations yet; the first one (`V1`) lands in M1.

## Status

| Milestone | Scope | State |
|---|---|---|
| M0 | Foundations, local infra, CI | done |
| M1 | Webhook intake: signature, idempotency, persistence | next |
| M2 | GitHub App auth, installations, repository sync | |
| M3 | Developer Inbox v1 — PR awaiting review | |
| M4 | Inbox breadth — issues, awaiting response | |
| M5 | Inbox — failing and flaky workflows | |
| M6 | Inbox — Dependabot and security alerts | |
| M7 | Hardening — rate limits, repo activity signal | |
| M8 | Authentication and exposure (deferred) | |
