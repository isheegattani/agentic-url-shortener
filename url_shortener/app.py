"""HTTP API: create, redirect, analytics and disable, with SQLite durability."""
from __future__ import annotations

import logging
import os
import secrets
from datetime import datetime, timezone
from typing import Annotated

from fastapi import FastAPI, Header, HTTPException, Request, Response
from fastapi.responses import RedirectResponse
from pydantic import BaseModel, Field, field_validator

from .storage import LinkStore
from .validation import validate_destination, validate_alias

logger = logging.getLogger(__name__)


class CreateLink(BaseModel):
    url: str = Field(min_length=8, max_length=2048)
    custom_alias: str | None = None
    expires_at: datetime | None = None

    @field_validator("url")
    @classmethod
    def url_is_safe(cls, value: str) -> str:
        return validate_destination(value)

    @field_validator("custom_alias")
    @classmethod
    def alias_is_valid(cls, value: str | None) -> str | None:
        return validate_alias(value) if value is not None else None


class LinkResponse(BaseModel):
    code: str
    short_url: str
    original_url: str
    created_at: str
    expires_at: str | None


def create_app(db_path: str | None = None) -> FastAPI:
    store = LinkStore(db_path or os.getenv("SHORTENER_DB", "shortener.sqlite"))
    app = FastAPI(title="Governed URL Shortener", version="0.1.0")
    app.state.store = store

    def serialize(record: dict) -> LinkResponse:
        base = os.getenv("SHORTENER_BASE_URL", "http://localhost:8000").rstrip("/")
        return LinkResponse(code=record["code"], short_url=f"{base}/{record['code']}",
                            original_url=record["destination"], created_at=record["created_at"],
                            expires_at=record["expires_at"])

    @app.get("/health", tags=["health"])
    def health():
        with store.connection() as db:
            db.execute("SELECT 1").fetchone()
        return {"status": "ok"}

    @app.post("/api/v1/links", response_model=LinkResponse, status_code=201, tags=["links"])
    def create_link(request: CreateLink, response: Response,
                    idempotency_key: Annotated[str | None, Header(max_length=128)] = None):
        expiry = request.expires_at
        if expiry is not None:
            if expiry.tzinfo is None:
                raise HTTPException(422, detail="expires_at must contain a timezone")
            if expiry <= datetime.now(timezone.utc):
                raise HTTPException(422, detail="expires_at must be in the future")
        expiry_text = expiry.astimezone(timezone.utc).isoformat() if expiry else None
        for _ in range(5):
            code = request.custom_alias or secrets.token_urlsafe(6).replace("-", "x").replace("_", "y")[:8]
            result, record = store.create(code, request.url, expiry_text, idempotency_key)
            if result == "created":
                return serialize(record)
            if result == "replayed":
                response.status_code = 200
                return serialize(record)
            if result == "idempotency_conflict":
                raise HTTPException(409, detail="Idempotency key used with different payload")
            if request.custom_alias:
                raise HTTPException(409, detail="Custom alias already exists")
        raise HTTPException(503, detail="Could not allocate short code; retry")

    @app.get("/api/v1/links/{code}/stats", tags=["analytics"])
    def stats(code: str):
        link = store.get(code)
        if not link:
            raise HTTPException(404, detail="Short link not found")
        return {"code": link["code"], "clicks": link["clicks"],
                "disabled": bool(link["disabled"]), "daily_clicks": store.daily_clicks(code)}

    @app.delete("/api/v1/links/{code}", status_code=204, tags=["links"])
    def delete_link(code: str):
        if not store.disable(code):
            raise HTTPException(404, detail="Active short link not found")
        return Response(status_code=204)

    @app.get("/{code}", include_in_schema=False)
    def redirect(code: str):
        record = store.get(code)
        if not record or record["disabled"]:
            raise HTTPException(404, detail="Short link not found")
        if record["expires_at"] and datetime.fromisoformat(record["expires_at"]) <= datetime.now(timezone.utc):
            raise HTTPException(410, detail="Short link expired")
        try:
            store.record_click(code)
        except Exception:
            logger.exception("Best-effort click recording failed for %s", code)
        return RedirectResponse(record["destination"], status_code=302)

    return app


app = create_app()
