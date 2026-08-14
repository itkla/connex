import json
import os
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).parents[2]
COMPOSE_PATH = ROOT / "deploy" / "docker-compose.yml"
BUILD_COMPOSE_PATH = ROOT / "deploy" / "docker-compose.build.yml"
BACKUP_ENV_PATH = ROOT / "deploy" / "backup" / "backup.env.example"
BACKUP_LIB_PATH = ROOT / "deploy" / "backup" / "connex-backup-lib.sh"
DOCKER_CLIENT_LIB_PATH = ROOT / "deploy" / "backup" / "shims" / "docker-client-lib.sh"
BACKUP_INSTALL_PATH = ROOT / "deploy" / "backup" / "install.sh"
DEPLOYMENT_DOC_PATH = ROOT / "docs" / "DEPLOYMENT.md"
UPGRADING_DOC_PATH = ROOT / "docs" / "UPGRADING.md"
DEPLOY_ENV_PATH = ROOT / "deploy" / ".env"
LOCAL_DEV_COMPOSE_PATH = ROOT / "backend" / "docker-compose.yml"
DIGEST = "0" * 64


class _DeployEnvFile:
    """Provides the gitignored deploy/.env that the bundle's env_file directive requires.

    A fresh checkout has no deploy/.env, so `docker compose config` refuses to resolve the
    model. Creating it only when absent keeps a developer's real file untouched.
    """

    def __init__(self) -> None:
        self._created = False

    def __enter__(self) -> "_DeployEnvFile":
        if not DEPLOY_ENV_PATH.exists():
            DEPLOY_ENV_PATH.write_text("", encoding="utf-8")
            self._created = True
        return self

    def __exit__(self, *_exc: object) -> None:
        if self._created:
            DEPLOY_ENV_PATH.unlink(missing_ok=True)


