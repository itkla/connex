"""Exercise the rendered Compose network topology with disposable IPv4 TCP peers."""

import argparse
import copy
import ipaddress
import json
import os
from pathlib import Path
import shlex
import socket
import subprocess
import tempfile
import uuid

from test_deployment_networks import (
    COMPOSE_PATH,
    DOCKER_CLIENT_LIB_PATH,
    LOCAL_DEV_COMPOSE_PATH,
    ROOT,
    resolve_compose_model,
)


IMAGE = "python:3.13-alpine@sha256:540c7d91f98ff6880174c40e99067bf5941eb54d818a7a5e094d188b196a934d"
PORTS = {"caddy": (80,), "frontend": (3000,), "backend": (8080,),
         "db": (3306, 33060), "adminer": (8080,), "ocr": (8090,), "clamav": (8091,)}
DEV_HOST_PORTS = {"db": (3306, 33060), "adminer": (8080,)}
SERVER = """
import socketserver, sys, threading
class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.sendall(sys.argv[1].encode())
class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
for port in sys.argv[2:]:
    server = Server(('0.0.0.0', int(port)), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
threading.Event().wait()
"""
CLIENT = """
import errno, json, socket, sys
try:
    connection = socket.create_connection((sys.argv[1], int(sys.argv[2])), timeout=1)
except OSError as error:
    if error.errno not in (None, errno.ECONNREFUSED, errno.ENETUNREACH, errno.EHOSTUNREACH, errno.ETIMEDOUT):
        raise
    if error.errno is None and not isinstance(error, TimeoutError):
        raise
    print(json.dumps({'reachable': False, 'errno': error.errno}))
else:
    with connection:
        payload = connection.recv(256).decode()
        if payload != sys.argv[3]:
            raise RuntimeError('unexpected TCP peer: ' + repr(payload))
    print(json.dumps({'reachable': True}))
"""


def run(command: list[str], *, environment: dict[str, str] | None = None,
        timeout: int = 90) -> str:
    result = subprocess.run(command, cwd=ROOT, env=environment, capture_output=True,
                            text=True, timeout=timeout, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"Command failed ({result.returncode}): {shlex.join(command)}\n"
                           f"{result.stdout}\n{result.stderr}")
    return result.stdout.strip()


def fixture_model(source: dict[str, object], project: str) -> dict[str, object]:
    """Keep the source network contract while removing application state and fixed host ports."""
    networks = copy.deepcopy(source["networks"])
    for name, network in networks.items():
        if network.get("external"):
            raise ValueError("External networks cannot be used by a disposable smoke")
        network["name"] = f"{project}_{name}"
    services = {}
    for name, service in source["services"].items():
        if any(service.get(key) for key in ("network_mode", "container_name", "external_links",
                                            "links", "extra_hosts", "privileged", "cap_add")):
            raise ValueError(f"Unsupported network-affecting setting on {name}")
        if not service.get("networks") or "default" in service["networks"]:
            raise ValueError(f"Missing explicit network membership on {name}")
        ports = PORTS[name]
        services[name] = {
            "image": IMAGE,
            "entrypoint": ["python", "-u", "-c", SERVER],
            "command": [name, *(str(port) for port in ports)],
            "networks": copy.deepcopy(service["networks"]),
            "read_only": True,
            "cap_drop": ["ALL"],
            "security_opt": ["no-new-privileges:true"],
            "pids_limit": 32,
            "mem_limit": "96m",
            "healthcheck": {
                "test": ["CMD", "python", "-c",
                         f"import socket; socket.create_connection(('127.0.0.1', {ports[0]}), timeout=1).close()"],
                "interval": "1s", "timeout": "2s", "retries": 20,
            },
        }
        if service.get("ports"):
            services[name]["ports"] = [
                {"target": port["target"], "published": "0", "host_ip": "127.0.0.1",
                 "protocol": port.get("protocol", "tcp")}
                for port in service["ports"]
            ]
    return {"name": project, "services": services, "networks": networks}


