# Persistence

Hermes Agent Java uses a layered persistence strategy for tenant state, sessions, quota usage, trajectories, and audit data.

## Tenant State Repositories

`TenantStateRepository` defines the storage contract for tenant snapshots and session state.

Implemented backends:

- `PostgresTenantRepository` — production-oriented PostgreSQL storage
- `FileSystemTenantRepository` — lightweight JSON-file storage for local/dev deployments

## FileSystemTenantRepository Safety

The filesystem backend protects JSON state files with a two-step data safety chain:

1. **Temporary file write**
   - Data is first written to `*.tmp`.
2. **Atomic replacement**
   - The temp file is moved into place with `ATOMIC_MOVE` where supported.
   - If atomic move is not supported by the filesystem, it falls back to `REPLACE_EXISTING`.
3. **Backup preservation**
   - Before replacing an existing JSON file, the previous version is copied to `*.bak`.
4. **Backup recovery**
   - Load operations first try the primary JSON file.
   - If the primary file is missing or corrupted, the repository tries the `*.bak` file.

Example layout:

```text
~/.hermes/persistence/
├── tenants/{tenantId}/
│   ├── state.json
│   └── state.json.bak
└── sessions/{tenantId}/
    ├── {sessionId}.json
    └── {sessionId}.json.bak
```

## Session Persistence

Distributed session state serializes conversation context through `SessionSerializer` / `JsonSessionSerializer`.

Persisted data includes:

- session id
- tenant id
- node id
- created / last activity timestamps
- metadata
- active flag
- conversation messages

## Quota Usage Persistence

`TenantQuotaManager` stores current usage in:

```text
~/.hermes/tenants/{tenantId}/state/usage.json
```

Historical usage is archived daily:

```text
~/.hermes/tenants/{tenantId}/state/history/YYYY-MM-DD.json
```

## Trajectory Persistence

Trajectory collection stores:

```text
~/.hermes/trajectories/
├── trajectory_samples.jsonl
├── failed_trajectories.jsonl
├── compressed/{trajectoryId}.json
└── insights.jsonl
```

## Operational Notes

- Prefer PostgreSQL for production multi-node deployments.
- Filesystem storage is useful for local development, embedded deployments, and simple single-node setups.
- For filesystem deployments, ensure the persistence directory is on durable storage.
- Backups (`*.bak`) protect against partial corruption, but do not replace full off-host backup strategies.
