# YAML guide

A definition file in `workflow-definitions/*.yaml` is the whole script of a workflow. One generic workflow class interprets it; the customer data
comes from the database, never from the YAML. Definitions are read **once at startup** (since phase 1). To change one: edit the file, restart the app.

## How customer data reaches the workflow

`customers table (orch.customers)` -> `CustomerRepository.findById` at start time -> `WorkflowInput.customer` (snapshot) -> the workflow.
The parsed definition is also passed as workflow input, so a running workflow keeps the definition it started with. The workflow never reads the database.

## Schema

Top level:

| Field | Required | Type | Meaning |
|---|---|---|---|
| `name` | yes | string | Unique across files. The caller names it in `POST /workflows/start`; it prefixes the workflow id. |
| `description` | no | string | Shown by `GET /workflow-definitions`. |
| `start` | yes | step id | First step. |
| `steps` | yes | non-empty list | The steps. |

Every step: `id` (required, unique) and `type` (required: `switch`, `activity`, `delay`, `end`). Unknown fields anywhere are rejected.

| Type | Fields | Meaning |
|---|---|---|
| `switch` | `on` (required), `cases` (value to step id), `default` (optional step id) | Picks the next step from a customer field. Exact, case-sensitive string match. No match and no `default`: the workflow fails with `No case for customer.initialChannel=XYZ in step 'route'`. Needs `cases` or `default`. |
| `activity` | `activity` (required), `params` (optional string map), `next` (required) | Runs a registered activity, then goes to `next`. The activity receives the customer fields and `params`. |
| `delay` | `duration` (required), `next` (required) | Sleeps (a durable Temporal timer), then goes to `next`. |
| `end` | none | Finishes the workflow normally (status COMPLETED). Never Temporal's terminate. |

### `on` paths (since phase 1)

`customer.customerId`, `customer.name`, `customer.phone`, `customer.initialChannel`. Nothing else: no expressions, no operators.

### Activities (since phase 1)

| Name | Params used | What it does |
|---|---|---|
| `executeVoiceCall` | `script` | Logs the call. |
| `sendMessage` | `channel`, `text` | Logs the message. |

Both return nothing and call no external service.

### Duration format (since phase 1)

A whole number plus one lowercase unit: `s`, `m`, `h`, `d` (`90s`, `30m`, `2h`, `3d`). `30`, `1w`, `30M`, `30 m`, `PT30M` and empty are rejected.

## Validation

Checked at startup; a broken file stops the app with `Invalid workflow definition <file>: <problems separated by ;>`. Messages:

| Problem | Message contains |
|---|---|
| missing name / start / steps | `'name' is required`, `'start' is required`, `'steps' is required and must not be empty` |
| step without id or type | `every step needs an 'id'`, `step 'x': 'type' is required` |
| duplicate step id | `duplicate step id 'x'` |
| unknown step type | `step 'x': unknown step type 'sleep' (use switch, activity, delay or end)` |
| unknown activity | `step 'x': unknown activity 'fax' (known: [...])` |
| bad duration | `step 'x': bad duration: ...` |
| `start`, `next`, case or default points nowhere | `start step 'x' does not exist`, `step 'a': next points to unknown step 'b'`, `case 'VOICE' points to unknown step ...` |
| non-end step without a way forward | `step 'x': 'next' is required ...`, `switch needs 'cases' or 'default'` |
| unsupported `on` path | `unsupported 'on' path 'x' (use one of [...])` |
| step not reachable from `start` | `step 'x' is not reachable from start 'y'` (an error, not a warning) |
| two files with the same `name` | `definition name 'x' is already defined by other.yaml` |
| unknown YAML field, bad YAML | the parser message, first line |

## Annotated example (the shipped `outreach-default.yaml`)

```yaml
name: OUTREACH_DEFAULT
description: Default outreach ...
start: route                     # first step

steps:
  - id: route
    type: switch
    on: customer.initialChannel  # value comes from orch.customers via the workflow input
    cases:
      VOICE: voice-call          # VOICE customers go to the call
      WHATSAPP: whatsapp-message # WHATSAPP customers go to the message
    # no default: any other value fails the workflow

  - id: voice-call
    type: activity
    activity: executeVoiceCall
    params: { script: "Hello, this is HDFC Bank. We are calling about your account." }
    next: wait-30-min            # both paths converge here

  - id: whatsapp-message
    type: activity
    activity: sendMessage
    params: { channel: WHATSAPP, text: "Hello from HDFC Bank. Reply here if you need any help." }
    next: wait-30-min

  - id: wait-30-min
    type: delay
    duration: 30m
    next: follow-up-message

  - id: follow-up-message
    type: activity
    activity: sendMessage
    params: { channel: WHATSAPP, text: "Just following up. Reply here if you need any help." }
    next: end

  - id: end
    type: end
```

(The real file writes `params` as a block map; the two forms are equivalent.)

## Common mistakes

- Indenting `cases` entries wrongly, or forgetting a step listed in `cases` (validation names the missing step).
- Writing `duration: 30` or `30 minutes` (use `30m`).
- Lower-case channel in `cases` (`voice:`): matching is case-sensitive, and the database stores `VOICE` / `WHATSAPP`.
- Editing the YAML and expecting a running app to notice: restart it (no hot reload in phase 1). Running workflows keep their old snapshot anyway.
- Leaving a step nobody points to (rejected as unreachable).

## Adding a new definition file

1. Copy `outreach-default.yaml` to `workflow-definitions/<something>.yaml` and give it a new unique `name`.
2. Restart the app; watch for `event=DEFINITIONS_LOADED` (or the error that names your file).
3. Start it with `POST /workflows/start` naming it in `definition`. Its workflow id is `<name>-<customerId>`.

## Planned (NOT available yet)

Phase 2 is expected to add the following. None of it works in phase 1: unknown fields such as `fromResult` are rejected as unknown YAML properties, and a `${...}` text in `params` would just be passed to the activity as literal text:

- activities returning results, `switch` on a step result (for example `on: voice-call.outcome`)
- `delay` with `fromResult: voice-call.remindInDays`
- `${voice-call.field}` references in `params`
- editing definitions at runtime and hot reload
