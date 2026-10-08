-- M2: GitHub App installations and the repositories they grant.
--
-- Both tables are keyed by GitHub's own numeric ids. Names change: an account
-- or a repository can be renamed or transferred, and a full_name can later be
-- reused by a different repository. The id is the only thing that stays put.
--
-- Nothing is ever deleted. An uninstalled App or a repository taken out of the
-- installation gets removed_at instead: inbox items will point at these rows,
-- and a hard delete would either cascade that history away or fail on it.
--
-- Values that are GitHub's vocabulary (account_type, repository_selection) are
-- deliberately not CHECK-constrained. A new value from GitHub would turn a
-- valid delivery into a failed one; constraints here guard our own invariants.

CREATE TABLE github_installation (
    installation_id      bigint      PRIMARY KEY,
    account_id           bigint      NOT NULL,
    account_login        text        NOT NULL,
    -- 'User' or 'Organization' today.
    account_type         text        NOT NULL,
    -- 'all' or 'selected': whether new repositories join automatically.
    repository_selection text        NOT NULL,
    suspended_at         timestamptz,
    removed_at           timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT github_installation_updated_at_ck
        CHECK (updated_at >= created_at)
);

-- Keyed by repository_id alone, not (installation_id, repository_id). A
-- repository belongs to one account, and an App has at most one installation
-- per account, so it is in at most one live installation at a time. When the
-- App is uninstalled and installed again, GitHub issues a new installation id:
-- the repository row moves to it, and keeps its identity and history.
CREATE TABLE github_repository (
    repository_id   bigint      PRIMARY KEY,
    installation_id bigint      NOT NULL REFERENCES github_installation (installation_id),
    -- 'owner/name'. Display and lookup only, never an identifier: not unique,
    -- because a rename and a new repository taking the old name can be seen in
    -- either order.
    full_name       text        NOT NULL,
    private         boolean     NOT NULL,
    removed_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT github_repository_full_name_ck
        CHECK (full_name LIKE '%_/_%'),

    CONSTRAINT github_repository_updated_at_ck
        CHECK (updated_at >= created_at)
);

-- PostgreSQL does not index the referencing side of a foreign key. Without
-- this, "the live repositories of installation N" is a sequential scan. Partial,
-- because removed repositories are never listed.
CREATE INDEX github_repository_installation_idx
    ON github_repository (installation_id)
    WHERE removed_at IS NULL;
