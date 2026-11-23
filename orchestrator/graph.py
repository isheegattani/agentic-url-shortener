"""Policy-owned dependency DAG. Model output cannot add/remove approval nodes."""
from dataclasses import asdict, dataclass


@dataclass(frozen=True)
class Node:
    name: str
    deps: tuple[str, ...]
    kind: str = "agent"
    retries: int = 0


def create_graph(scenario: str, model_assisted: bool = False) -> dict[str, dict]:
    if scenario not in {"greenfield", "brownfield", "ambiguous"}:
        raise ValueError("Scenario must be greenfield, brownfield, or ambiguous")
    nodes = [Node("intake", ())]
    if scenario == "ambiguous":
        nodes.append(Node("requirements_approval", ("intake",), "approval"))
        planning_dep = "requirements_approval"
    else:
        planning_dep = "intake"
    nodes += [Node("plan", (planning_dep,)), Node("design", ("plan",)),
              Node("proposal", ("design",))]
    if scenario == "brownfield" or model_assisted:
        nodes.append(Node("change_approval", ("proposal",), "approval"))
        implement_dep = "change_approval"
    else:
        implement_dep = "proposal"
    nodes += [Node("implement", (implement_dep,)),
              Node("security", ("implement",)),
              Node("test", ("security",), retries=1),
              Node("docs", ("implement",)),
              Node("review", ("test", "docs", "security")),
              Node("release_approval", ("review",), "approval"),
              Node("release", ("release_approval",))]
    graph = {node.name: {**asdict(node), "deps": list(node.deps)} for node in nodes}
    _validate_graph(graph)
    return graph


def _validate_graph(graph: dict):
    seen, visiting = set(), set()

    def dfs(node):
        if node in visiting:
            raise ValueError("Cyclic workflow")
        if node in seen:
            return
        visiting.add(node)
        for dependency in graph[node]["deps"]:
            if dependency not in graph:
                raise ValueError(f"Unknown dependency {dependency}")
            dfs(dependency)
        visiting.remove(node)
        seen.add(node)

    for node in graph:
        dfs(node)
