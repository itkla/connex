import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).parents[2]
COMPOSE_PATH = ROOT / "deploy" / "docker-compose.yml"
BACKUP_ENV_PATH = ROOT / "deploy" / "backup" / "backup.env.example"
BACKUP_LIB_PATH = ROOT / "deploy" / "backup" / "connex-backup-lib.sh"
DOCKER_CLIENT_LIB_PATH = ROOT / "deploy" / "backup" / "shims" / "docker-client-lib.sh"
BACKUP_INSTALL_PATH = ROOT / "deploy" / "backup" / "install.sh"
DEPLOYMENT_DOC_PATH = ROOT / "docs" / "DEPLOYMENT.md"


class DeploymentNetworkTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.compose = yaml.safe_load(COMPOSE_PATH.read_text(encoding="utf-8"))

    def test_services_join_only_required_networks(self) -> None:
        expected_networks = {
            "caddy": {"edge"},
            "frontend": {"edge", "app"},
            "backend": {"edge", "app", "db", "ocr_internal"},
            "ocr": {"ocr_internal"},
            "db": {"db"},
        }

        services = self.compose["services"]
        self.assertEqual(set(expected_networks), set(services))
        for service_name, expected in expected_networks.items():
            with self.subTest(service=service_name):
                self.assertEqual(expected, set(services[service_name]["networks"]))

    def test_internal_networks_are_gateway_isolated(self) -> None:
        networks = self.compose["networks"]
        self.assertEqual({"edge", "app", "db", "ocr_internal"}, set(networks))

        for network_name in ("db", "ocr_internal"):
            with self.subTest(network=network_name):
                network = networks[network_name]
                self.assertIs(network["internal"], True)
                self.assertEqual(
                    "isolated",
                    network["driver_opts"][
                        "com.docker.network.bridge.gateway_mode_ipv4"
                    ],
                )
                self.assertEqual(
                    "isolated",
                    network["driver_opts"][
                        "com.docker.network.bridge.gateway_mode_ipv6"
                    ],
                )

    def test_application_network_is_the_default_gateway(self) -> None:
        services = self.compose["services"]
        self.assertEqual(1, services["frontend"]["networks"]["app"]["gw_priority"])
        self.assertEqual(1, services["backend"]["networks"]["app"]["gw_priority"])

    def test_only_caddy_publishes_a_host_port(self) -> None:
        services = self.compose["services"]
        self.assertEqual(["${CONNEX_HTTP_PORT:-80}:80"], services["caddy"]["ports"])
        for service_name, service in services.items():
            if service_name != "caddy":
                with self.subTest(service=service_name):
                    self.assertNotIn("ports", service)

    def test_backup_run_mode_uses_only_the_database_network(self) -> None:
        expected = "CONNEX_BACKUP_DOCKER_NETWORK=connex_db"
        self.assertIn(expected, BACKUP_ENV_PATH.read_text(encoding="utf-8").splitlines())
        self.assertIn(
            "${CONNEX_BACKUP_DOCKER_NETWORK:=connex_db}",
            BACKUP_LIB_PATH.read_text(encoding="utf-8"),
        )
        self.assertIn(
            "${CONNEX_BACKUP_DOCKER_NETWORK:-connex_db}",
            DOCKER_CLIENT_LIB_PATH.read_text(encoding="utf-8"),
        )
        self.assertIn(
            "install_migrate_database_network",
            BACKUP_INSTALL_PATH.read_text(encoding="utf-8"),
        )

    def test_maintenance_upgrade_reattaches_database_before_backend_run(self) -> None:
        deployment_doc = DEPLOYMENT_DOC_PATH.read_text(encoding="utf-8")
        recreate = "docker compose up -d --wait --no-deps --force-recreate db"
        one_off_backend = "docker compose run --rm --no-deps"
        self.assertIn(recreate, deployment_doc)
        self.assertLess(deployment_doc.index(recreate), deployment_doc.index(one_off_backend))


if __name__ == "__main__":
    unittest.main()
