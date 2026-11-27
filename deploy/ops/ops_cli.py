#!/usr/bin/env python3
from __future__ import annotations

import argparse
import getpass
import json
import os
import re
import shlex
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_CATALOG = Path(__file__).with_name("service_catalog.json")
DEFAULT_AUDIT = Path.home() / ".cognora-ops" / "audit.jsonl"
ENV_NAME = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


class CatalogError(ValueError):
    pass


class RemoteError(RuntimeError):
    pass


def load_catalog(path: Path) -> dict:
    try:
        catalog = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise CatalogError(f"cannot read service catalog: {path}") from exc
    validate_catalog(catalog)
    return catalog


def validate_catalog(catalog: dict) -> None:
    if catalog.get("version") != 1:
        raise CatalogError("unsupported catalog version")
    services = catalog.get("services")
    if not isinstance(services, dict) or not services:
        raise CatalogError("services must be a non-empty object")
    for name, service in services.items():
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", name):
            raise CatalogError(f"invalid service name: {name}")
        if not service.get("container") or not service.get("image"):
            raise CatalogError(f"{name}: container and image are required")
        for dependency in service.get("dependsOn", []):
            if dependency not in services:
                raise CatalogError(f"{name}: unknown dependency {dependency}")
        health = service.get("health", {})
        if health.get("type") not in {"docker-health", "http", "command"}:
            raise CatalogError(f"{name}: unsupported health type")
        if health["type"] == "http" and not health.get("path"):
            raise CatalogError(f"{name}: HTTP health path is required")
        deploy = service.get("deploy", {})
        for env_name in deploy.get("secretEnv", []):
            if not ENV_NAME.fullmatch(env_name):
                raise CatalogError(f"{name}: invalid secret environment name {env_name}")
    for profile, members in catalog.get("profiles", {}).items():
        for name in members:
            if name not in services:
                raise CatalogError(f"profile {profile}: unknown service {name}")
    topological_order(catalog, list(services))


def topological_order(catalog: dict, selected: list[str]) -> list[str]:
    services = catalog["services"]
    selected_set = set(selected)
    ordered: list[str] = []
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(name: str) -> None:
        if name in visited:
            return
        if name in visiting:
            raise CatalogError(f"dependency cycle detected at {name}")
        if name not in services:
            raise CatalogError(f"unknown service {name}")
        visiting.add(name)
        for dependency in services[name].get("dependsOn", []):
            if dependency in selected_set:
                visit(dependency)
        visiting.remove(name)
        visited.add(name)
        ordered.append(name)

    for name in selected:
        visit(name)
    return ordered


class AuditLog:
    def __init__(self, path: Path):
        self.path = path

    def record(self, action: str, host: str, outcome: str, **details) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        entry = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "action": action,
            "host": host,
            "outcome": outcome,
            **details,
        }
        with self.path.open("a", encoding="utf-8") as file:
            file.write(json.dumps(entry, ensure_ascii=False) + "\n")


class Remote:
    def __init__(self, host: str, user: str, password: str | None, key_filename: str | None,
                 known_hosts: str | None, trust_new_host: bool):
        try:
            import paramiko
        except ImportError as exc:
            raise RemoteError("paramiko is required; install deploy/ops/requirements.txt") from exc
        self.host = host
        self.user = user
        self.client = paramiko.SSHClient()
        self.client.load_system_host_keys()
        if known_hosts:
            self.client.load_host_keys(known_hosts)
        if trust_new_host:
            self.client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        else:
            self.client.set_missing_host_key_policy(paramiko.RejectPolicy())
        try:
            self.client.connect(
                hostname=host,
                username=user,
                password=password,
                key_filename=key_filename,
                timeout=15,
                banner_timeout=15,
                auth_timeout=15,
                allow_agent=True,
                look_for_keys=True,
            )
        except Exception as exc:
            self.client.close()
            raise RemoteError(f"SSH connection failed: {type(exc).__name__}") from exc

    def run(self, command: str, timeout: int = 60) -> tuple[int, str, str]:
        _, stdout, stderr = self.client.exec_command(command, timeout=timeout)
        out = stdout.read().decode("utf-8", "replace")
        err = stderr.read().decode("utf-8", "replace")
        status = stdout.channel.recv_exit_status()
        return status, out, err

    def close(self) -> None:
        self.client.close()


