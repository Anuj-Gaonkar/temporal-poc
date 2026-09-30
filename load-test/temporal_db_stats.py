#!/usr/bin/env python3
"""Row counts and sizes of every table in Temporal's Postgres, before and after a load test.

Temporal keeps its own Postgres (container cern-orch-temporal-postgres, not published to the host),
with two databases: `temporal` (workflow state, history, timers) and `temporal_visibility` (what the
Temporal UI lists). This tool only READS: it runs `SELECT count(*)` per table through `docker exec`.
Python 3 standard library only.

  python3 load-test/temporal_db_stats.py snapshot before        # save load-test/snapshots/before.json
  ... run the load test ...
  python3 load-test/temporal_db_stats.py snapshot after
  python3 load-test/temporal_db_stats.py diff before after      # what the run created (rows, bytes)
  python3 load-test/temporal_db_stats.py watch --out load-test/snapshots/run.csv --interval 5
                                                                 # growth over time, Ctrl+C to stop

`snapshot` and `diff` feed `report.py --db-before before --db-after after`; `watch` output feeds
`--db-samples`. Counts are exact (count(*)), so a snapshot on a big database takes a moment.
"""
import argparse
import csv
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone

IST = timezone(timedelta(hours=5, minutes=30))
QUERY = """
SELECT table_name,
       (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text::bigint,
       pg_total_relation_size(format('%I.%I', table_schema, table_name)::regclass)
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY 1
"""


def psql(args, db, sql):
    cmd = ["docker", "exec", "-i", args.container, "psql", "-U", args.user, "-d", db, "-At", "-F", "|", "-v", "ON_ERROR_STOP=1"]
    r = subprocess.run(cmd, input=sql, text=True, capture_output=True)
    if r.returncode != 0:
        sys.exit(f"psql failed on database {db}:\n{r.stderr or r.stdout}")
    return r.stdout


def take(args):
    """{database: {table: [rows, bytes]}}"""
    out = {}
    for db in args.dbs.split(","):
        tables = {}
        for line in psql(args, db, QUERY).splitlines():
            name, rows, size = line.split("|")
            tables[name] = [int(rows), int(size)]
        out[db] = tables
    return out


def path_for(args, name):
    return name if name.endswith(".json") or os.sep in name else os.path.join(args.dir, name + ".json")


def load(args, name):
    with open(path_for(args, name), encoding="utf-8") as fh:
        return json.load(fh)


def fmt_bytes(n):
    for unit in ("B", "KB", "MB", "GB"):
        if abs(n) < 1024 or unit == "GB":
            return f"{n:,.0f} {unit}" if unit == "B" else f"{n:,.1f} {unit}"
        n /= 1024


def cmd_snapshot(args):
    snap = {"label": args.label, "taken_at": datetime.now(IST).isoformat(timespec="seconds"), "databases": take(args)}
    os.makedirs(args.dir, exist_ok=True)
    path = path_for(args, args.label)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(snap, fh, indent=1)
    print(f"saved {path} ({snap['taken_at']} IST)")
    for db, tables in snap["databases"].items():
        total_rows = sum(r for r, _ in tables.values())
        total_bytes = sum(b for _, b in tables.values())
        print(f"\n{db}: {total_rows:,} rows, {fmt_bytes(total_bytes)}")
        for name, (rows, size) in sorted(tables.items(), key=lambda kv: -kv[1][0]):
            if rows:
                print(f"  {name:<28}{rows:>12,}{fmt_bytes(size):>14}")


def diff_rows(before, after):
    """[(db, table, rows_before, rows_after, delta_rows, delta_bytes)] for every table in either snapshot."""
    rows = []
    for db in sorted(set(before["databases"]) | set(after["databases"])):
        b, a = before["databases"].get(db, {}), after["databases"].get(db, {})
        for table in sorted(set(b) | set(a)):
            rb, bb = b.get(table, [0, 0])
            ra, ba = a.get(table, [0, 0])
            rows.append((db, table, rb, ra, ra - rb, ba - bb))
    return rows


def cmd_diff(args):
    before, after = load(args, args.before), load(args, args.after)
    rows = diff_rows(before, after)
    print(f"{before['label']} ({before['taken_at']})  ->  {after['label']} ({after['taken_at']})\n")
    print(f"{'database':<22}{'table':<28}{'before':>11}{'after':>11}{'+rows':>11}{'+size':>13}")
    for db, table, rb, ra, dr, dbytes in sorted(rows, key=lambda r: -r[4]):
        if dr or args.all:
            print(f"{db:<22}{table:<28}{rb:>11,}{ra:>11,}{dr:>+11,}{fmt_bytes(dbytes):>13}")
    for db in sorted({r[0] for r in rows}):
        print(f"{db:<22}{'TOTAL':<28}{sum(r[2] for r in rows if r[0] == db):>11,}{sum(r[3] for r in rows if r[0] == db):>11,}"
              f"{sum(r[4] for r in rows if r[0] == db):>+11,}{fmt_bytes(sum(r[5] for r in rows if r[0] == db)):>13}")


def cmd_watch(args):
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    new = not os.path.exists(args.out)
    with open(args.out, "a", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        if new:
            w.writerow(["time", "database", "table", "rows", "bytes"])
        print(f"sampling every {args.interval}s into {args.out} (Ctrl+C to stop)")
        try:
            while True:
                now = datetime.now(IST).isoformat(timespec="seconds")
                for db, tables in take(args).items():
                    for table, (rows, size) in tables.items():
                        if rows:
                            w.writerow([now, db, table, rows, size])
                fh.flush()
                time.sleep(args.interval)
        except KeyboardInterrupt:
            print("stopped")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--container", default="cern-orch-temporal-postgres")
    ap.add_argument("--user", default="temporal")
    ap.add_argument("--dbs", default="temporal,temporal_visibility")
    ap.add_argument("--dir", default="load-test/snapshots")
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("snapshot", help="save row counts and sizes")
    s.add_argument("label")
    d = sub.add_parser("diff", help="compare two snapshots")
    d.add_argument("before")
    d.add_argument("after")
    d.add_argument("--all", action="store_true", help="include tables that did not change")
    w = sub.add_parser("watch", help="sample counts over time into a CSV")
    w.add_argument("--out", default="load-test/snapshots/watch.csv")
    w.add_argument("--interval", type=float, default=5)
    args = ap.parse_args()
    {"snapshot": cmd_snapshot, "diff": cmd_diff, "watch": cmd_watch}[args.cmd](args)


if __name__ == "__main__":
    main()
