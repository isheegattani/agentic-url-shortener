"""SQLite persistence with atomic creation and safe, limited analytics."""
from __future__ import annotations

import sqlite3
from datetime import datetime, timezone
from pathlib import Path


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


class LinkStore:
    def __init__(self, filename: str):
        self.filename = filename
        Path(filename).parent.mkdir(parents=True, exist_ok=True)
        with self.connection() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS links (
                    code TEXT PRIMARY KEY COLLATE NOCASE,
                    destination TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    expires_at TEXT,
                    disabled INTEGER NOT NULL DEFAULT 0,
                    clicks INTEGER NOT NULL DEFAULT 0,
                    idempotency_key TEXT UNIQUE
                );
                CREATE TABLE IF NOT EXISTS clicks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    code TEXT NOT NULL,
                    clicked_at TEXT NOT NULL,
                    FOREIGN KEY(code) REFERENCES links(code)
                );
                CREATE INDEX IF NOT EXISTS clicks_code_time ON clicks(code, clicked_at);
            """)

    def connection(self):
        db = sqlite3.connect(self.filename, timeout=10)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        return db

    def create(self, code: str, destination: str, expires_at: str | None, idem: str | None):
        with self.connection() as db:
            if idem:
                existing = db.execute("SELECT * FROM links WHERE idempotency_key=?", (idem,)).fetchone()
                if existing:
                    if existing["destination"] != destination or existing["expires_at"] != expires_at:
                        return "idempotency_conflict", dict(existing)
                    return "replayed", dict(existing)
            try:
                db.execute("INSERT INTO links(code,destination,created_at,expires_at,idempotency_key) VALUES (?,?,?,?,?)",
                           (code, destination, now(), expires_at, idem))
            except sqlite3.IntegrityError:
                if idem:
                    existing = db.execute("SELECT * FROM links WHERE idempotency_key=?", (idem,)).fetchone()
                    if existing:
                        if existing["destination"] == destination and existing["expires_at"] == expires_at:
                            return "replayed", dict(existing)
                        return "idempotency_conflict", dict(existing)
                return "code_conflict", None
            return "created", dict(db.execute("SELECT * FROM links WHERE code=?", (code,)).fetchone())

    def get(self, code: str):
        with self.connection() as db:
            record = db.execute("SELECT * FROM links WHERE code=?", (code,)).fetchone()
            return dict(record) if record else None

    def record_click(self, code: str):
        # Analytics is best effort. Callers keep redirecting if this fails.
        with self.connection() as db:
            db.execute("UPDATE links SET clicks=clicks+1 WHERE code=?", (code,))
            db.execute("INSERT INTO clicks(code, clicked_at) VALUES (?, ?)", (code, now()))

    def disable(self, code: str) -> bool:
        with self.connection() as db:
            result = db.execute("UPDATE links SET disabled=1 WHERE code=? AND disabled=0", (code,))
            return result.rowcount > 0

    def daily_clicks(self, code: str) -> list[dict]:
        with self.connection() as db:
            return [dict(row) for row in db.execute(
                "SELECT substr(clicked_at,1,10) AS day, count(*) AS clicks FROM clicks "
                "WHERE code=? GROUP BY day ORDER BY day DESC LIMIT 30", (code,)).fetchall()]