def validate_runtime(model: dict[str, object], project: str,
                     containers: dict[str, dict[str, object]]) -> None:
    for name, container in containers.items():
        labels = container["Config"]["Labels"]
        if labels.get("com.docker.compose.project") != project:
            raise AssertionError(f"Container {name} escaped the disposable project")
        expected = {f"{project}_{network}" for network in model["services"][name]["networks"]}
        if set(container["NetworkSettings"]["Networks"]) != expected:
            raise AssertionError(f"Unexpected runtime memberships for {name}")


def probe(docker: list[str], container: str, host: str, port: int,
          target: str, expected: bool) -> None:
    result = json.loads(run([*docker, "exec", container, "python", "-c", CLIENT,
                             host, str(port), target]))
    if result.get("reachable") is not expected:
        raise AssertionError(f"Expected reachable={expected} for {host}:{port} ({target}), got {result}")


def validate_dev_publications(source: dict[str, object]) -> None:
    for name, targets in DEV_HOST_PORTS.items():
        ports = source["services"][name].get("ports", [])
        if {(port["target"], port.get("protocol", "tcp")) for port in ports} != {(target, "tcp") for target in targets}:
            raise AssertionError(f"Missing or unexpected required development publications on {name}")


def exercise(docker: list[str], mode: str) -> None:
    source = resolve_compose_model(
        COMPOSE_PATH if mode == "deployment" else LOCAL_DEV_COMPOSE_PATH,
        profiles=("ocr", "clamav"),
    )
    if mode == "dev":
        validate_dev_publications(source)
    for name, service in source["services"].items():
        if mode == "deployment" and name != "caddy" and service.get("ports"):
            raise AssertionError(f"Internal deployment service {name} publishes a host port")
        if mode == "dev" and any(port.get("host_ip") != "127.0.0.1" for port in service.get("ports", [])):
            raise AssertionError(f"Development service {name} publishes beyond loopback")
    project = f"connex-network-smoke-{uuid.uuid4().hex[:12]}"
    model = fixture_model(source, project)
    with tempfile.TemporaryDirectory(prefix=project) as directory:
        path = Path(directory) / "compose.json"
        path.write_text(json.dumps(model), encoding="utf-8")
        compose = [*docker, "compose", "--project-name", project, "-f", str(path)]
        try:
            run([*compose, "up", "-d", "--wait", "--wait-timeout", "30", "--no-build"], timeout=150)
            ids = {name: run([*compose, "ps", "-q", name]) for name in model["services"]}
            if any(not value for value in ids.values()):
                raise AssertionError("A fixture container is missing")
            containers = {name: json.loads(run([*docker, "inspect", value]))[0]
                          for name, value in ids.items()}
            validate_runtime(model, project, containers)
            for logical, network in model["networks"].items():
                actual = json.loads(run([*docker, "network", "inspect", network["name"]]))[0]
                labels = actual["Labels"]
                if labels.get("com.docker.compose.project") != project or labels.get("com.docker.compose.network") != logical:
                    raise AssertionError("A fixture network escaped the disposable project")
                if actual["Internal"] != network.get("internal", False) or actual["Options"] != network.get("driver_opts", {}):
                    raise AssertionError(f"Runtime isolation options differ on {logical}")

            def address(target: str, network: str) -> str:
                value = containers[target]["NetworkSettings"]["Networks"][f"{project}_{network}"]["IPAddress"]
                return str(ipaddress.IPv4Address(value))

            def check(caller: str, target: str, network: str, expected: bool) -> None:
                probe(docker, ids[caller], address(target, network), PORTS[target][0], target, expected)
                print(f"{mode}: {caller} -> {target}/{network} IPv4 {'allowed' if expected else 'blocked'}", flush=True)

            def check_loopback() -> None:
                for name, targets in DEV_HOST_PORTS.items():
                    container = containers[name]
                    for target in targets:
                        key = f"{target}/tcp"
                        bindings = container["NetworkSettings"]["Ports"].get(key)
                        if not bindings:
                            raise AssertionError(f"Missing dev loopback publication for {name}:{key}")
                        for binding in bindings:
                            if binding["HostIp"] != "127.0.0.1":
                                raise AssertionError("A dev fixture published beyond loopback")
                            with socket.create_connection(("127.0.0.1", int(binding["HostPort"])), timeout=2) as connection:
                                if connection.recv(256).decode() != name:
                                    raise AssertionError("Unexpected loopback TCP peer")
                            print(f"dev: host loopback -> {name} allowed", flush=True)

            def check_sidecar_targets() -> None:
                for name in ("ocr", "clamav"):
                    network = f"{name}_internal"
                    check(name, name, network, True)
                    with socket.create_connection((address(name, network), PORTS[name][0]), timeout=2) as connection:
                        if connection.recv(256).decode() != name:
                            raise AssertionError("Unexpected host-to-sidecar TCP peer")
                    print(f"dev: host -> {name} bridge IPv4 allowed", flush=True)

            if mode == "deployment":
                allowed = [("caddy", "frontend", "edge"), ("caddy", "backend", "edge"),
                           ("frontend", "caddy", "edge"),
                           ("frontend", "backend", "app"), ("backend", "db", "db"),
                           ("backend", "ocr", "ocr_internal"), ("backend", "clamav", "clamav_internal")]
                denied = [(caller, "db", "db") for caller in ("caddy", "frontend", "ocr", "clamav")]
                denied += [(caller, target, network) for caller in ("ocr", "clamav")
                           for target, network in (("frontend", "edge"), ("frontend", "app"), ("caddy", "edge"))]
                denied += [("ocr", "clamav", "clamav_internal"), ("clamav", "ocr", "ocr_internal")]
                denied += [(caller, target, f"{target}_internal") for caller in ("caddy", "frontend")
                           for target in ("ocr", "clamav")]
                for caller, target, network in allowed:
                    check(caller, target, network, True)
                probe(docker, ids["frontend"], "backend-app", 8080, "backend", True)
                for caller, target, network in denied:
                    check(caller, target, network, False)
                for caller, target, network in allowed:
                    check(caller, target, network, True)
                for setting in ("auto", f"{project}_default"):
                    environment = os.environ.copy()
                    environment.update({"CONNEX_BACKUP_DOCKER_BIN": shlex.join(docker),
                                        "CONNEX_BACKUP_DB_CONTAINER": ids["db"],
                                        "CONNEX_BACKUP_DOCKER_NETWORK": setting})
                    network = run(["bash", "-c", 'source "$1"; shim_resolve_database_network --host=db',
                                   "network-smoke", str(DOCKER_CLIENT_LIB_PATH)], environment=environment)
                    if network != f"{project}_db":
                        raise AssertionError("Backup resolver selected an unexpected network")
                    result = json.loads(run([*docker, "run", "--rm", "--network", network,
                                             "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
                                             IMAGE, "python", "-c", CLIENT, "db", "3306", "db"]))
                    if result.get("reachable") is not True:
                        raise AssertionError("The DB-only maintenance peer cannot reach MySQL's port")
                    print(f"deployment: backup resolver {setting} -> db allowed", flush=True)
            else:
                check_loopback()
                check("adminer", "db", "db", True)
                check_sidecar_targets()
                for caller, target, network in (("ocr", "db", "db"), ("clamav", "db", "db"),
                                                 ("ocr", "clamav", "clamav_internal"),
                                                 ("clamav", "ocr", "ocr_internal"),
                                                 ("adminer", "ocr", "ocr_internal"),
                                                 ("adminer", "clamav", "clamav_internal")):
                    check(caller, target, network, False)
                check("adminer", "db", "db", True)
                check_sidecar_targets()
                check_loopback()
        finally:
            run([*compose, "down", "--remove-orphans", "--timeout", "3"])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker-command", default="docker", help="Docker daemon CLI, e.g. 'sudo -n docker'")
    args = parser.parse_args()
    docker = shlex.split(args.docker_command)
    if not docker:
        parser.error("Docker command cannot be empty")
    run([*docker, "pull", IMAGE], timeout=150)
    for mode in ("deployment", "dev"):
        exercise(docker, mode)
    print("Deployment and dev IPv4 network smoke passed; disposable projects removed.")


if __name__ == "__main__":
    main()
