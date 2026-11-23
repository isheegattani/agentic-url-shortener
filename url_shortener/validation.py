"""Input validation and abuse-prevention rules.

No server-side URL fetch is performed. Redirect targets still warrant abuse monitoring
and denylisting before a public production rollout.
"""
from ipaddress import ip_address
from urllib.parse import urlsplit
import re

RESERVED_CODES = {"api", "docs", "redoc", "openapi.json", "health", "metrics", "admin"}
ALIAS_PATTERN = re.compile(r"^[a-zA-Z0-9_-]{4,32}$")


def validate_destination(value: str) -> str:
    if len(value) > 2048:
        raise ValueError("URL exceeds 2048 characters")
    try:
        url = urlsplit(value)
        hostname = url.hostname
        port = url.port  # Force invalid ports to raise ValueError.
    except ValueError as exc:
        raise ValueError("Malformed URL") from exc
    if url.scheme not in ("http", "https") or not hostname:
        raise ValueError("Only absolute http(s) URLs are allowed")
    if url.username is not None or url.password is not None:
        raise ValueError("URLs with embedded credentials are not allowed")
    if hostname.lower() in {"localhost"} or hostname.lower().endswith((".local", ".internal")):
        raise ValueError("Local/internal hostnames are not allowed")
    try:
        address = ip_address(hostname)
        if not address.is_global:
            raise ValueError("Private or non-global IP destinations are not allowed")
    except ValueError as exc:
        if "not allowed" in str(exc):
            raise
    if port is not None and port not in (80, 443, 8080, 8443):
        raise ValueError("Nonstandard ports are not allowed")
    return value


def validate_alias(value: str) -> str:
    if not ALIAS_PATTERN.fullmatch(value):
        raise ValueError("Alias must be 4–32 characters: letters, numbers, _ or -")
    if value.lower() in RESERVED_CODES:
        raise ValueError("Alias is reserved")
    return value
