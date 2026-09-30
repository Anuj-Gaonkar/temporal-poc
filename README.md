# cern-temporal-workflow-orch-service

A small proof of concept that starts a **Temporal workflow per customer, defined in YAML**.
The service reads the customer's initial channel (`WHATSAPP` or `VOICE`) from Postgres; the YAML's first step depends on it
(VOICE: call, WHATSAPP: message). Both paths then converge: wait 30 minutes, send a message, end.
No external service is called: every activity only writes a log line.

Phase 1 is built (this branch). Docs are in [`docs/`](docs/README.md).

## Prerequisites

Java 21, Docker with Compose v2, `psql` is not needed on the host (the commands below use `docker exec`).

## Quick start

```bash
# 1. Infrastructure (Temporal + UI + both Postgres instances)
cp .env.example .env            # optional; the defaults are cern / cern
docker compose up -d

# 2. Database: run the scripts by hand, in this order. The app never runs them.
docker exec -i cern-orch-app-postgres psql -U cern -d cern_workflow_orch < db/01_schema.sql
docker exec -i cern-orch-app-postgres psql -U cern -d cern_workflow_orch < db/02_default_customers.sql   # C1001..C1006

# 3. App
./mvnw spring-boot:run

# 4. Start a workflow
curl -s localhost:8096/workflow-definitions
curl -s -X POST localhost:8096/workflows/start -H 'Content-Type: application/json' \
     -d '{"customerId":"C1001","definition":"OUTREACH_DEFAULT"}'
curl -s localhost:8096/workflows/OUTREACH_DEFAULT-C1001
```

Watch it in the Temporal UI: http://localhost:8089 (namespace `default`, task queue `CERN_WORKFLOW_ORCH`).
Logs go to the console and to `logs/cern-temporal-workflow-orch-service.log`.

Default customers: `C1001`, `C1003`, `C1005` are VOICE; `C1002`, `C1004`, `C1006` are WHATSAPP.

Stop: `Ctrl+C` the app, then `docker compose down` (add `-v` only if you want to wipe both databases, which also removes the customers).

## Ports (different from the root Chat-Bot-V3 stack on purpose, so both can run together)

| What | Here | Root stack |
|---|---|---|
| Temporal gRPC | 7234 | 7233 |
| Temporal UI | 8089 | 8088 |
| App Postgres | 5435 | (5434 is the shared chatbot Postgres) |
| App HTTP | 8096 | |

Container names are prefixed `cern-orch-`.

## API

| Method and path | Purpose |
|---|---|
| `GET /workflow-definitions` | Loaded definitions (name, description, step ids) |
| `POST /workflows/start` | Body `{"customerId","definition"}`. 202, or 400 / 404 `DEFINITION_NOT_FOUND` / 404 `CUSTOMER_NOT_FOUND` / 409 |
| `GET /workflows/{workflowId}` | Status via Temporal (RUNNING, COMPLETED, ...) |
| `GET /customers/{customerId}` | Read-only view of the customer row |

Workflow id is `<definition>-<customerId>`, e.g. `OUTREACH_DEFAULT-C1001`. Starting a customer whose workflow is running returns 409.

## Fast demo

The delay is 30 minutes. To demo quickly, edit `duration: 30m` in `workflow-definitions/outreach-default.yaml` (for example to `20s`)
and restart the app; there is no hot reload in Phase 1. Do not commit the shortened value.
Restart the app during the delay to see Temporal keep the timer: the follow-up message is still sent after the restart.

## Tests

```bash
./mvnw test        # plain unit tests, no Docker needed
```

## Postman

Import `postman/cern-temporal-workflow-orch-service.postman_collection.json` and `postman/local.postman_environment.json`
(`baseUrl = http://localhost:8096`), select the `local` environment and run the requests top to bottom.
Customer ids are collection variables. The duplicate (409) request needs the VOICE workflow started just before it to still be running,
so run it right after "Start workflow, VOICE customer" (with the shipped 30 minute YAML there is plenty of time; with a shortened YAML, be quick).
To rerun the collection, wait for the earlier workflows to complete first, otherwise the start requests return 409.

## Phase branches

| Branch | Contains |
|---|---|
| `main` | Latest completed phase |
| `phase-1-channel-routing` | Phase 1 (tag `phase-1-done`) |

Demo a phase: `git switch phase-1-channel-routing`. The repo is local only; no remote is configured.
