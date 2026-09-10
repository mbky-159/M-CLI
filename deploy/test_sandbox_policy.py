"""Regression checks against accidental weakening of the rendered lab config."""
import copy
import json
import unittest

from check_sandbox import COMPOSE, command, validate


class SandboxPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.config = json.loads(command(COMPOSE + ["--profile", "sandbox", "--profile", "middleware",
                                                    "config", "--format", "json"]))

    def test_rendered_policy(self):
        validate(self.config)

    def test_rejects_isolation_regressions(self):
        changes = [
            ("runtime", "runc"), ("network_mode", "host"), ("user", "0:0"),
            ("read_only", False), ("mem_limit", 0), ("memswap_limit", -1),
            ("pids_limit", 0), ("cpus", 0), ("cap_drop", []), ("security_opt", []),
            ("privileged", True), ("pid", "host"), ("ipc", "host"),
            ("volumes", [{"source": "/var/run/docker.sock"}]),
            ("environment", {"GLM_API_KEY": "test-only"}), ("ports", [8080]),
            ("tmpfs", ["/workspace"]),
        ]
        for key, value in changes:
            with self.subTest(key=key):
                config = copy.deepcopy(self.config)
                config["services"]["sandbox"][key] = value
                with self.assertRaises(ValueError):
                    validate(config)

    def test_rejects_middleware_exposure(self):
        for name in ("mysql", "redis"):
            with self.subTest(service=name):
                config = copy.deepcopy(self.config)
                config["services"][name]["ports"] = [3306]
                with self.assertRaises(ValueError):
                    validate(config)
        config = copy.deepcopy(self.config)
        config["networks"]["backend"]["internal"] = False
        with self.assertRaises(ValueError):
            validate(config)


if __name__ == "__main__":
    unittest.main()
