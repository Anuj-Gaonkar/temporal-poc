-- cern-temporal-workflow-orch-service - schema DDL
--
-- Own database ("cern_workflow_orch", created by docker compose), own schema ("orch"). The service
-- only READS this table; it never creates, inserts or updates anything. Temporal's own history
-- lives in a separate Postgres container, never here.
--
-- Run by hand, once, after `docker compose up -d`:
--
--   docker exec -i cern-orch-app-postgres psql -U cern -d cern_workflow_orch < db/01_schema.sql
--
-- Idempotent to re-run - every statement is guarded by IF NOT EXISTS.

CREATE SCHEMA IF NOT EXISTS orch;

CREATE TABLE IF NOT EXISTS orch.customers (
    customer_id     VARCHAR(50)  NOT NULL,
    name            VARCHAR(100) NOT NULL,
    phone           VARCHAR(20)  NOT NULL,
    initial_channel VARCHAR(20)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT customers_pkey PRIMARY KEY (customer_id),
    CONSTRAINT customers_initial_channel_check CHECK (initial_channel IN ('WHATSAPP', 'VOICE'))
);
