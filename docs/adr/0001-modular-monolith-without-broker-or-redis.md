# 1. Modular monolith, no message broker and no Redis

Date: 2026-09-28
Status: accepted

## Context

Pato Commit receives GitHub webhooks at-least-once. A common design is to hand the
event to a broker (Kafka, RabbitMQ, SQS) and let workers process it. That design
is attractive in production and actively harmful in a single-developer product
with no throughput requirements: it adds an infrastructure dependency, a second
failure mode, and a delivery-semantics decision, before a single inbox item
exists.

A second candidate was Redis — for the GitHub installation-token cache, rate
limiting, or a work queue.

## Decision

One Spring Boot process, one PostgreSQL database, no broker, no Redis.

Webhook processing uses a database-backed queue: a delivery is written to
`webhook_delivery` before the HTTP response is sent, and an in-process worker
drains rows whose status is `PENDING`. Crash recovery is the same code path as
normal processing — the worker re-reads the row from the database, so a crash
simply leaves rows for the next start. A scheduled sweeper picks up rows that
were interrupted mid-flight.

The installation-token cache is a `ConcurrentHashMap` with expiry inside the
single process.

## Consequences

- Correctness comes from the database, not from a broker's delivery guarantees.
  Idempotency has a single source of truth.
- Throughput is bounded by one instance. This is not a constraint we can
  currently measure.
- Fan-out to other consumers (notifications, a public API) will eventually need
  something. It will be added at the point of need, against a measured limit.
- GitHub installation tokens are cached per-process, so they are lost on restart.
  One extra `POST /app/installations/{id}/access_tokens` call is cheap.

## Revisit when

- The sweeper cannot keep up: measure pending-row age, not throughput in
  general. When it cannot, extract the worker first (it already has a clean
  database boundary) and only then consider a broker.
- More than one API instance must run. The in-memory token cache is the first
  thing that breaks; it moves to Redis or becomes a per-instance cost.
- Webhook volume reaches a point where the 10-second acknowledgement window is
  threatened.
