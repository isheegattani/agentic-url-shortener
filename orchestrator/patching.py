"""Validate proposed edits and apply them only to staged files."""
from __future__ import annotations

import ast
import difflib
import hashlib
from pathlib import Path

from .model import ModelError, ask_model

SOURCE_FILES = {
    "url_shortener/app.py", "url_shortener/storage.py", "url_shortener/validation.py",
}
TEST_FILE = "tests/test_generated_change.py"
MAX_EDITS = 4
MAX_FILE_SIZE = 40_000
BANNED_IMPORTS = {"subprocess", "socket", "ctypes", "pickle", "importlib", "requests", "httpx", "shutil"}
BANNED_CALLS = {"eval", "exec", "compile", "__import__", "open", "input", "breakpoint"}


class ProposalRejected(ValueError):
    """The proposed change violates a project rule."""


def sha(data: str) -> str:
    return hashlib.sha256(data.encode("utf-8")).hexdigest()


def check_python(source: str) -> None:
    """Reject known unsafe code patterns before tests run."""
    if len(source.encode()) > MAX_FILE_SIZE:
        raise ProposalRejected("Generated file exceeds size limit")
    try:
        tree = ast.parse(source)
    except SyntaxError as exc:
        raise ProposalRejected(f"Invalid generated Python at line {exc.lineno}") from None
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            if any(alias.name.split(".")[0] in BANNED_IMPORTS for alias in node.names):
                raise ProposalRejected("Unapproved external process/network import")
        if isinstance(node, ast.ImportFrom) and node.module:
            if node.module.split(".")[0] in BANNED_IMPORTS:
                raise ProposalRejected("Unapproved external process/network import")
        if isinstance(node, ast.Call):
            if isinstance(node.func, ast.Name) and node.func.id in BANNED_CALLS:
                raise ProposalRejected("Unsafe dynamic or filesystem call")
            if isinstance(node.func, ast.Attribute) and node.func.attr in {"system", "popen", "spawn", "remove", "unlink", "rmtree", "write_text", "write_bytes"}:
                raise ProposalRejected("Unsafe process/filesystem operation")


def inspect_proposal(root: Path, proposal: dict) -> tuple[list[dict], str]:
    """Check edit locations and produce a diff for review."""
    changes = proposal.get("edits")
    if not isinstance(changes, list) or len(changes) > MAX_EDITS:
        raise ProposalRejected("Proposal must contain at most four edits")
    if len(changes) != len({c.get("path") for c in changes if isinstance(c, dict)}):
        raise ProposalRejected("Duplicate edit targets are not allowed")
    approved = []
    diffs = []
    for edit in changes:
        if not isinstance(edit, dict):
            raise ProposalRejected("Invalid edit entry")
        path, find, replace = edit.get("path"), edit.get("find"), edit.get("replace")
        if path not in SOURCE_FILES or not isinstance(find, str) or not isinstance(replace, str):
            raise ProposalRejected("Edit target or replacement is not allowed")
        if not find or len(find) > 12_000 or len(replace) > 20_000:
            raise ProposalRejected("Edit exceeds replacement boundaries")
        original = (root / path).read_text()
        if original.count(find) != 1:
            raise ProposalRejected("Expected source anchor was missing or ambiguous")
        changed = original.replace(find, replace, 1)
        check_python(changed)
        approved.append({"path": path, "find": find, "replace": replace, "original_sha256": sha(original)})
        diffs.extend(difflib.unified_diff(original.splitlines(keepends=True), changed.splitlines(keepends=True),
                                           fromfile="a/" + path, tofile="b/" + path))
    test_source = proposal.get("test_source", "")
    if not isinstance(test_source, str):
        raise ProposalRejected("Invalid proposed test")
    if test_source:
        check_python(test_source)
        diffs.extend(difflib.unified_diff([], test_source.splitlines(keepends=True),
                                           fromfile="/dev/null", tofile="b/" + TEST_FILE))
    if not approved and not test_source:
        raise ProposalRejected("Model did not propose a verifiable change")
    if len("".join(diffs)) > 65_000:
        raise ProposalRejected("Change diff exceeds review limit")
    return approved, "".join(diffs)


def make_proposal(root: Path, scenario: str, requirement: str, use_llm: bool) -> dict:
    """Build the proposal and validate it before asking for approval."""
    if not use_llm:
        if scenario != "brownfield":
            return {"summary": "Verify existing service in isolated candidate", "edits": [], "test_source": "", "mode": "verified_template", "diff": ""}
        proposal = {
            "summary": "Reserve support alias and add a regression test",
            "edits": [{"path": "url_shortener/validation.py", "find": '"admin"}',
                       "replace": '"admin", "support"}'}],
            "test_source": "import pytest\nfrom url_shortener.validation import validate_alias\n\n"
                           "def test_support_alias_is_reserved():\n"
                           "    with pytest.raises(ValueError, match='reserved'):\n"
                           "        validate_alias('support')\n",
        }
        edits, diff = inspect_proposal(root, proposal)
        return {"summary": proposal["summary"], "edits": edits, "test_source": proposal["test_source"],
                "mode": "bounded_patch", "diff": diff}
    context = {
        "scenario": scenario, "requirement": requirement,
        "allowed_source_files": {path: (root / path).read_text()[:12_000] for path in sorted(SOURCE_FILES)},
        "test_file": TEST_FILE,
    }
    response = ask_model("proposal", (
        "You are implementing a narrowly scoped software requirement for a URL shortener. "
        "Propose an actual, testable code change, not an explanation. Return JSON with keys: "
        "summary (string), edits (list of up to 4 {path,find,replace} exact-substring edits), "
        "test_source (complete Python pytest test module). Edit only allowed source paths, "
        "and write new tests only in the designated test_file. "
        "Preserve APIs, do not change auth, logging, network access, or approval logic. "
        "Do not use shell, dynamic execution, environment variables, external network calls or filesystem writes. "
        "Your new tests must prove the requested feature."
    ), context)
    summary = response.get("summary")
    if not isinstance(summary, str) or not 0 < len(summary) <= 500:
        raise ModelError("Model proposal missing concise summary")
    edits, diff = inspect_proposal(root, response)
    if not edits or not response.get("test_source"):
        raise ProposalRejected("Model proposals must include source changes and a regression test")
    return {"summary": summary, "edits": edits, "test_source": response["test_source"],
            "mode": "model_proposed", "diff": diff}


def apply_proposal(candidate: Path, proposal: dict) -> list[str]:
    """Apply approved edits after checking the source has not changed."""
    changes = []
    for edit in proposal["edits"]:
        file = candidate / edit["path"]
        if not file.is_file():
            raise ProposalRejected("Staged source file missing")
        current = file.read_text()
        if sha(current) != edit["original_sha256"] or current.count(edit["find"]) != 1:
            raise ProposalRejected("Source drift detected; re-plan before applying")
        modified = current.replace(edit["find"], edit["replace"], 1)
        check_python(modified)
        file.write_text(modified)
        changes.append(edit["path"])
    if proposal.get("test_source"):
        test = candidate / TEST_FILE
        if test.exists():
            raise ProposalRejected("Generated test already exists")
        check_python(proposal["test_source"])
        test.write_text(proposal["test_source"])
        changes.append(TEST_FILE)
    return changes
