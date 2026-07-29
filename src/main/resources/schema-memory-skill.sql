-- ══════════════════════════════════════════════════════════════════
--  Hermes Memory & Skill Centralisation Schema
--  Sprint C: Postgres tables for MemoryStore + SkillStore
-- ══════════════════════════════════════════════════════════════════

-- ── Extensions ────────────────────────────────────────────

CREATE EXTENSION IF NOT EXISTS vector;    -- pgvector for semantic search
CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- trigram for ILIKE/BM25 fallback

-- ══════════════════════════════════════════════════════════════════
--  Memory: Long-term agent_memory
-- ══════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS agent_memory (
    id          TEXT PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    user_id     VARCHAR(64),
    agent_id    VARCHAR(64),
    type        VARCHAR(32) NOT NULL,      -- PREFERENCE/DECISION/FACT/CONTEXT/FEEDBACK
    content     TEXT NOT NULL,
    category    VARCHAR(64),
    metadata    JSONB DEFAULT '{}',
    embedding   TEXT,                       -- pgvector column (vector(1536)) when available
    created_at  BIGINT NOT NULL,            -- epoch millis
    valid_from  BIGINT,                     -- when this fact became true
    valid_until BIGINT,                     -- NULL = still valid; set on invalidation
    expires_at  BIGINT,                     -- NULL = never expires; TTL-based
    source      VARCHAR(128)                -- e.g. "session_decay:abc123"
);

CREATE INDEX IF NOT EXISTS idx_am_tenant_user
    ON agent_memory(tenant_id, user_id);
CREATE INDEX IF NOT EXISTS idx_am_tenant_agent
    ON agent_memory(tenant_id, agent_id);
CREATE INDEX IF NOT EXISTS idx_am_valid
    ON agent_memory(tenant_id) WHERE valid_until IS NULL;
CREATE INDEX IF NOT EXISTS idx_am_content_trgm
    ON agent_memory USING gin(content gin_trgm_ops);

-- Optional: pgvector index (uncomment when embedding column is vector type)
-- CREATE INDEX IF NOT EXISTS idx_am_embedding
--     ON agent_memory USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- ══════════════════════════════════════════════════════════════════
--  Memory: Short-term session_message (with decay stage)
-- ══════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS session_message (
    id          TEXT PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    session_id  VARCHAR(64) NOT NULL,
    role        VARCHAR(16) NOT NULL,        -- user/assistant/tool/system
    content     TEXT NOT NULL,
    created_at  BIGINT NOT NULL,             -- epoch millis
    stage       VARCHAR(16) NOT NULL DEFAULT 'FULL',  -- FULL/WARM/COOL/EVICTED
    summary     TEXT                          -- COOL stage: LLM-generated summary
);

CREATE INDEX IF NOT EXISTS idx_sm_session
    ON session_message(tenant_id, session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_sm_stage
    ON session_message(tenant_id, session_id, stage);

-- ══════════════════════════════════════════════════════════════════
--  Memory: Agent experience (learned patterns)
-- ══════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS agent_experience (
    id          TEXT PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    agent_id    VARCHAR(64) NOT NULL,
    category    VARCHAR(64) NOT NULL,
    content     TEXT NOT NULL,
    created_at  BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ae_agent
    ON agent_experience(tenant_id, agent_id, category, created_at DESC);

-- ══════════════════════════════════════════════════════════════════
--  Skill: Registry + Version history
-- ══════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS skill_registry (
    id              TEXT PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    scope           VARCHAR(16) DEFAULT 'PRIVATE',   -- PRIVATE/SHARED/SYSTEM
    type            VARCHAR(32) DEFAULT 'CUSTOM',     -- BUILTIN/CUSTOM/CONNECTOR
    enabled         BOOLEAN DEFAULT true,
    current_version VARCHAR(64) DEFAULT '1.0.0',
    created_at      BIGINT NOT NULL,
    updated_at      BIGINT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_tenant_name
    ON skill_registry(tenant_id, name);
CREATE INDEX IF NOT EXISTS idx_skill_tenant
    ON skill_registry(tenant_id);
CREATE INDEX IF NOT EXISTS idx_skill_scope
    ON skill_registry(scope) WHERE scope != 'PRIVATE';

CREATE TABLE IF NOT EXISTS skill_version (
    id           TEXT PRIMARY KEY,
    skill_id     TEXT NOT NULL,
    version      VARCHAR(64) NOT NULL,
    config       TEXT NOT NULL,              -- JSON: SkillConfig
    published_at BIGINT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_version
    ON skill_version(skill_id, version);
CREATE INDEX IF NOT EXISTS idx_sv_skill
    ON skill_version(skill_id);

-- ══════════════════════════════════════════════════════════════════
--  Done. Tables are auto-created by PostgresMemoryStore/PostgresSkillStore
--  on first connect, but this file can be used for manual deployment
--  or migration tools (Flyway / Liquibase).
-- ══════════════════════════════════════════════════════════════════
