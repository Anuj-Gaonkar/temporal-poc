# cern-temporal-workflow-orch-service

A small proof of concept that starts a **Temporal workflow per customer, defined in YAML**, and runs it as a **15-day campaign**.

- An external system starts the workflow (`POST /workflows/start`); that day is **Day 0**.
- On **Day 1** (10:00 IST) the customer is contacted on the channel stored in Postgres (`WHATSAPP` or `VOICE`).
- The customer's **feedback** then decides the next contact: positive (thank-you SMS, done), remind me later (their own time and channel, or defaults),
  no response (try again on the DB channel), or answered without a conclusion (talk again on the channel they answered on). An unanswered reminder is repeated with the same time and channel.
- **Nothing is sent after Day 15.** A reminder that would land beyond it is dropped and the workflow ends.
- Feedback is sent to `POST /workflows/{workflowId}/feedback` (by hand with Postman, or by a load-test simulator). The service generates none.
- No external service is called: every activity only writes a log line.

Phase 2 is built (this branch). Docs are in [`docs/`](docs/README.md).

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

# After the Day 1 contact (see the demo section below for a quick demo), send the customer's feedback:
curl -s -X POST localhost:8096/workflows/OUTREACH_DEFAULT-C1001/feedback -H 'Content-Type: application/json' \
     -d '{"type":"REMIND_LATER","remindAfter":"2d","channel":"WHATSAPP"}'
curl -s -X POST localhost:8096/workflows/OUTREACH_DEFAULT-C1001/feedback -H 'Content-Type: application/json' -d '{"type":"POSITIVE"}'
```

Watch it in the Temporal UI: http://localhost:8089 (namespace `default`, task queue `CERN_WORKFLOW_ORCH`).
Logs go to the console and to `logs/cern-temporal-workflow-orch-service.log`.
The readable conversation (what we sent, what the customer replied, how the campaign ended, each with date and time) is also in `logs/conversation.log`:
`tail -f logs/conversation.log`.

Default customers: `C1001`, `C1003`, `C1005` are VOICE; `C1002`, `C1004`, `C1006` are WHATSAPP.

Stop: `Ctrl+C` the app, then `docker compose down` (add `-v` only if you want to wipe both databases, which also removes the customers).

## Ports (different from the root Chat-Bot-V3 stack on purpose, so both can run together)

| What | Here | Root stack |
|---|---|---|
| Temporal gRPC | 7234 | 7233 |
| Temporal UI | 8089 | 8088 |
| App Postgres | 5435 | (5434 is the shared chatbot Postgres) |
| Temporal Postgres (inspection only, localhost only) | 5436 | |
| App HTTP | 8096 | |

Container names are prefixed `cern-orch-`.

## Inspecting the databases with DBeaver

New connection, type PostgreSQL:

| | App database (customers) | Temporal database (workflow state, history, timers) | Temporal visibility (what the UI lists) |
|---|---|---|---|
| Host / port | `localhost` / `5435` | `localhost` / `5436` | `localhost` / `5436` |
| Database | `cern_workflow_orch` | `temporal` | `temporal_visibility` |
| User / password | `cern` / `cern` (or your `.env`) | `temporal` / `temporal` | `temporal` / `temporal` |

Port 5436 is bound to `127.0.0.1` only. Tick "Read-only connection" in DBeaver (Edit Connection, General, Security) so the Temporal data cannot be changed by accident.
Tables are in schema `public`. If you rerun `docker compose up -d` after pulling this change, only `temporal-postgres` is recreated; its data volume is kept.

## API

| Method and path | Purpose |
|---|---|
| `GET /workflow-definitions` | Loaded definitions (name, description, step ids) |
| `POST /workflows/start` | Body `{"customerId","definition"}`. 202, or 400 / 404 `DEFINITION_NOT_FOUND` / 404 `CUSTOMER_NOT_FOUND` / 409 |
| `POST /workflows/start-all` | Body `{"definition"}`. Starts it for **every** customer in the table, one after another; returns 202 with counts and one result per customer (`STARTED`, `ALREADY_RUNNING`, `FAILED`). 400 / 404 `DEFINITION_NOT_FOUND` |
| `POST /workflows/{workflowId}/feedback` | Customer feedback for a running workflow (signal). Body `{"type","remindAfter","channel"}`; `type` is `POSITIVE`, `REMIND_LATER`, `NO_RESPONSE` or `INCONCLUSIVE`; `remindAfter` (`2d`, `3h`) and `channel` (`WHATSAPP`, `VOICE`, `SMS`) are optional and only for `REMIND_LATER`. 202, 400 `BAD_REQUEST`, 404 `WORKFLOW_NOT_FOUND`. Send it **after** the contact it answers: feedback that arrives when no contact is waiting is dropped (logged). |
| `POST /workflow-definitions/reload` | Rescan the YAML folder now. Returns `{changed, removed, errors}`; a broken file keeps its previous version live. Running workflows keep their definition. |
| `GET /workflows/{workflowId}` | Status via Temporal (RUNNING, COMPLETED, ...) |
| `GET /customers/{customerId}` | Read-only view of the customer row |

Start everyone:

```bash
curl -s -X POST localhost:8096/workflows/start-all -H 'Content-Type: application/json' -d '{"definition":"OUTREACH_DEFAULT"}'
```

Workflow id is `<definition>-<customerId>`, e.g. `OUTREACH_DEFAULT-C1001`. Starting a customer whose workflow is running returns 409.

## Fast demo

A real campaign takes days. For a demo or a load test, copy `workflow-definitions/outreach-default.yaml` to another folder, set in the copy
`dayLength: 20s` under `campaign:` (one "day" lasts 20 seconds, so Day 1 starts 20 s after the trigger and the whole 15-day window lasts about 5 minutes)
and a short `timeout:` (for example `8s`) on the two `await-feedback` steps, and start the app with
`ORCH_DEFINITIONS_PATH=/path/to/folder ./mvnw spring-boot:run`. Do not commit a shortened file.
Edits to the YAML are picked up by the running app within 10 seconds (or call `POST /workflow-definitions/reload`); only workflows started afterwards use them.
Restart the app while a workflow waits to see Temporal keep the timers.

## Tests

```bash
./mvnw test        # plain unit tests (workflow tests use Temporal's time-skipping test server), no Docker needed
```

## Postman

Import `postman/cern-temporal-workflow-orch-service.postman_collection.json` and `postman/local.postman_environment.json`
(`baseUrl = http://localhost:8096`), select the `local` environment and run the requests top to bottom.
Customer ids are collection variables. The duplicate (409) request needs the VOICE workflow started just before it to still be running,
so run it right after "Start workflow, VOICE customer" (with the shipped 30 minute YAML there is plenty of time; with a shortened YAML, be quick).
To rerun the collection, wait for the earlier workflows to complete first, otherwise the start requests return 409.
The feedback requests at the end target the VOICE customer's workflow: run them one at a time, each after the contact it answers (with the shipped YAML that is Day 1 at 10:00 IST; for a quick run use the shortened demo copy). Run straight after the start request they return 202 but the workflow drops the feedback, because no contact has been made yet. The 400 and 404 requests work at any time.