class Ops:
    def __init__(self, catalog: dict, remote: Remote, audit: AuditLog, apply: bool = False):
        self.catalog = catalog
        self.remote = remote
        self.audit = audit
        self.apply = apply

    def resolve_services(self, profile: str | None, services: list[str] | None) -> list[str]:
        if services:
            return topological_order(self.catalog, services)
        if profile:
            if profile not in self.catalog.get("profiles", {}):
                raise CatalogError(f"unknown profile: {profile}")
            return topological_order(self.catalog, self.catalog["profiles"][profile])
        raise CatalogError("select --profile or --services")

    def state(self, service_name: str) -> str:
        service = self.catalog["services"][service_name]
        status, out, _ = self.remote.run(
            f"docker inspect --format '{{{{.State.Status}}}}' {shlex.quote(service['container'])}"
        )
        return out.strip() if status == 0 and out.strip() else "missing"

    def health_command(self, service_name: str) -> str:
        service = self.catalog["services"][service_name]
        health = service["health"]
        container = shlex.quote(service["container"])
        if health["type"] == "docker-health":
            return (
                "docker inspect --format "
                "'{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "
                f"{container}"
            )
        if health["type"] == "http":
            return (
                f"curl -fsS --max-time 5 http://127.0.0.1:{int(service['port'])}"
                f"{shlex.quote(health['path'])}"
            )
        return f"docker exec {container} {health['command']}"

    def healthy(self, service_name: str) -> bool:
        status, out, _ = self.remote.run(self.health_command(service_name), timeout=10)
        if status != 0:
            return False
        if self.catalog["services"][service_name]["health"]["type"] == "docker-health":
            return out.strip() in {"healthy", "running"}
        return True

    def wait_health(self, service_name: str, timeout: int = 120) -> bool:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.healthy(service_name):
                return True
            time.sleep(2)
        return False

    def status(self, services: list[str]) -> None:
        print(f"{'SERVICE':<20} {'STATE':<12} HEALTH")
        for name in services:
            state = self.state(name)
            health = "UP" if state == "running" and self.healthy(name) else "DOWN"
            print(f"{name:<20} {state:<12} {health}")

    def plan(self, action: str, services: list[str]) -> None:
        print(f"DRY-RUN {action}")
        for name in services:
            container = self.catalog["services"][name]["container"]
            print(f"  {name}: {container}")

    def start(self, services: list[str]) -> None:
        if not self.apply:
            self.plan("start", services)
            return
        for name in services:
            service = self.catalog["services"][name]
            if self.state(name) != "running":
                status, _, err = self.remote.run(f"docker start {shlex.quote(service['container'])}")
                if status != 0:
                    raise RemoteError(f"failed to start {name}: {err.strip()}")
            if not self.wait_health(name):
                raise RemoteError(f"health check failed for {name}")
            self.audit.record("start", self.remote.host, "success", service=name)
            print(f"started {name}")

    def stop(self, services: list[str]) -> None:
        ordered = list(reversed(services))
        if not self.apply:
            self.plan("stop", ordered)
            return
        for name in ordered:
            service = self.catalog["services"][name]
            if self.state(name) != "running":
                continue
            status, _, err = self.remote.run(f"docker stop {shlex.quote(service['container'])}")
            if status != 0:
                raise RemoteError(f"failed to stop {name}: {err.strip()}")
            self.audit.record("stop", self.remote.host, "success", service=name)
            print(f"stopped {name}")

    def restart(self, services: list[str]) -> None:
        if not self.apply:
            self.plan("restart", services)
            return
        for name in services:
            service = self.catalog["services"][name]
            status, _, err = self.remote.run(f"docker restart {shlex.quote(service['container'])}")
            if status != 0:
                raise RemoteError(f"failed to restart {name}: {err.strip()}")
            if not self.wait_health(name):
                raise RemoteError(f"health check failed for {name}")
            self.audit.record("restart", self.remote.host, "success", service=name)
            print(f"restarted {name}")

    def build_run_command(self, service_name: str, image: str) -> str:
        service = self.catalog["services"][service_name]
        deploy = service.get("deploy", {})
        if not deploy.get("enabled"):
            raise CatalogError(f"image deployment is not enabled for {service_name}")
        parts = [
            "docker", "run", "-d", "--name", shlex.quote(service["container"]),
            "--network", shlex.quote(deploy["network"]),
            "--restart", "unless-stopped",
            "--memory", shlex.quote(deploy["memory"]),
        ]
        for port in deploy.get("ports", []):
            parts.extend(["-p", shlex.quote(port)])
        for volume in deploy.get("volumes", []):
            parts.extend(["-v", shlex.quote(volume)])
        if deploy.get("envFile"):
            parts.extend(["--env-file", shlex.quote(deploy["envFile"])])
        for key, value in sorted(deploy.get("environment", {}).items()):
            parts.extend(["-e", shlex.quote(f"{key}={value}")])
        secret_env = deploy.get("secretEnv", [])
        for key in secret_env:
            if not ENV_NAME.fullmatch(key):
                raise CatalogError(f"invalid secret environment name {key}")
            parts.extend(["-e", f'{key}="${key}"'])
        parts.append(shlex.quote(image))
        parts.extend(shlex.quote(arg) for arg in deploy.get("command", []))
        command = " ".join(parts)
        if secret_env:
            env_file = deploy.get("envFile") or "/root/scm-deploy/.env"
            command = f". {shlex.quote(env_file)}; {command}"
        return command

    def deploy_image(self, service_name: str, image: str) -> None:
        service = self.catalog["services"][service_name]
        deploy = service.get("deploy", {})
        if not deploy.get("enabled"):
            raise CatalogError(f"image deployment is not enabled for {service_name}")
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
        backup_container = f"{service['container']}-rollback-{timestamp}"
        backup_image = f"cognora-rollback/{service_name}:{timestamp}"
        run_command = self.build_run_command(service_name, image)
        if not self.apply:
            print("DRY-RUN deploy-image")
            print(f"  pull {image}")
            print(f"  backup {service['container']} as {backup_container}")
            print(f"  {run_command}")
            return

        status, _, err = self.remote.run(f"docker pull {shlex.quote(image)}", timeout=600)
        if status != 0:
            raise RemoteError(f"image pull failed: {err.strip()}")
        status, _, err = self.remote.run(f"docker image inspect {shlex.quote(image)}")
        if status != 0:
            raise RemoteError(f"image inspection failed: {err.strip()}")

        old_exists = self.state(service_name) != "missing"
        if old_exists:
            status, old_image, err = self.remote.run(
                f"docker inspect --format '{{{{.Config.Image}}}}' {shlex.quote(service['container'])}"
            )
            if status != 0:
                raise RemoteError(f"cannot inspect current image: {err.strip()}")
            status, _, err = self.remote.run(
                f"docker tag {shlex.quote(old_image.strip())} {shlex.quote(backup_image)}"
            )
            if status != 0:
                raise RemoteError(f"cannot tag rollback image: {err.strip()}")
            status, _, err = self.remote.run(f"docker stop {shlex.quote(service['container'])}")
            if status != 0:
                raise RemoteError(f"cannot stop current container: {err.strip()}")
            status, _, err = self.remote.run(
                f"docker rename {shlex.quote(service['container'])} {shlex.quote(backup_container)}"
            )
            if status != 0:
                raise RemoteError(f"cannot preserve current container: {err.strip()}")

        try:
            status, _, err = self.remote.run(run_command, timeout=120)
            if status != 0:
                raise RemoteError(f"new container failed: {err.strip()}")
            if not self.wait_health(service_name):
                raise RemoteError(f"new container health check failed: {service_name}")
        except Exception:
            if old_exists:
                self.remote.run(f"docker rm -f {shlex.quote(service['container'])}")
                self.remote.run(
                    f"docker rename {shlex.quote(backup_container)} {shlex.quote(service['container'])}"
                )
                self.remote.run(f"docker start {shlex.quote(service['container'])}")
                self.wait_health(service_name)
            raise

        self.audit.record(
            "deploy-image", self.remote.host, "success", service=service_name,
            image=image, rollback_image=backup_image, rollback_container=backup_container,
        )
        print(f"deployed {service_name} from {image}")
        if old_exists:
            print(f"rollback image: {backup_image}")
            print(f"rollback container: {backup_container}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Cognora enterprise deployment control plane")
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--host")
    parser.add_argument("--user")
    parser.add_argument("--key")
    parser.add_argument("--known-hosts")
    parser.add_argument("--trust-new-host", action="store_true")
    parser.add_argument("--audit-file", type=Path, default=DEFAULT_AUDIT)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("status", "plan", "start", "stop", "restart"):
        command = sub.add_parser(name)
        command.add_argument("--profile")
        command.add_argument("--services", nargs="+")
        if name in {"start", "stop", "restart"}:
            command.add_argument("--apply", action="store_true")
    deploy = sub.add_parser("deploy-image")
    deploy.add_argument("service")
    deploy.add_argument("image")
    deploy.add_argument("--apply", action="store_true")
    rollback = sub.add_parser("rollback")
    rollback.add_argument("service")
    rollback.add_argument("image")
    rollback.add_argument("--apply", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        catalog = load_catalog(args.catalog)
        ops = Ops(catalog, None, AuditLog(args.audit_file), getattr(args, "apply", False))
        if args.command == "plan":
            services = ops.resolve_services(args.profile, args.services)
            ops.plan("plan", services)
            return 0
        if not getattr(args, "apply", False) and args.command in {
            "start", "stop", "restart", "deploy-image", "rollback"
        }:
            if args.command in {"deploy-image", "rollback"}:
                ops.deploy_image(args.service, args.image)
            else:
                services = ops.resolve_services(args.profile, args.services)
                getattr(ops, args.command)(services)
            return 0
        host = args.host or catalog["defaultHost"]
        user = args.user or catalog.get("defaultUser", "root")
        password = os.getenv("COGNORA_OPS_SSH_PASSWORD")
        if not password and not args.key and sys.stdin.isatty():
            password = getpass.getpass("SSH password: ")
        if not password and not args.key:
            raise RemoteError("set COGNORA_OPS_SSH_PASSWORD or provide --key")
        remote = Remote(host, user, password, args.key, args.known_hosts, args.trust_new_host)
        ops.remote = remote
        try:
            if args.command == "status":
                ops.status(ops.resolve_services(args.profile, args.services))
            elif args.command == "start":
                ops.start(ops.resolve_services(args.profile, args.services))
            elif args.command == "stop":
                ops.stop(ops.resolve_services(args.profile, args.services))
            elif args.command == "restart":
                ops.restart(ops.resolve_services(args.profile, args.services))
            elif args.command in {"deploy-image", "rollback"}:
                ops.deploy_image(args.service, args.image)
            return 0
        finally:
            remote.close()
    except (CatalogError, RemoteError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
