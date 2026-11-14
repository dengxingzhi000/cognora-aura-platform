#!/usr/bin/env python3
"""Fail when two services or containers would bind the same port.

Three collisions shipped in this repo at once (message/approval on 8209,
schema-registry/platform-system on 8081, Grafana/cognora-aura-web on 3000).
Each one only surfaces when the *second* process starts, which on a dev box
means "works for whoever started things in the other order" - the worst kind of
defect to leave to a human checklist.

This script is deliberately static: CI never boots the stack, so the only
enforceable guard is a config check.

Usage:
    python3 scripts/ci/check_port_conflicts.py [--repo-root .]

Exit codes: 0 = no conflicts, 1 = conflicts found, 2 = could not read inputs.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# server.port declarations in a service's Spring config.
SERVER_PORT_RE = re.compile(r"^\s{2}port:\s*\$\{SERVER_PORT:(\d+)\}", re.M)
SERVER_PORT_PLAIN_RE = re.compile(r"^\s{2}port:\s*(\d+)\s*$", re.M)

# docker-compose host:container mappings, e.g. - "8081:8081"
COMPOSE_PORT_RE = re.compile(r'^\s+-\s+"(\d+):(\d+)"', re.M)

# Directories that hold another checkout or generated output.
SKIP_DIRS = {"target", "node_modules", ".git", "cognora-aura-web", "enterprise-agent",
             ".vmcheck", ".vmdeploy", ".m2repo", ".trae"}

# Infra containers are allowed to share a host port with nothing, but the ports they
# are *documented* to expose are asserted by OPERATIONS_MANUAL; no exemptions needed here.


def iter_application_yamls(root: Path):
    for path in root.rglob("application*.y*ml"):
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        yield path


def service_ports(root: Path) -> dict[int, list[str]]:
    """Map declared server port -> the config files that declare it."""
    ports: dict[int, list[str]] = {}
    for path in iter_application_yamls(root):
        text = path.read_text(encoding="utf-8", errors="replace")
        found = [int(m.group(1)) for m in SERVER_PORT_RE.finditer(text)]
        found += [int(m.group(1)) for m in SERVER_PORT_PLAIN_RE.finditer(text)]
        for port in found:
            ports.setdefault(port, []).append(str(path.relative_to(root)))
    return ports


def compose_ports(root: Path) -> dict[int, list[str]]:
    """Map host port -> the compose services that publish it."""
    compose = root / "docker-compose.yml"
    if not compose.exists():
        return {}
    text = compose.read_text(encoding="utf-8", errors="replace")
    services: dict[int, list[str]] = {}
    # Track the current top-level service so a conflict can name both sides.
    current = "<unknown>"
    for line in text.splitlines():
        match = re.match(r"^  ([a-z0-9-]+):\s*$", line)
        if match:
            current = match.group(1)
            continue
        port_match = COMPOSE_PORT_RE.match(line)
        if port_match:
            services.setdefault(int(port_match.group(1)), []).append(current)
    return services


def _service_name_of_config(cfg: str) -> str:
    """Derive the Spring service name from a config path.

    ``platform/system/service/src/main/resources/application.yaml`` -> ``platform-system``
    ``scm/order/service/src/main/resources/application.yml``        -> ``scm-order``
    ``platform/gateway/src/main/resources/application.yaml``        -> ``platform-gateway``
    ``ecom/order-center/service/src/main/resources/application.yml``-> ``ecom-order-center``
    ``config/seata/application.yml``                                -> ``config-seata``
    """
    path = Path(cfg)
    parts = list(path.parts)
    # Drop the file and the trailing resources/main/src/service segments.
    while parts and parts[-1] not in ("resources", "service", "main", "src"):
        parts.pop()
    if parts and parts[-1] in ("resources",):
        parts.pop()
    while parts and parts[-1] in ("main", "src", "service"):
        parts.pop()
    # For a flat module (gateway/auth) the last part is the module name itself.
    if not parts:
        return ""
    domain, _, module = None, None, None
    if len(parts) >= 2:
        domain, module = parts[-2], parts[-1]
    else:
        module = parts[-1]
    return f"{domain}-{module}" if domain else module


def _same_service(cfg: str, compose_owners: list[str], root: Path) -> bool:
    """Whether the config file and the compose service(s) name the same service."""
    name = _service_name_of_config(cfg)
    # Infra configs live under config/<component>/ and the compose service is
    # usually named <component> or <component>-<role> (config/seata -> seata-server).
    parts = Path(cfg).parts
    if len(parts) >= 2 and parts[0] == "config":
        component = parts[1]
        for owner in compose_owners:
            if owner and owner != "<unknown>" and owner.split("-")[0] == component:
                return True
    for owner in compose_owners:
        if not owner or owner == "<unknown>":
            continue
        if owner == name:
            return True
        # compose names sometimes drop the domain prefix (gateway vs platform-gateway).
        if name.endswith("-" + owner) or owner.endswith("-" + name):
            return True
        if name.split("-")[-1] == owner.split("-")[-1]:
            return True
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", default=".", help="repository root (default: cwd)")
    args = parser.parse_args()

    root = Path(args.repo_root).resolve()
    if not root.is_dir():
        print(f"error: repo root not found: {root}", file=sys.stderr)
        return 2

    svc = service_ports(root)
    comp = compose_ports(root)

    problems: list[str] = []

    # 1. Two Spring services on the same server.port.
    for port, owners in sorted(svc.items()):
        if len(owners) > 1:
            problems.append(
                f"server.port {port} is declared by {len(owners)} services: "
                + ", ".join(sorted(owners))
            )

    # 2. Two compose services publishing the same host port.
    for port, owners in sorted(comp.items()):
        if len(owners) > 1:
            problems.append(
                f"docker-compose host port {port} is published by {len(owners)} services: "
                + ", ".join(sorted(owners))
            )

    # 3. A compose service publishing a host port that a *different* Spring service uses.
    #    The same service appearing in both places is legitimate: core services are defined
    #    in docker-compose.yml AND keep a server.port for a plain `mvn spring-boot:run`.
    for port, owners in sorted(comp.items()):
        if port not in svc:
            continue
        for cfg in svc[port]:
            if _same_service(cfg, owners, root):
                continue
            problems.append(
                f"host port {port} is published by compose service "
                f"{', '.join(sorted(set(owners)))} but is also the server.port of "
                f"{cfg}"
            )

    if problems:
        print("Port conflicts found:\n")
        for problem in problems:
            print(f"  - {problem}")
        print("\nEach of these only fails when the second process starts.")
        print("Change one side's port (and README.md's Ports table) instead of relying on an override.")
        return 1

    print(f"No port conflicts: {len(svc)} declared service port(s), "
          f"{len(comp)} published compose port(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
