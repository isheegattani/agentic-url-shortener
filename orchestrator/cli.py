"""Example: python -m orchestrator.cli create --scenario greenfield --requirement 'Build a URL shortener'"""
import argparse
import json
import sys

from .engine import WorkflowEngine


def main(argv=None):
    parser = argparse.ArgumentParser(description="Governed agentic SDLC orchestrator")
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("create")
    create.add_argument("--scenario", choices=["greenfield", "brownfield", "ambiguous"], required=True)
    create.add_argument("--requirement", required=True)
    create.add_argument("--llm", action="store_true", help="Enable structured model planning and reviewed change proposals")
    advance = commands.add_parser("advance")
    advance.add_argument("run_id")
    advance.add_argument("--fault-once", choices=["test"], help="Demo a bounded retry")
    approve = commands.add_parser("approve")
    approve.add_argument("run_id")
    approve.add_argument("--gate", required=True)
    approve.add_argument("--actor", required=True)
    approve.add_argument("--decision", choices=["approved", "rejected"], default="approved")
    status = commands.add_parser("status")
    status.add_argument("run_id")
    events = commands.add_parser("events")
    events.add_argument("run_id")
    revise = commands.add_parser("revise")
    revise.add_argument("run_id")
    revise.add_argument("--requirement", required=True)
    replan = commands.add_parser("replan")
    replan.add_argument("run_id")
    replan.add_argument("--changed-stage", required=True)
    stop = commands.add_parser("stop")
    stop.add_argument("run_id")
    stop.add_argument("--reason", default="Operator safe-stop")
    commands.add_parser("metrics")
    args = parser.parse_args(argv)
    engine = WorkflowEngine()
    try:
        if args.command == "create":
            result = engine.create(args.scenario, args.requirement, args.llm)
        elif args.command == "advance":
            result = engine.advance(args.run_id, args.fault_once)
        elif args.command == "approve":
            result = engine.approve(args.run_id, args.gate, args.actor, args.decision)
        elif args.command == "status":
            result = engine.store.get(args.run_id)
        elif args.command == "events":
            result = {"audit_valid": engine.store.verify_audit(args.run_id),
                      "events": engine.store.events(args.run_id)}
        elif args.command == "revise":
            result = engine.revise(args.run_id, args.requirement)
        elif args.command == "replan":
            result = engine.replan(args.run_id, args.changed_stage)
        elif args.command == "stop":
            result = engine.stop(args.run_id, args.reason)
        elif args.command == "metrics":
            result = engine.store.metrics()
        print(json.dumps(result, indent=2))
        return 0
    except (ValueError, PermissionError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
