-- ============================================================
-- Hermes Agent Java - Centralized Config Storage (MySQL)
-- DDL + seed data
-- ============================================================

-- 租户模型配置
CREATE TABLE IF NOT EXISTS tenant_model_config (
    tenant_id       VARCHAR(64)  NOT NULL,
    provider        VARCHAR(32)  NOT NULL DEFAULT 'openrouter',
    model           VARCHAR(128) NOT NULL DEFAULT 'anthropic/claude-3.5-sonnet',
    base_url        VARCHAR(256) DEFAULT '',
    api_key         VARCHAR(256) DEFAULT '',
    temperature     FLOAT        DEFAULT 0.7,
    max_tokens      INT          DEFAULT 4096,
    key_source      VARCHAR(16)  DEFAULT 'hybrid',
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 租户 API Key
CREATE TABLE IF NOT EXISTS tenant_api_key (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   VARCHAR(64)  NOT NULL,
    provider    VARCHAR(32)  NOT NULL,
    api_key     VARCHAR(512) NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_provider (tenant_id, provider)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 租户 model_routes
CREATE TABLE IF NOT EXISTS tenant_model_route (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   VARCHAR(64)  NOT NULL,
    alias       VARCHAR(64)  NOT NULL,
    model       VARCHAR(128) NOT NULL,
    provider    VARCHAR(32)  DEFAULT NULL,
    base_url    VARCHAR(256) DEFAULT NULL,
    sort_order  INT          DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_alias (tenant_id, alias)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 平台预定义 model_routes
CREATE TABLE IF NOT EXISTS platform_model_route (
    alias       VARCHAR(64)  NOT NULL,
    model       VARCHAR(128) NOT NULL,
    provider    VARCHAR(32)  DEFAULT NULL,
    base_url    VARCHAR(256) DEFAULT NULL,
    sort_order  INT          DEFAULT 0,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (alias)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 平台 Provider Catalog
CREATE TABLE IF NOT EXISTS platform_provider (
    provider_id         VARCHAR(32)  NOT NULL,
    display_name        VARCHAR(64)  NOT NULL,
    default_base_url    VARCHAR(256) NOT NULL,
    allow_tenant_keys   TINYINT(1)   DEFAULT 1,
    allow_platform_keys TINYINT(1)   DEFAULT 1,
    supported_models    TEXT         DEFAULT NULL,
    sort_order          INT          DEFAULT 0,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (provider_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 平台 API Key 池（代付 key）
CREATE TABLE IF NOT EXISTS platform_api_key (
    provider    VARCHAR(32)  NOT NULL,
    api_key     VARCHAR(512) NOT NULL,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (provider)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 租户配额
CREATE TABLE IF NOT EXISTS tenant_quota (
    tenant_id                   VARCHAR(64)  NOT NULL,
    max_daily_requests          INT          DEFAULT 10000,
    max_daily_tokens            BIGINT       DEFAULT 10000000,
    max_concurrent_agents       INT          DEFAULT 5,
    max_concurrent_sessions    INT          DEFAULT 10,
    max_storage_bytes           BIGINT       DEFAULT 1073741824,
    max_memory_bytes            BIGINT       DEFAULT 536870912,
    requests_per_second         INT          DEFAULT 10,
    requests_per_minute         INT          DEFAULT 100,
    max_tool_calls_per_session  INT          DEFAULT 100,
    max_file_size_bytes         BIGINT       DEFAULT 104857600,
    allow_code_execution        TINYINT(1)   DEFAULT 1,
    max_private_skills          INT          DEFAULT 50,
    max_installed_skills        INT          DEFAULT 100,
    on_exceed                   VARCHAR(16)  DEFAULT 'BLOCK',
    degrade_model               VARCHAR(128) DEFAULT NULL,
    degrade_provider            VARCHAR(32)  DEFAULT NULL,
    updated_at                  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 计费记录
CREATE TABLE IF NOT EXISTS billing_record (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id           VARCHAR(64)  NOT NULL,
    model               VARCHAR(128) NOT NULL,
    provider            VARCHAR(32)  NOT NULL,
    input_tokens        BIGINT       NOT NULL DEFAULT 0,
    output_tokens       BIGINT       NOT NULL DEFAULT 0,
    total_tokens        BIGINT       NOT NULL DEFAULT 0,
    estimated_cost_usd  DECIMAL(12,6) NOT NULL DEFAULT 0,
    session_id          VARCHAR(128) DEFAULT NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_tenant_date (tenant_id, created_at),
    INDEX idx_tenant_model (tenant_id, model)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- Seed: 平台 Provider Catalog
-- ============================================================
INSERT INTO platform_provider (provider_id, display_name, default_base_url, allow_tenant_keys, allow_platform_keys, supported_models, sort_order) VALUES
('openai',    'OpenAI',               'https://api.openai.com/v1',                1, 1, '["gpt-4o","gpt-4o-mini","gpt-4.1","gpt-4.1-mini","o1","o1-mini","o3","o4-mini"]', 1),
('anthropic', 'Anthropic',            'https://api.anthropic.com/v1',             1, 1, '["claude-3-5-sonnet","claude-3-5-haiku","claude-3-opus"]', 2),
('openrouter', 'OpenRouter',          'https://openrouter.ai/api/v1',             1, 1, '[]', 3),
('deepseek',  'DeepSeek',             'https://api.deepseek.com/v1',              1, 1, '["deepseek-chat","deepseek-reasoner"]', 4),
('doubao',    'Doubao (Volcengine)',  'https://ark.cn-beijing.volces.com/api/v3', 1, 1, '["doubao-pro-32k","doubao-pro-128k","doubao-lite-4k"]', 5),
('moonshot',  'Moonshot (Kimi)',      'https://api.moonshot.cn/v1',               1, 1, '["moonshot-v1-8k","moonshot-v1-32k","moonshot-v1-128k"]', 6),
('minimax',   'MiniMax',              'https://api.minimax.chat/v1',               1, 1, '["abab6.5s-chat","abab6.5-chat"]', 7),
('ollama',    'Ollama (Local)',       'http://localhost:11434/v1',                1, 0, '["llama3","qwen2.5","mistral"]', 8)
ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP;

-- ============================================================
-- Seed: 平台预定义 model_routes
-- ============================================================
INSERT INTO platform_model_route (alias, model, provider, base_url, sort_order) VALUES
('fast',  'gpt-4o-mini',          'openai',    NULL, 1),
('smart', 'claude-3.5-sonnet',    'anthropic', NULL, 2),
('cheap', 'deepseek-chat',        'deepseek',  NULL, 3)
ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP;
