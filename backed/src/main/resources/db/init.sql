-- 企业知识库 RAG 业务表初始化（向量表 rag_embedding 由 PgVectorEmbeddingStore createTable 自建）
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(16)  NOT NULL DEFAULT 'USER',
    department    VARCHAR(64)  NOT NULL DEFAULT '',
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS knowledge_base (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(512) NOT NULL DEFAULT '',
    owner_id    BIGINT       NOT NULL REFERENCES users (id),
    department  VARCHAR(64)  NOT NULL DEFAULT '',
    visibility  VARCHAR(16)  NOT NULL DEFAULT 'PRIVATE',
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS rag_document (
    id          BIGSERIAL PRIMARY KEY,
    kb_id       BIGINT       NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
    file_name   VARCHAR(255) NOT NULL,
    file_type   VARCHAR(16)  NOT NULL,
    file_size   BIGINT       NOT NULL DEFAULT 0,
    status      VARCHAR(16)  NOT NULL DEFAULT 'PROCESSING',
    error_msg   VARCHAR(1024),
    chunk_count INT          NOT NULL DEFAULT 0,
    uploaded_by BIGINT       NOT NULL REFERENCES users (id),
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rag_document_kb ON rag_document (kb_id);

-- 工作流任务表：状态机 RUNNING→PENDING_CONFIRM→CONFIRMED/REJECTED→EXPORTED，异常进 FAILED
CREATE TABLE IF NOT EXISTS workflow_task (
    id             BIGSERIAL PRIMARY KEY,
    question       VARCHAR(1024) NOT NULL,
    kb_ids         VARCHAR(255)  NOT NULL DEFAULT '',
    status         VARCHAR(24)   NOT NULL DEFAULT 'RUNNING',
    intent         VARCHAR(16)   NOT NULL DEFAULT '',
    draft          TEXT          NOT NULL DEFAULT '',
    format_report  TEXT          NOT NULL DEFAULT '',
    sources        TEXT          NOT NULL DEFAULT '',
    steps          TEXT          NOT NULL DEFAULT '',
    confirm_comment VARCHAR(512) NOT NULL DEFAULT '',
    error_msg      VARCHAR(1024),
    created_by     BIGINT        NOT NULL REFERENCES users (id),
    created_at     TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_workflow_task_user ON workflow_task (created_by);