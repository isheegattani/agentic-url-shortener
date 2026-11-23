"""Durable run store and SHA-256 hash-chained append-only audit events."""
import hashlib
import json
import os
import sqlite3
from datetime import datetime, timezone
from pathlib import Path


def utc_now():
    return datetime.now(timezone.utc).isoformat()


class RunStore:
    def __init__(self, path: str | None = None):
        self.path = Path(path or os.getenv("ORCHESTRATOR_DB", "runs/workflows.sqlite"))
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS runs (
                  id TEXT PRIMARY KEY, payload TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS events (
                  seq INTEGER PRIMARY KEY AUTOINCREMENT,
                  run_id TEXT NOT NULL,
                  at TEXT NOT NULL, kind TEXT NOT NULL,
                  detail TEXT NOT NULL, prev_hash TEXT NOT NULL,
                  event_hash TEXT NOT NULL);
                CREATE INDEX IF NOT EXISTS events_run ON events(run_id,seq);
            """)

    def connect(self):
        db = sqlite3.connect(str(self.path), timeout=10)
        db.row_factory = sqlite3.Row
        return db

    def put(self, run):
        with self.connect() as db:
            db.execute("INSERT INTO runs(id,payload) VALUES (?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload",
                       (run["id"], json.dumps(run, sort_keys=True)))

    def get(self, run_id: str):
        with self.connect() as db:
            row = db.execute("SELECT payload FROM runs WHERE id=?", (run_id,)).fetchone()
            if not row:
                raise ValueError(f"No workflow with id {run_id}")
            return json.loads(row["payload"])

    def event(self, run_id, kind, detail):
        at = utc_now()
        detail = json.dumps(detail, sort_keys=True, separators=(",", ":"))
        with self.connect() as db:
            last = db.execute("SELECT event_hash FROM events WHERE run_id=? ORDER BY seq DESC LIMIT 1", (run_id,)).fetchone()
            prev = last["event_hash"] if last else "GENESIS"
            payload = f"{run_id}|{at}|{kind}|{detail}|{prev}"
            digest = hashlib.sha256(payload.encode()).hexdigest()
            db.execute("INSERT INTO events(run_id,at,kind,detail,prev_hash,event_hash) VALUES (?,?,?,?,?,?)",
                       (run_id, at, kind, detail, prev, digest))

    def events(self, run_id):
        with self.connect() as db:
            rows = db.execute("SELECT * FROM events WHERE run_id=? ORDER BY seq", (run_id,)).fetchall()
            return [{**dict(row), "detail": json.loads(row["detail"])} for row in rows]

    def verify_audit(self, run_id):
        previous = "GENESIS"
        for event in self.events(run_id):
            detail = json.dumps(event["detail"], sort_keys=True, separators=(",", ":"))
            payload = f"{run_id}|{event['at']}|{event['kind']}|{detail}|{previous}"
            if event["prev_hash"] != previous or event["event_hash"] != hashlib.sha256(payload.encode()).hexdigest():
                return False
            previous = event["event_hash"]
        return True

    def metrics(self):
        with self.connect() as db:
            rows = [json.loads(r["payload"]) for r in db.execute("SELECT payload FROM runs")]
            kinds = [r["kind"] for r in db.execute("SELECT kind FROM events")]
        terminal = [r for r in rows if r["status"] in {"completed", "failed", "stopped"}]
        latencies = [r["elapsed_ms"] for r in terminal if r.get("elapsed_ms") is not None]
        return {"runs": len(rows), "terminal_runs": len(terminal),
                "success_rate": round(sum(r["status"] == "completed" for r in terminal) / len(terminal), 3) if terminal else None,
                "retry_frequency": kinds.count("retry"), "rollback_frequency": kinds.count("rollback"),
                "replan_frequency": kinds.count("selective_replan") + kinds.count("replan"),
                "mttr_ms": round(sum(r.get("recovery_ms", 0) for r in rows if r.get("recovery_ms")) / max(1, sum(bool(r.get("recovery_ms")) for r in rows)), 1) if any(r.get("recovery_ms") for r in rows) else None,
                "average_end_to_end_latency_ms": round(sum(latencies)/len(latencies), 1) if latencies else None}
