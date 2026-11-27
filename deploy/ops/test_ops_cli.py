import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("ops_cli.py")
spec = importlib.util.spec_from_file_location("ops_cli", MODULE_PATH)
ops_cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops_cli)


class FakeRemote:
    host = "test-host"

    def __init__(self, responses=None):
        self.commands = []
        self.responses = responses or {}

    def run(self, command, timeout=60):
        self.commands.append(command)
        response = self.responses.get(command)
        if response is not None:
            return response
        if "docker inspect --format '{{.State.Status}}'" in command:
            return 0, "running\n", ""
        if "docker inspect --format '{{.Config.Image}}'" in command:
            return 0, "old-image:1\n", ""
        if command.startswith("docker inspect --format '{{if .State.Health}}"):
            return 0, "healthy\n", ""
        return 0, "", ""


class CatalogTests(unittest.TestCase):
    def setUp(self):
        self.catalog = {
            "version": 1,
            "defaultHost": "host",
            "defaultUser": "root",
            "profiles": {"all": ["app", "db"]},
            "services": {
                "db": {
                    "container": "db",
                    "image": "postgres:18",
                    "dependsOn": [],
                    "health": {"type": "docker-health"},
                },
                "app": {
                    "container": "app",
                    "image": "app:1",
                    "dependsOn": ["db"],
                    "health": {"type": "http", "path": "/health"},
                    "deploy": {
                        "enabled": True,
                        "network": "net",
                        "ports": ["8080:8080"],
                        "memory": "700m",
                        "envFile": "/root/.env",
                        "secretEnv": ["DB_PASSWORD"],
                        "environment": {"SERVER_PORT": "8080"},
                    },
                },
            },
        }

    def test_dependency_order_is_deterministic(self):
        ops_cli.validate_catalog(self.catalog)
        self.assertEqual(
            ops_cli.topological_order(self.catalog, ["app", "db"]),
            ["db", "app"],
        )

    def test_dependency_cycle_is_rejected(self):
        self.catalog["services"]["db"]["dependsOn"] = ["app"]
        with self.assertRaises(ops_cli.CatalogError):
            ops_cli.validate_catalog(self.catalog)

    def test_unknown_profile_service_is_rejected(self):
        self.catalog["profiles"]["all"].append("missing")
        with self.assertRaises(ops_cli.CatalogError):
            ops_cli.validate_catalog(self.catalog)

    def test_run_command_uses_env_file_without_embedding_secrets(self):
        ops_cli.validate_catalog(self.catalog)
        command = ops_cli.Ops.build_run_command(self, "app", "app:2")
        self.assertIn(". /root/.env; docker run -d", command)
        self.assertIn("--env-file /root/.env", command)
        self.assertIn('DB_PASSWORD="$DB_PASSWORD"', command)
        self.assertIn("-e SERVER_PORT=8080", command)
        self.assertIn("app:2", command)
        self.assertNotIn("secret-value", command)

    def test_dry_run_does_not_touch_remote(self):
        remote = FakeRemote()
        with tempfile.TemporaryDirectory() as directory:
            audit = ops_cli.AuditLog(Path(directory) / "audit.jsonl")
            ops = ops_cli.Ops(self.catalog, remote, audit, apply=False)
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                ops.start(["app", "db"])
        self.assertEqual(remote.commands, [])
        self.assertIn("DRY-RUN start", output.getvalue())

    def test_docker_health_accepts_healthy_and_running(self):
        remote = FakeRemote()
        ops = ops_cli.Ops(self.catalog, remote, ops_cli.AuditLog(Path("unused")))
        self.assertTrue(ops.healthy("db"))
        remote.responses[ops.health_command("db")] = (0, "unhealthy\n", "")
        self.assertFalse(ops.healthy("db"))

    def test_failed_deployment_restores_previous_container(self):
        remote = FakeRemote()
        service = self.catalog["services"]["app"]
        run_command = ops_cli.Ops.build_run_command(self, "app", "app:2")
        remote.responses[run_command] = (1, "", "new container failed")
        with tempfile.TemporaryDirectory() as directory:
            ops = ops_cli.Ops(
                self.catalog,
                remote,
                ops_cli.AuditLog(Path(directory) / "audit.jsonl"),
                apply=True,
            )
            ops.wait_health = lambda name, timeout=120: True
            with self.assertRaises(ops_cli.RemoteError):
                ops.deploy_image("app", "app:2")
        self.assertTrue(any("docker rm -f app" in command for command in remote.commands))
        self.assertTrue(any("docker rename app-rollback-" in command and " app" in command for command in remote.commands))
        self.assertTrue(any(command == "docker start app" for command in remote.commands))

    def test_cli_deploy_dry_run_does_not_open_ssh(self):
        original_remote = ops_cli.Remote
        ops_cli.Remote = lambda *args, **kwargs: self.fail("dry-run must not connect")
        try:
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                result = ops_cli.main([
                    "--catalog", str(Path(__file__).with_name("service_catalog.json")),
                    "deploy-image", "gateway", "gateway:test",
                ])
        finally:
            ops_cli.Remote = original_remote
        self.assertEqual(result, 0)
        self.assertIn("DRY-RUN deploy-image", output.getvalue())

    def test_actual_catalog_loads(self):
        catalog = ops_cli.load_catalog(Path(__file__).with_name("service_catalog.json"))
        self.assertIn("platform-agent", catalog["services"])
        self.assertIn("web", catalog["profiles"]["agent"])


if __name__ == "__main__":
    unittest.main()
