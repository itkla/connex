import copy
import json
import socket
import subprocess
import sys
import unittest
from unittest.mock import patch

from smoke_deployment_networks import CLIENT, fixture_model, probe, validate_dev_publications, validate_runtime


class NetworkSmokeTest(unittest.TestCase):
    def model(self) -> dict[str, object]:
        return {
            "services": {"backend": {"networks": {"app": {"aliases": ["backend-app"], "gw_priority": 1}, "db": None}},
                         "db": {"networks": {"db": None}, "volumes": ["live-data:/var/lib/mysql"]}},
            "networks": {"app": {"name": "connex_app", "driver": "bridge"},
                         "db": {"name": "connex_db", "internal": True, "driver_opts": {
                             "com.docker.network.bridge.gateway_mode_ipv4": "isolated"}}},
        }

    def test_live_names_and_volumes_cannot_escape_into_the_fixture(self) -> None:
        source = self.model()
        original = copy.deepcopy(source)
        model = fixture_model(source, "unique-smoke")
        self.assertEqual(original, source)
        self.assertEqual("unique-smoke_db", model["networks"]["db"]["name"])
        self.assertEqual(source["networks"]["db"]["driver_opts"], model["networks"]["db"]["driver_opts"])
        self.assertEqual(source["services"]["backend"]["networks"], model["services"]["backend"]["networks"])
        self.assertNotIn("volumes", model["services"]["db"])

    def test_external_networks_are_rejected(self) -> None:
        source = self.model()
        source["networks"]["db"]["external"] = True
        with self.assertRaises(ValueError):
            fixture_model(source, "unique-smoke")

    def test_host_publications_explicitly_request_an_ephemeral_port(self) -> None:
        source = self.model()
        source["services"]["db"]["ports"] = [
            {"target": 3306, "published": "3306", "host_ip": "127.0.0.1"}
        ]
        model = fixture_model(source, "unique-smoke")
        self.assertEqual([{ "target": 3306, "published": "0", "host_ip": "127.0.0.1", "protocol": "tcp"}],
                         model["services"]["db"]["ports"])

    def test_missing_required_dev_publications_cannot_skip_host_probes(self) -> None:
        source = self.model()
        source["services"]["adminer"] = {"networks": {"db": None}}
        with self.assertRaises(AssertionError):
            validate_dev_publications(source)

    def test_service_settings_that_can_escape_the_project_are_rejected(self) -> None:
        for key, value in (("network_mode", "host"), ("container_name", "live-db"),
                           ("privileged", True), ("cap_add", ["NET_ADMIN"]),
                           ("extra_hosts", ["live-db:192.0.2.1"])):
            with self.subTest(key=key):
                source = self.model()
                source["services"]["db"][key] = value
                with self.assertRaises(ValueError):
                    fixture_model(source, "unique-smoke")

    def test_a_foreign_container_or_an_extra_network_fails_validation(self) -> None:
        model = fixture_model(self.model(), "unique-smoke")
        container = {"Config": {"Labels": {"com.docker.compose.project": "live"}},
                     "NetworkSettings": {"Networks": {"unique-smoke_db": {}}}}
        with self.assertRaises(AssertionError):
            validate_runtime(model, "unique-smoke", {"db": container})
        container["Config"]["Labels"]["com.docker.compose.project"] = "unique-smoke"
        container["NetworkSettings"]["Networks"]["live_default"] = {}
        with self.assertRaises(AssertionError):
            validate_runtime(model, "unique-smoke", {"db": container})

    def test_an_executor_failure_never_counts_as_network_isolation(self) -> None:
        with patch("smoke_deployment_networks.run", side_effect=RuntimeError("docker unavailable")):
            with self.assertRaises(RuntimeError):
                probe(["docker"], "fixture", "192.0.2.1", 3306, "db", False)

    def test_a_successful_connection_fails_a_negative_expectation(self) -> None:
        with patch("smoke_deployment_networks.run", return_value=json.dumps({"reachable": True})):
            with self.assertRaises(AssertionError):
                probe(["docker"], "fixture", "192.0.2.1", 3306, "db", False)

    def test_client_treats_a_resolution_error_as_failure_not_a_blocked_ip_path(self) -> None:
        with patch("sys.argv", ["probe", "invalid..host", "3306", "db"]), \
                patch("socket.create_connection", side_effect=socket.gaierror(-2, "resolution failed")):
            with self.assertRaises(socket.gaierror):
                exec(CLIENT, {})

    def test_client_identifies_an_actual_refused_tcp_connection(self) -> None:
        with socket.socket() as reserved:
            reserved.bind(("127.0.0.1", 0))
            port = reserved.getsockname()[1]
            result = subprocess.run([sys.executable, "-c", CLIENT, "127.0.0.1", str(port), "db"],
                                    capture_output=True, text=True, timeout=5, check=True)
        self.assertIs(json.loads(result.stdout)["reachable"], False)


if __name__ == "__main__":
    unittest.main()