## Load-test data

`load-test/load_customers.py` (Python 3 + Docker only) loads fake customers into `orch.customers` by piping SQL into the app-postgres container.
It is a manual operator tool like `db/*.sql`; the service never writes to the table.

```bash
python3 load-test/load_customers.py                  # 1000 customers: 400 VOICE, 600 WHATSAPP, ids L0001..L1000
python3 load-test/load_customers.py --dry-run | head # print the SQL only
python3 load-test/load_customers.py --clean          # delete the L* customers again
```

Temporal database growth: `python3 load-test/temporal_db_stats.py snapshot before`, run the load, `snapshot after`, then `diff before after` (rows and bytes per table). `watch --interval 5` samples growth over time into a CSV. Read-only; needs Docker only.

Options: `--count`, `--voice-pct`, `--prefix`, `--seed`, `--container`. It is idempotent. The channel is stored as `VOICE` (the column only allows `VOICE` / `WHATSAPP`).
After loading, `POST /workflows/start-all` starts a workflow for every customer, including the six defaults.

## Load-test report

`load-test/report.py` turns the timing logs into one self-contained HTML report (hover and zoom charts, percentile table, failures). Times are shown in IST.

```bash
pip install -r load-test/requirements.txt        # pandas + plotly, once (a venv is recommended)
python load-test/report.py logs --list-runs      # list the start-all runs in the logs
python load-test/report.py logs --run last       # report for the last start-all run
python load-test/report.py collected-logs/run-01 --out load-test/reports/run-01.html
python load-test/report.py logs --since 2026-09-30T15:36 --until 2026-09-30T15:50   # IST window
```

It accepts files or folders, including rolled `.gz` logs, and mixes old UTC (`Z`) and new IST (`+05:30`) lines correctly.
Reports go to `load-test/reports/` (git-ignored). `--theme dark` and `--cdn` (smaller file, needs internet) are available.
A run is the window from one `API_START_ALL_RECEIVED` to the next, so keep runs apart by waiting for the workflows to finish.

## Phase branches

| Branch | Contains |
|---|---|
| `main` | Latest completed phase |
| `phase-1-channel-routing` | Phase 1 (tag `phase-1-done`) |
| `phase-1-start-all` | Phase 1 plus the ad hoc `POST /workflows/start-all` endpoint, IST logs and the load-test tools (branched from `phase-1-done`; `main` is unchanged) |
| `phase-2-feedback-switch` | Everything above plus Phase 2: the 15-day campaign window, customer feedback and feedback-driven contacts, hot reload of definitions (branched from `phase-1-start-all`; `main` is unchanged until Phase 2 is accepted) |

Demo a phase: `git switch phase-1-channel-routing`. The repo is local only; no remote is configured.
