# 3. A webhook-only port for tunnels

Date: 2026-10-08
Status: accepted
Amends: [0002](0002-no-authentication-until-m8.md)

## Context

GitHub has to reach the webhook endpoint, and the API is bound to `127.0.0.1`
with no authentication (ADR 0002). Some tunnel has to bridge the two.

- **smee.io** relays deliveries as parsed JSON and re-serialises them, so the
  bytes that arrive are not always the bytes GitHub signed, and the HMAC check
  can fail on a valid delivery.
- **A Cloudflare named tunnel** can restrict exposure by path at the edge, but
  needs a domain on Cloudflare.
- **A Cloudflare quick tunnel** (`cloudflared tunnel --url`) needs no account and
  no domain, and forwards the original bytes. It forwards *every* path to its
  target. Pointed at the API, it would publish Swagger, actuator and every read
  endpoint to the internet.

## Decision

The API listens on a second loopback port, `pato-commit.webhook.port`
(default `8090`), that serves `POST /api/webhooks/github` and nothing else.
Every other request on that port gets an empty 404. Tunnels point at this port,
never at `server.port`.

The check is made on the port the connection arrived on, which the server
socket reports and a caller cannot forge, and on an exact match of the raw
request URI, so an encoded or otherwise rewritten variant of the path is
refused rather than interpreted.

The webhook endpoint is the one part of the API designed to face the internet:
it authenticates every request with the webhook secret before reading anything
else.

## Consequences

- Any tunnel, including a quick tunnel with no path rules, exposes exactly one
  endpoint. Exposure is a property of the API, not of a tunnel configuration
  that has to be right every time.
- ADR 0002's rule still holds for everything else: `server.address` stays
  `127.0.0.1`, and no endpoint other than the webhook is reachable from another
  machine.
- A quick tunnel's URL changes on every run, so the App's webhook URL has to be
  updated each time. Acceptable for local testing; a named tunnel removes it.
- `WebhookPortTests` asserts the boundary against a real server: what the
  webhook port serves, what it hides, and which path variants it refuses.

## Revisit when

M8. Once the API has authentication, exposure stops depending on which port a
request uses, and a single public port behind a reverse proxy becomes possible.