def resolve_compose_model(
    *compose_files: Path,
    profiles: tuple[str, ...] = (),
    http_port: str | None = "18080",
    environment_overrides: dict[str, str] | None = None,
) -> dict[str, object]:
    environment = os.environ.copy()
    environment.update(
        {
            "CONNEX_BACKEND_DIGEST": DIGEST,
            "CONNEX_FRONTEND_DIGEST": DIGEST,
            "CONNEX_OCR_DIGEST": DIGEST,
            "CONNEX_DB_PASSWORD": "network-test",
            "CONNEX_DB_ROOT_PASSWORD": "network-root-test",
            "CONNEX_OCR_SERVICE_TOKEN": "0" * 32,
            "CONNEX_DB_USERNAME": "network-test-user",
            "CONNEX_CADDY_ADDITIONAL_TRUSTED_PROXIES": "",
            "CONNEX_SECURITY_TRUSTED_PROXIES": (
                "10.0.0.0/8,172.16.0.0/12,192.168.0.0/16"
            ),
            "CONNEX_API_MAX_BODY_BYTES": "10485760",
            "CONNEX_IMPORT_MAX_BODY_BYTES": "67108864",
            "CONNEX_UPLOAD_MAX_BODY_BYTES": "28311552",
            "CONNEX_BUSINESS_CARD_MAX_BODY_BYTES": "12582912",
            "CONNEX_CLIENT_ERRORS_MAX_BODY_BYTES": "16384",
            "CONNEX_WEBAUTHN_MAX_BODY_BYTES": "65536",
            "CONNEX_WORKFLOW_MAX_BODY_BYTES": "98304",
            "CONNEX_FORM_MAX_BODY_BYTES": "1048576",
            "COMPOSE_PROFILES": "",
        }
    )
    if http_port is None:
        environment.pop("CONNEX_HTTP_PORT", None)
    else:
        environment["CONNEX_HTTP_PORT"] = http_port
    if environment_overrides is not None:
        environment.update(environment_overrides)
    command = ["docker", "compose", "--env-file", os.devnull]
    for profile in profiles:
        command.extend(("--profile", profile))
    for compose_file in compose_files:
        command.extend(("-f", str(compose_file)))
    command.extend(("config", "--format", "json"))
    with _DeployEnvFile():
        completed = subprocess.run(
            command,
            cwd=ROOT,
            env=environment,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
    if completed.returncode != 0:
        raise AssertionError(
            "docker compose config failed for "
            f"{' '.join(str(path) for path in compose_files)}: {completed.stderr.strip()}"
        )
    return json.loads(completed.stdout)


class DeploymentNetworkTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.compose_models = {
            "published": resolve_compose_model(COMPOSE_PATH),
            "published-ocr": resolve_compose_model(COMPOSE_PATH, profiles=("ocr",)),
            "source-build": resolve_compose_model(COMPOSE_PATH, BUILD_COMPOSE_PATH),
            "source-build-ocr": resolve_compose_model(
                COMPOSE_PATH, BUILD_COMPOSE_PATH, profiles=("ocr",)
            ),
        }

    def test_services_join_only_required_networks(self) -> None:
        expected_networks = {
            "caddy": {"edge"},
            "frontend": {"edge", "app"},
            "backend": {"edge", "app", "db", "ocr_internal"},
            "ocr": {"ocr_internal"},
            "db": {"db"},
        }

        for model_name, compose in self.compose_models.items():
            services = compose["services"]
            model_expected_networks = expected_networks.copy()
            if not model_name.endswith("-ocr"):
                del model_expected_networks["ocr"]
            with self.subTest(model=model_name):
                self.assertEqual(set(model_expected_networks), set(services))
            for service_name, expected in model_expected_networks.items():
                with self.subTest(model=model_name, service=service_name):
                    self.assertEqual(expected, set(services[service_name]["networks"]))

    def test_profile_selection_ignores_ambient_compose_profiles(self) -> None:
        with patch.dict(os.environ, {"COMPOSE_PROFILES": "ocr"}):
            compose = resolve_compose_model(COMPOSE_PATH)
        self.assertNotIn("ocr", compose["services"])

    def test_internal_networks_are_gateway_isolated(self) -> None:
        for model_name, compose in self.compose_models.items():
            networks = compose["networks"]
            with self.subTest(model=model_name):
                self.assertEqual({"edge", "app", "db", "ocr_internal"}, set(networks))
                self.assertFalse(networks["app"].get("internal", False))
            for network_name in ("db", "ocr_internal"):
                with self.subTest(model=model_name, network=network_name):
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
        for model_name, compose in self.compose_models.items():
            services = compose["services"]
            with self.subTest(model=model_name, service="frontend"):
                self.assertEqual(1, services["frontend"]["networks"]["app"]["gw_priority"])
            with self.subTest(model=model_name, service="backend"):
                self.assertEqual(1, services["backend"]["networks"]["app"]["gw_priority"])

    def test_only_caddy_publishes_a_host_port(self) -> None:
        for model_name, compose in self.compose_models.items():
            services = compose["services"]
            caddy_ports = services["caddy"].get("ports", [])
            with self.subTest(model=model_name, service="caddy"):
                self.assertEqual(1, len(caddy_ports))
                self.assertEqual(80, caddy_ports[0]["target"])
                self.assertEqual("18080", caddy_ports[0]["published"])
            for service_name, service in services.items():
                if service_name != "caddy":
                    with self.subTest(model=model_name, service=service_name):
                        self.assertEqual([], service.get("ports", []))

    def test_caddy_host_port_defaults_to_80(self) -> None:
        compose = resolve_compose_model(COMPOSE_PATH, http_port=None)
        self.assertEqual("80", compose["services"]["caddy"]["ports"][0]["published"])

    def test_forwarded_client_ip_chain_is_trusted_only_between_private_peers(self) -> None:
        expected_backend_proxies = "10.0.0.0/8,172.16.0.0/12,192.168.0.0/16"
        for model_name, compose in self.compose_models.items():
            services = compose["services"]
            with self.subTest(model=model_name, service="backend"):
                self.assertEqual(
                    expected_backend_proxies,
                    services["backend"]["environment"]["CONNEX_SECURITY_TRUSTED_PROXIES"],
                )
            with self.subTest(model=model_name, service="caddy"):
                self.assertEqual(
                    "",
                    services["caddy"]["environment"][
                        "CONNEX_CADDY_ADDITIONAL_TRUSTED_PROXIES"
                    ],
                )

    def test_caddy_request_limits_share_backend_environment_contracts(self) -> None:
        expected_limits = {
            "CONNEX_API_MAX_BODY_BYTES": "10485760",
            "CONNEX_IMPORT_MAX_BODY_BYTES": "67108864",
            "CONNEX_UPLOAD_MAX_BODY_BYTES": "28311552",
            "CONNEX_BUSINESS_CARD_MAX_BODY_BYTES": "12582912",
            "CONNEX_CLIENT_ERRORS_MAX_BODY_BYTES": "16384",
            "CONNEX_WEBAUTHN_MAX_BODY_BYTES": "65536",
            "CONNEX_WORKFLOW_MAX_BODY_BYTES": "98304",
            "CONNEX_FORM_MAX_BODY_BYTES": "1048576",
        }
        for model_name, compose in self.compose_models.items():
            services = compose["services"]
            caddy_environment = services["caddy"]["environment"]
            backend_environment = services["backend"]["environment"]
            with self.subTest(model=model_name):
                self.assertEqual(
                    expected_limits,
                    {
                        name: caddy_environment[name]
                        for name in expected_limits
                    },
                )
                self.assertEqual(
                    {name: backend_environment[name] for name in expected_limits},
                    {name: caddy_environment[name] for name in expected_limits},
                )
        overridden = resolve_compose_model(
            COMPOSE_PATH,
            environment_overrides={"CONNEX_API_MAX_BODY_BYTES": "7340032"},
        )
        self.assertEqual(
            "7340032",
            overridden["services"]["caddy"]["environment"][
                "CONNEX_API_MAX_BODY_BYTES"
            ],
        )
        self.assertEqual(
            "7340032",
            overridden["services"]["backend"]["environment"][
                "CONNEX_API_MAX_BODY_BYTES"
            ],
        )

    def test_local_development_publishes_only_on_loopback(self) -> None:
        model = resolve_compose_model(LOCAL_DEV_COMPOSE_PATH, http_port=None)
        services = model["services"]
        published = [
            (name, port)
            for name, service in services.items()
            for port in service.get("ports", [])
        ]
        self.assertTrue(published, "the local development stack must publish something")
        for name, port in published:
            self.assertEqual(
                "127.0.0.1",
                port.get("host_ip"),
                f"{name} publishes {port.get('published')} beyond loopback; the local"
                " development database and its admin console must not be reachable"
                " from the local network",
            )

    def test_backup_run_mode_uses_only_the_database_network(self) -> None:
        expected = "CONNEX_BACKUP_DOCKER_NETWORK=auto"
        self.assertIn(expected, BACKUP_ENV_PATH.read_text(encoding="utf-8").splitlines())
        self.assertIn(
            "${CONNEX_BACKUP_DOCKER_NETWORK:=auto}",
            BACKUP_LIB_PATH.read_text(encoding="utf-8"),
        )
        self.assertIn(
            "shim_discover_database_network",
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

    def test_upgrade_runbook_refreshes_backup_tools_before_data_plane(self) -> None:
        upgrading_doc = UPGRADING_DOC_PATH.read_text(encoding="utf-8")
        installer = "sudo ./backup/install.sh"
        data_plane = "docker compose up -d --wait --wait-timeout 300 db ocr backend"
        self.assertIn(installer, upgrading_doc)
        self.assertLess(upgrading_doc.index(installer), upgrading_doc.index(data_plane))


if __name__ == "__main__":
    unittest.main()
