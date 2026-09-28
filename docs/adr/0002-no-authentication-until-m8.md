# 2. No authentication until M8; the API is bound to loopback

Date: 2026-09-28
Status: accepted

## Context

Dockyard is a self-hosted tool for one developer. It reads private repositories
through a GitHub App installation. A login system is a large surface: sessions,
password or token storage, recovery, authorization, and every one of them is a
way to leak access to private source code.

The alternative — shipping the API unprotected and assuming nobody deploys it
publicly — is the one option that cannot be taken back.

## Decision

No authentication in the MVP. `server.address` is pinned to `127.0.0.1`, so the
API accepts connections from the local machine only.

Identity is not modelled. There is no `user` or `account` table and no session.
The single effective identity is the GitHub App installation whose account owns
the repositories.

`apps/web/next.config.ts` proxies `/api/backend/*` to the API server-side. The
browser therefore talks only to its own origin: no CORS configuration is needed,
and whatever credential M8 introduces will be added to the proxy, never to
client-side JavaScript.

## Consequences

- The API cannot be reached from another machine. Deploying it anywhere
  non-loopback requires adding authentication **first**; changing
  `server.address` is the last step, not the first.
- There is nothing to test for authorization yet, so the M0 test suite is small.
  That is the intended trade, not an oversight.
- A network-level exposure mistake (proxy in front, `server.forward-headers`,
  container port mapping) is the realistic failure mode. It is checked by
  asserting the listening socket is `127.0.0.1`.

## Revisit when

M8, when the tool is being used from somewhere other than the machine it runs
on. The planned change is a GitHub OAuth App with `read:user` and
`user:email` only — no `repo` scope, since the GitHub App installation already
carries repository access.
