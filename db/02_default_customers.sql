-- cern-temporal-workflow-orch-service - default customers (owner-approved; the ONLY seed data).
--
-- Run by hand AFTER db/01_schema.sql. The service never runs this file.
--
--   docker exec -i cern-orch-app-postgres psql -U cern -d cern_workflow_orch < db/02_default_customers.sql
--
-- Phone numbers are deliberately fake (+91 00000 000xx). Idempotent: re-running changes nothing.

INSERT INTO orch.customers (customer_id, name, phone, initial_channel) VALUES
    ('C1001', 'Test Customer One',   '+910000000001', 'VOICE'),
    ('C1002', 'Test Customer Two',   '+910000000002', 'WHATSAPP'),
    ('C1003', 'Test Customer Three', '+910000000003', 'VOICE'),
    ('C1004', 'Test Customer Four',  '+910000000004', 'WHATSAPP'),
    ('C1005', 'Test Customer Five',  '+910000000005', 'VOICE'),
    ('C1006', 'Test Customer Six',   '+910000000006', 'WHATSAPP')
ON CONFLICT (customer_id) DO NOTHING;
