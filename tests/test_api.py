import pytest
from fastapi.testclient import TestClient
from url_shortener.app import create_app


@pytest.fixture
def client(tmp_path):
    return TestClient(create_app(str(tmp_path / "shortener.sqlite")))


def test_create_redirect_and_analytics(client):
    result = client.post("/api/v1/links", json={"url": "https://example.com/blog", "custom_alias": "myblog"})
    assert result.status_code == 201
    assert result.json()["code"] == "myblog"
    redirect = client.get("/myblog", follow_redirects=False)
    assert redirect.status_code == 302
    assert redirect.headers["location"] == "https://example.com/blog"
    stats = client.get("/api/v1/links/myblog/stats").json()
    assert stats["clicks"] == 1
    assert sum(item["clicks"] for item in stats["daily_clicks"]) == 1


def test_collision_returns_conflict(client):
    payload = {"url": "https://example.org", "custom_alias": "hello"}
    assert client.post("/api/v1/links", json=payload).status_code == 201
    assert client.post("/api/v1/links", json=payload).status_code == 409


def test_idempotency_and_conflicting_key(client):
    headers = {"Idempotency-Key": "request-123"}
    first = client.post("/api/v1/links", json={"url": "https://example.org"}, headers=headers)
    second = client.post("/api/v1/links", json={"url": "https://example.org"}, headers=headers)
    third = client.post("/api/v1/links", json={"url": "https://example.net"}, headers=headers)
    assert first.status_code == 201
    assert second.status_code == 200
    assert first.json()["code"] == second.json()["code"]
    assert third.status_code == 409


@pytest.mark.parametrize("url", ["javascript:alert(1)", "file:///etc/passwd", "http://localhost", "http://127.0.0.1", "https://10.0.0.3", "https://user:pass@example.com"])
def test_unsafe_urls_rejected(client, url):
    assert client.post("/api/v1/links", json={"url": url}).status_code == 422


def test_disable_link(client):
    assert client.post("/api/v1/links", json={"url": "https://example.com", "custom_alias": "disableme"}).status_code == 201
    assert client.delete("/api/v1/links/disableme").status_code == 204
    assert client.get("/disableme", follow_redirects=False).status_code == 404
    assert client.delete("/api/v1/links/disableme").status_code == 404


def test_expired_rejected_and_future_expire(client):
    assert client.post("/api/v1/links", json={"url": "https://example.org", "expires_at": "2020-01-01T00:00:00+00:00"}).status_code == 422
    assert client.post("/api/v1/links", json={"url": "https://example.org", "expires_at": "2099-01-01T00:00:00+00:00"}).status_code == 201


def test_health(client):
    assert client.get("/health").json() == {"status": "ok"}
