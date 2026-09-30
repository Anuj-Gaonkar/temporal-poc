# Documentation index

| Doc | What it is |
|---|---|
| [`yaml-guide.md`](yaml-guide.md) | The YAML definition format. A living doc; each feature notes the phase that added it. Start here if you want to write or change a definition. |
| [`phase-1/01-coding.md`](phase-1/01-coding.md) | Code walkthrough, request flow, determinism, how to add an activity, tests, and the **log events reference** the load-test scripts rely on. |
| [`phase-1/02-decisions.md`](phase-1/02-decisions.md) | Decision log (D1, D2, ...) including every deviation from the instruction guide. |
| [`phase-1/03-architecture.md`](phase-1/03-architecture.md) | Components, sequence diagrams, ports, failure and restart behaviour, where each latency is measured. |

Reading order for a newcomer: top-level `../README.md` (run it), then `phase-1/03-architecture.md`, `yaml-guide.md`, `phase-1/01-coding.md`; read the decision log when you wonder "why".
Each later phase adds a `phase-N/` folder; earlier phases stay readable as they were.
