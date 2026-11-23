"""Ask a configured model for structured suggestions, not executable commands."""
from __future__ import annotations

import json
import os
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


class ModelError(RuntimeError):
    """Raised when the model request or response is invalid."""


def ask_model(stage: str, instructions: str, context: dict) -> dict:
    """Request a JSON response from the configured endpoint."""
    endpoint = os.getenv("MODEL_API_URL", "").strip()
    key = os.getenv("MODEL_API_KEY", "").strip()
    model = os.getenv("MODEL_NAME", "").strip()
    if not endpoint or not key or not model:
        raise ModelError("Model configuration incomplete: MODEL_API_URL, MODEL_API_KEY, MODEL_NAME required")
    if not endpoint.startswith("https://"):
        raise ModelError("Model endpoint must use HTTPS")
    payload = {
        "model": model,
        "temperature": 0,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": "Return one valid JSON object only. " + instructions},
            {"role": "user", "content": json.dumps({"stage": stage, "context": context}, sort_keys=True)},
        ],
    }
    request = Request(
        endpoint,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=20) as response:
            raw = response.read(150_001)
        if len(raw) > 150_000:
            raise ModelError("Model response exceeded size limit")
        output = json.loads(raw)
        content = output["choices"][0]["message"]["content"]
        result = json.loads(content)
    except (HTTPError, URLError, TimeoutError, KeyError, ValueError, TypeError, IndexError) as exc:
        # Do not log responses or credentials when a request fails.
        raise ModelError(f"Model {stage} request failed ({type(exc).__name__})") from None
    if not isinstance(result, dict):
        raise ModelError("Model must return a JSON object")
    return result


def string_list(value: object, *, limit: int = 12, max_length: int = 300) -> list[str]:
    if not isinstance(value, list) or len(value) > limit:
        raise ModelError("Model supplied an invalid list")
    if not all(isinstance(item, str) and 0 < len(item) <= max_length for item in value):
        raise ModelError("Model supplied invalid list items")
    return value
