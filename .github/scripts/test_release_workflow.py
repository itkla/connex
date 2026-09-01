import os
import re
import shlex
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

import yaml


WORKFLOW_PATH = Path(__file__).parents[1] / "workflows" / "release.yml"
PRECONDITIONS_PATH = Path(__file__).parent / "verify-release-preconditions.sh"
DEPLOYMENT_PATH = Path(__file__).parents[2] / "docs" / "DEPLOYMENT.md"
SEMVER_TAG_PATTERN = (
    r"^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(-((0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
    r"(\.(0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*))?$"
)
PUBLISH_MODE_CONDITION = "needs.metadata.outputs.release_mode == 'publish'"
DRY_RUN_MODE_CONDITION = "needs.metadata.outputs.release_mode == 'dry-run'"
BUILD_PUSH_MODE_INPUT = "${{ needs.metadata.outputs.release_mode == 'publish' }}"
EVENT_MODE_EXPRESSION = "${{ github.event_name == 'push' && 'publish' || 'dry-run' }}"
CONFIRMED_SINK = "CONFIRMED_SINK"
UNREVIEWED = "UNREVIEWED"
RELEASE_OR_PACKAGE_ENDPOINT_PATTERN = re.compile(r"(?:^|/)(releases|packages)(?:/|\b)", re.IGNORECASE)
NONPUBLISHING_ACTIONS = {
    "actions/checkout",
    "anchore/scan-action",
    "docker/setup-buildx-action",
}
BUILD_ACTIONS = {
    "docker/bake-action",
    "docker/build-push-action",
}
DOCKER_GLOBAL_OPTIONS_WITH_VALUE = {
    "--config",
    "--context",
    "--host",
    "--log-level",
    "-H",
}


def split_top_level(expression: str, operator: str) -> list[str]:
    parts: list[str] = []
    depth = 0
    quote = ""
    start = 0
    index = 0
    while index < len(expression):
        character = expression[index]
        if quote:
            if character == quote and (index == 0 or expression[index - 1] != "\\"):
                quote = ""
        elif character in ("'", '"'):
            quote = character
        elif character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
        elif depth == 0 and expression.startswith(operator, index):
            parts.append(expression[start:index])
            start = index + len(operator)
            index += len(operator) - 1
        index += 1
    parts.append(expression[start:])
    return parts


def strip_outer_parentheses(expression: str) -> str:
    stripped = expression.strip()
    while stripped.startswith("(") and stripped.endswith(")"):
        depth = 0
        closes_at_end = True
        for index, character in enumerate(stripped):
            if character == "(":
                depth += 1
            elif character == ")":
                depth -= 1
                if depth == 0 and index != len(stripped) - 1:
                    closes_at_end = False
                    break
        if not closes_at_end:
            break
        stripped = stripped[1:-1].strip()
    return stripped


def effective_publish_condition(condition: object) -> bool:
    expression = str(condition or "").strip()
    if expression.startswith("${{") and expression.endswith("}}"):
        expression = expression[3:-2].strip()
    expression = strip_outer_parentheses(expression)
    alternatives = split_top_level(expression, "||")
    if len(alternatives) > 1:
        return all(effective_publish_condition(alternative) for alternative in alternatives)
    conjunctions = split_top_level(expression, "&&")
    if len(conjunctions) > 1:
        return any(effective_publish_condition(conjunction) for conjunction in conjunctions)
    return " ".join(expression.split()) == PUBLISH_MODE_CONDITION


def shell_commands(script: str) -> list[list[str]]:
    script = re.sub(r"\\\r?\n\s*", " ", script)
    lexer = shlex.shlex(script, posix=True, punctuation_chars=";&|()\n")
    lexer.commenters = "#"
    lexer.whitespace = " \t\r"
    lexer.whitespace_split = True
    commands: list[list[str]] = []
    command: list[str] = []
    for token in lexer:
        if token and all(character in ";&|()\n" for character in token):
            if command:
                commands.append(command)
                command = []
        else:
            command.append(token)
    if command:
        commands.append(command)
    return commands


def gh_api_method(arguments: list[str]) -> str:
    explicit_method = ""
    has_post_fields = False
    has_dynamic_arguments = any(
        re.fullmatch(r"\$(?:[A-Za-z_][A-Za-z0-9_]*|\{[^}]+\})", argument)
        for argument in arguments
    )
    index = 0
    while index < len(arguments):
        argument = arguments[index]
        if argument in {"-X", "--method"} and index + 1 < len(arguments):
            explicit_method = arguments[index + 1]
            index += 1
        elif argument.startswith("--method="):
            explicit_method = argument.split("=", maxsplit=1)[1]
        elif argument.startswith("-X") and len(argument) > 2:
            explicit_method = argument[2:].removeprefix("=")
        elif argument in {"-f", "-F", "--field", "--raw-field", "--input"}:
            has_post_fields = True
        elif argument.startswith(("--field=", "--raw-field=", "--input=")):
            has_post_fields = True
        elif (argument.startswith("-f") or argument.startswith("-F")) and len(argument) > 2:
            has_post_fields = True
        index += 1
    if has_dynamic_arguments:
        return "DYNAMIC"
    if explicit_method:
        return explicit_method.upper()
    return "POST" if has_post_fields else "GET"


def buildx_export_destination(value: str) -> str:
    if "type=registry" in value:
        return "type=registry"
    if "type=image" in value and ("push=true" in value or "push-by-digest=true" in value):
        return "type=image with push"
    if "$" in value:
        return "dynamic destination"
    return ""


def buildx_registry_exports(arguments: list[str]) -> list[str]:
    sinks: list[str] = []
    index = 0
    while index < len(arguments):
        argument = arguments[index]
        value = ""
        matched_flag = ""
        for flag in ("--output", "-o", "--cache-to"):
            if argument == flag and index + 1 < len(arguments):
                matched_flag = flag
                value = arguments[index + 1]
                index += 1
                break
            if argument.startswith(f"{flag}="):
                matched_flag = flag
                value = argument.split("=", maxsplit=1)[1]
                break
        destination = buildx_export_destination(value)
        if matched_flag and destination:
            sinks.append(f"docker buildx {matched_flag} {destination}")
        index += 1
    return sinks


def docker_subcommand(arguments: list[str]) -> tuple[str, list[str]]:
    index = 0
    while index < len(arguments):
        argument = arguments[index]
        if argument in DOCKER_GLOBAL_OPTIONS_WITH_VALUE:
            index += 2
            continue
        long_options = (
            option
            for option in DOCKER_GLOBAL_OPTIONS_WITH_VALUE
            if option.startswith("--")
        )
        if any(argument.startswith(f"{option}=") for option in long_options):
            index += 1
            continue
        if argument.startswith("-"):
            index += 1
            continue
        return argument, arguments[index + 1:]
    return "", []


def publication_sinks(step: dict[str, object]) -> list[tuple[str, str]]:
    sinks: list[tuple[str, str]] = []
    run = step.get("run")
    if isinstance(run, str):
        for command in shell_commands(run):
            for index, token in enumerate(command):
                if token == "docker":
                    docker_arguments = command[index + 1:]
                    subcommand, subcommand_arguments = docker_subcommand(docker_arguments)
                    if subcommand == "push":
                        sinks.append((CONFIRMED_SINK, "docker push"))
                    if subcommand == "buildx":
                        buildx_arguments = subcommand_arguments
                        push_index = next(
                            (
                                candidate_index
                                for candidate_index, argument in enumerate(buildx_arguments)
                                if argument == "--push" or argument.startswith("--push=")
                            ),
                            None,
                        )
                        if push_index is not None:
                            sinks.append((CONFIRMED_SINK, "docker buildx ... --push"))
                        sinks.extend(
                            (CONFIRMED_SINK, sink)
                            for sink in buildx_registry_exports(buildx_arguments)
                        )
                        if "imagetools" in buildx_arguments:
                            imagetools_index = buildx_arguments.index("imagetools")
                            if buildx_arguments[imagetools_index + 1:imagetools_index + 2] == ["create"]:
                                sinks.append((CONFIRMED_SINK, "docker buildx imagetools create"))
                if token == "buildx" and "docker" not in command[:index]:
                    buildx_arguments = command[index + 1:]
                    push_index = next(
                        (
                            candidate_index
                            for candidate_index in range(index + 1, len(command))
                            if command[candidate_index] == "--push" or command[candidate_index].startswith("--push=")
                        ),
                        None,
                    )
                    if push_index is not None:
                        sinks.append((CONFIRMED_SINK, "buildx ... --push"))
                    sinks.extend(
                        (CONFIRMED_SINK, sink)
                        for sink in buildx_registry_exports(buildx_arguments)
                    )
                if (
                    token == "imagetools"
                    and "docker" not in command[:index]
                    and command[index + 1:index + 2] == ["create"]
                ):
                    sinks.append((CONFIRMED_SINK, "imagetools create"))
                if token == "cosign":
                    operation = next(
                        (
                            argument
                            for argument in command[index + 1:]
                            if argument in {"sign", "attest", "sign-blob"}
                        ),
                        None,
                    )
                    if operation:
                        sinks.append((CONFIRMED_SINK, f"cosign {operation}"))
                if token != "gh":
                    continue
                next_gh = command.index("gh", index + 1) if "gh" in command[index + 1:] else len(command)
                gh_arguments = command[index + 1:next_gh]
                for subcommand_index, subcommand in enumerate(gh_arguments):
                    if subcommand == "release" and gh_arguments[subcommand_index + 1:subcommand_index + 2]:
                        operation = gh_arguments[subcommand_index + 1]
                        if operation in {"create", "upload", "edit", "delete"}:
                            sinks.append((CONFIRMED_SINK, f"gh release {operation}"))
                    if subcommand != "api":
                        continue
                    api_arguments = gh_arguments[subcommand_index + 1:]
                    method = gh_api_method(api_arguments)
                    endpoint = next(
                        (
                            match
                            for argument in api_arguments
                            if (match := RELEASE_OR_PACKAGE_ENDPOINT_PATTERN.search(argument))
                        ),
                        None,
                    )
                    if method not in {"GET", "HEAD"}:
                        if endpoint:
                            sinks.append(
                                (CONFIRMED_SINK, f"gh api {method} .../{endpoint.group(1).lower()}")
                            )
                        else:
                            sinks.append(
                                (CONFIRMED_SINK, f"gh api {method} (endpoint not statically classified)")
                            )

    uses = step.get("uses")
    if not isinstance(uses, str):
        return sinks
    action = uses.split("@", maxsplit=1)[0].lower()
    action_tokens = set(re.split(r"[/_.-]+", action))
    inputs = step.get("with")
    action_inputs = inputs if isinstance(inputs, dict) else {}
    independent_publication_tokens = {
        "attest",
        "attestation",
        "package",
        "publish",
        "publishing",
        "registry",
        "release",
        "upload",
    }
    if independent_publication_tokens & action_tokens:
        sinks.append((CONFIRMED_SINK, f"uses: {uses}"))
    for input_name in ("outputs", "cache-to"):
        input_value = action_inputs.get(input_name)
        if isinstance(input_value, str):
            destination = buildx_export_destination(input_value)
            if destination:
                sinks.append((CONFIRMED_SINK, f"uses: {uses} with {input_name}: {destination}"))
    if "push" in action_inputs and action in BUILD_ACTIONS:
        push_input = action_inputs["push"]
        if push_input is not False and push_input != BUILD_PUSH_MODE_INPUT:
            sinks.append((CONFIRMED_SINK, f"uses: {uses} with push: {push_input}"))
        return sinks
    if action in NONPUBLISHING_ACTIONS:
        return sinks
    if (
        action == "anchore/sbom-action"
        and action_inputs.get("upload-artifact") is False
        and action_inputs.get("upload-release-assets") is False
    ):
        return sinks
    if not sinks:
        sinks.append((UNREVIEWED, f"uses: {uses}"))
    return sinks


def publication_gate_errors(workflow: dict[str, object]) -> list[str]:
    errors: list[str] = []
    jobs = workflow.get("jobs")
    if not isinstance(jobs, dict):
        return [".github/workflows/release.yml: jobs must be a mapping"]
    for job_name, job_value in jobs.items():
        if not isinstance(job_value, dict):
            continue
        job_condition = job_value.get("if")
        job_sinks = publication_sinks(job_value)
        if job_sinks and not effective_publish_condition(job_condition):
            for classification, sink in job_sinks:
                reason = (
                    "publication sink lacks an effective publish-only condition"
                    if classification == CONFIRMED_SINK
                    else "audit this action before classifying it as nonpublishing"
                )
                errors.append(
                    ".github/workflows/release.yml: "
                    f"job '{job_name}', step '<job-level uses>': {classification}: "
                    f"{reason}: {sink}"
                )
        steps = job_value.get("steps")
        if not isinstance(steps, list):
            steps = []
        for index, step_value in enumerate(steps, start=1):
            if not isinstance(step_value, dict):
                continue
            sinks = publication_sinks(step_value)
            if not sinks:
                continue
            if effective_publish_condition(step_value.get("if")) or effective_publish_condition(job_condition):
                continue
            step_name = step_value.get("name") or step_value.get("id") or f"step {index}"
            for classification, sink in sinks:
                reason = (
                    "publication sink lacks an effective publish-only condition"
                    if classification == CONFIRMED_SINK
                    else "audit this action before classifying it as nonpublishing"
                )
                errors.append(
                    ".github/workflows/release.yml: "
                    f"job '{job_name}', step '{step_name}': {classification}: "
                    f"{reason}: {sink}"
                )
    return errors



def evaluate_concurrency_group(group: str, event_name: str) -> str:
    """
    Resolves the workflow concurrency group for one event, mimicking GitHub's expression
    semantics: `&&` and `||` yield an operand rather than a boolean, and the empty string is
    falsy. Writing `cond && '' || '-suffix'` therefore produces '-suffix' on *both* branches,
    silently collapsing the publication and dry-run groups into one.
    """
    pattern = re.compile(
        r"\$\{\{\s*github\.event_name\s*==\s*'(?P<event>[a-z_]+)'"
        r"\s*&&\s*'(?P<when_true>[^']*)'"
        r"\s*\|\|\s*'(?P<when_false>[^']*)'\s*\}\}"
    )

    def resolve(match):
        left = match.group("when_true") if event_name == match.group("event") else ""
        return left if left else match.group("when_false")

    resolved, count = pattern.subn(resolve, group)
    if count != 1:
        raise AssertionError(f"expected exactly one event-conditional suffix, found {count}")
    return resolved


class ReleaseWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = yaml.safe_load(WORKFLOW_PATH.read_text(encoding="utf-8"))

    def steps(self, job: str) -> list[dict[str, object]]:
        return self.workflow["jobs"][job]["steps"]

    def named_step(self, job: str, name: str) -> dict[str, object]:
        return next(step for step in self.steps(job) if step.get("name") == name)

    def workflow_events(self) -> dict[str, object]:
        return self.workflow.get("on") or self.workflow[True]

    def run_preconditions(
        self,
        release_mode: str,
        release_status: str = "404",
    ) -> tuple[subprocess.CompletedProcess[str], list[list[str]]]:
        expected_sha = "1" * 40
        with tempfile.TemporaryDirectory() as directory:
            temp_dir = Path(directory)
            call_log = temp_dir / "calls.tsv"
            git_stub = temp_dir / "git"
            git_stub.write_text(
                textwrap.dedent(
                    """\
                    #!/usr/bin/env bash
                    set -euo pipefail
                    {
                      printf 'git'
                      printf '\\t%s' "$@"
                      printf '\\n'
                    } >> "$CALL_LOG"
                    if [ "${1:-}" = rev-parse ]; then
                      printf '%s\\n' "$EXPECTED_SHA"
                    fi
                    """
                ),
                encoding="utf-8",
            )
            gh_stub = temp_dir / "gh"
            gh_stub.write_text(
                textwrap.dedent(
                    """\
                    #!/usr/bin/env bash
                    set -euo pipefail
                    {
                      printf 'gh'
                      printf '\\t%s' "$@"
                      printf '\\n'
                    } >> "$CALL_LOG"
                    endpoint="${*: -1}"
                    case "$endpoint" in
                      repos/example/connex/immutable-releases)
                        printf '{"enabled":true}\\n'
                        ;;
                      repos/example/connex/releases/tags/v2.0.0)
                        case "$GH_RELEASE_STATUS" in
                          200)
                            printf 'HTTP/2.0 200 OK\\r\\n\\r\\n{}\\n'
                            ;;
                          403)
                            printf 'HTTP/2.0 403 Forbidden\\r\\n\\r\\n{"message":"Forbidden"}\\n'
                            printf 'request forbidden\\n' >&2
                            exit 1
                            ;;
                          404)
                            printf 'HTTP/2.0 404 Not Found\\r\\n\\r\\n{"message":"Not Found"}\\n'
                            printf 'HTTP/1.1 502 diagnostic-only\\n' >&2
                            exit 1
                            ;;
                          429)
                            printf 'HTTP/2.0 429 Too Many Requests\\r\\n\\r\\n{"message":"Rate limited"}\\n'
                            exit 1
                            ;;
                          500)
                            printf 'HTTP/2.0 500 Internal Server Error\\r\\n\\r\\n{"message":"Error"}\\n'
                            exit 1
                            ;;
                          redirect)
                            printf 'HTTP/2.0 301 Moved Permanently\\r\\nlocation: /redirected\\r\\n\\r\\n'
                            printf 'HTTP/2.0 404 Not Found\\r\\n\\r\\n{"message":"Not Found"}\\n'
                            exit 1
                            ;;
                          empty)
                            exit 1
                            ;;
                          502-fake-404)
                            printf 'HTTP/2.0 502 Bad Gateway\\r\\n\\r\\nupstream failed\\n'
                            printf 'HTTP/1.1 404 Not Found\\n'
                            exit 1
                            ;;
                          error)
                            printf 'transport error\\n' >&2
                            exit 1
                            ;;
                          *)
                            exit 2
                            ;;
                        esac
                        ;;
                      *)
                        exit 2
                        ;;
                    esac
                    """
                ),
                encoding="utf-8",
            )
            git_stub.chmod(0o755)
            gh_stub.chmod(0o755)
            result = subprocess.run(
                ["bash", str(PRECONDITIONS_PATH), expected_sha, "v2.0.0", release_mode],
                cwd=temp_dir,
                env={
                    **os.environ,
                    "CALL_LOG": str(call_log),
                    "CONNEX_RELEASE_ADMIN_TOKEN": "test-token",
                    "EXPECTED_SHA": expected_sha,
                    "GH_RELEASE_STATUS": release_status,
                    "GH_REPO": "example/connex",
                    "GITHUB_RUN_ATTEMPT": "2",
                    "GITHUB_RUN_ID": "1234",
                    "PATH": f"{temp_dir}:{os.environ['PATH']}",
                },
                text=True,
                capture_output=True,
                check=False,
            )
            calls = [line.split("\t") for line in call_log.read_text(encoding="utf-8").splitlines()]
        return result, calls

    def test_dry_runs_cannot_queue_ahead_of_a_publication(self) -> None:
        group = self.workflow["concurrency"]["group"]

        publish_group = evaluate_concurrency_group(group, "push")
        dry_run_group = evaluate_concurrency_group(group, "workflow_dispatch")

        self.assertEqual("release-${{ github.repository }}", publish_group)
        self.assertEqual("release-${{ github.repository }}-dry-run", dry_run_group)
        self.assertNotEqual(publish_group, dry_run_group)
        self.assertIs(False, self.workflow["concurrency"]["cancel-in-progress"])


    def test_dispatch_requires_version_and_job_output_derives_mode_from_event(self) -> None:
        events = self.workflow_events()
        self.assertEqual({"tags": ["v*.*.*"]}, events["push"])
        dispatch = events["workflow_dispatch"]
        self.assertEqual({"version"}, set(dispatch["inputs"]))
        self.assertEqual(
            {"required": True, "type": "string"},
            {
                key: dispatch["inputs"]["version"][key]
                for key in ("required", "type")
            },
        )

        metadata = self.named_step("metadata", "Resolve release event metadata")
        self.assertEqual("${{ inputs.version }}", metadata["env"]["REQUESTED_VERSION"])
        self.assertEqual(EVENT_MODE_EXPRESSION, metadata["env"]["DERIVED_RELEASE_MODE"])
        self.assertEqual(
            EVENT_MODE_EXPRESSION,
            self.workflow["jobs"]["metadata"]["outputs"]["release_mode"],
        )
        self.assertNotIn("release_mode=", metadata["run"])
        self.assertEqual(1, metadata["run"].count('test "$RELEASE_MODE" = "$DERIVED_RELEASE_MODE"'))
        self.assertIn("Unsupported release event", metadata["run"])
        preconditions = self.named_step(
            "metadata",
            "Verify release preconditions and immutable publication policy",
        )
        self.assertEqual(EVENT_MODE_EXPRESSION, preconditions["env"]["RELEASE_MODE"])
        for job_name, job in self.workflow["jobs"].items():
            if job_name == "metadata":
                continue
            self.assertIn("needs.metadata.outputs.release_mode", str(job), job_name)
            self.assertNotIn("github.event_name", str(job), job_name)
            self.assertNotIn("GITHUB_EVENT_NAME", str(job), job_name)

    def test_metadata_shell_mode_must_match_the_yaml_derived_mode(self) -> None:
        metadata = self.named_step("metadata", "Resolve release event metadata")
        mode_resolution = metadata["run"].split(
            'if [[ ! "$TAG_NAME" =~',
            maxsplit=1,
        )[0]
        matched = subprocess.run(
            ["bash", "-c", mode_resolution],
            env={
                "DERIVED_RELEASE_MODE": "dry-run",
                "GITHUB_EVENT_NAME": "workflow_dispatch",
                "GITHUB_REF_NAME": "main",
                "REQUESTED_VERSION": "v2.0.0",
            },
            text=True,
            capture_output=True,
            check=False,
        )
        drifted = subprocess.run(
            ["bash", "-c", mode_resolution],
            env={
                "DERIVED_RELEASE_MODE": "publish",
                "GITHUB_EVENT_NAME": "workflow_dispatch",
                "GITHUB_REF_NAME": "main",
                "REQUESTED_VERSION": "v2.0.0",
            },
            text=True,
            capture_output=True,
            check=False,
        )
        unsupported = subprocess.run(
            ["bash", "-c", mode_resolution],
            env={
                "DERIVED_RELEASE_MODE": "dry-run",
                "GITHUB_EVENT_NAME": "schedule",
                "GITHUB_REF_NAME": "main",
                "REQUESTED_VERSION": "v2.0.0",
            },
            text=True,
            capture_output=True,
            check=False,
        )

        self.assertEqual(0, matched.returncode, matched.stderr)
        self.assertNotEqual(0, drifted.returncode)
        self.assertNotEqual(0, unsupported.returncode)
        self.assertIn("Unsupported release event", unsupported.stdout)

    def test_every_publication_sink_has_an_effective_publish_only_condition(self) -> None:
        errors = publication_gate_errors(self.workflow)
        self.assertEqual([], errors, "\n".join(errors))

    def test_publication_sink_scanner_rejects_unconditional_helper_fixture(self) -> None:
        workflow = yaml.safe_load(WORKFLOW_PATH.read_text(encoding="utf-8"))
        workflow["jobs"]["candidate-images-dry-run"]["steps"].append(
            {
                "name": "Publish helper image",
                "run": (
                    "docker tag helper:local "
                    "ghcr.io/${{ github.repository_owner }}/connex-${{ matrix.component }}:helper\n"
                    "docker push "
                    "ghcr.io/${{ github.repository_owner }}/connex-${{ matrix.component }}:helper\n"
                ),
            }
        )
        self.assertEqual(
            [
                ".github/workflows/release.yml: job 'candidate-images-dry-run', "
                "step 'Publish helper image': CONFIRMED_SINK: publication sink lacks an effective "
                "publish-only condition: docker push"
            ],
            publication_gate_errors(workflow),
        )

    def test_publication_sink_scanner_rejects_dry_run_reusable_publisher_fixture(self) -> None:
        workflow = yaml.safe_load(WORKFLOW_PATH.read_text(encoding="utf-8"))
        workflow["jobs"]["helper-publisher"] = {
            "if": "needs.metadata.outputs.release_mode == 'dry-run'",
            "uses": "example/publish/.github/workflows/release.yml@sha",
        }
        self.assertEqual(
            [
                ".github/workflows/release.yml: job 'helper-publisher', step '<job-level uses>': "
                "CONFIRMED_SINK: publication sink lacks an effective publish-only condition: "
                "uses: example/publish/.github/workflows/release.yml@sha"
            ],
            publication_gate_errors(workflow),
        )

    def test_publication_sink_scanner_recognizes_every_supported_sink_class(self) -> None:
        run_fixtures = {
            "docker push ghcr.io/example/image:tag": "docker push",
            "docker buildx build --push .": "docker buildx ... --push",
            "docker buildx imagetools create image@sha256:abc": "docker buildx imagetools create",
            "cosign sign image@sha256:abc": "cosign sign",
            "cosign attest image@sha256:abc": "cosign attest",
            "cosign sign-blob manifest.json": "cosign sign-blob",
            "gh release create v2.0.0": "gh release create",
            "gh release upload v2.0.0 asset.tar": "gh release upload",
            "gh release edit v2.0.0 --draft=false": "gh release edit",
            "gh release delete v2.0.0 --yes": "gh release delete",
            "gh api --method DELETE repos/example/connex/releases/1": "gh api DELETE .../releases",
            "gh api repos/example/connex/packages/container/app/restore -f token=x": "gh api POST .../packages",
            "docker 'push' ghcr.io/example/image:tag": "docker push",
            "docker buildx build '--push' .": "docker buildx ... --push",
            "cosign 'sign' image@sha256:abc": "cosign sign",
            "gh release 'create' v2.0.0": "gh release create",
            "gh api --method 'DELETE' repos/example/connex/releases/1": "gh api DELETE .../releases",
            "gh api --method GET repos/example/connex && gh api --method DELETE repos/example/connex/releases/1": "gh api DELETE .../releases",
            "gh --repo example/connex release create v2.0.0": "gh release create",
            "docker buildx build \\\n  --push .": "docker buildx ... --push",
            "docker \\\n  push ghcr.io/example/image:tag": "docker push",
            "gh release \\\n  create v2.0.0": "gh release create",
            "gh api --method \\\n  DELETE repos/example/connex/releases/1": "gh api DELETE .../releases",
            "docker --config config.json push ghcr.io/example/image:tag": "docker push",
            "docker --context example buildx build --push .": "docker buildx ... --push",
            "cosign --verbose sign image@sha256:abc": "cosign sign",
            "endpoint='repos/example/connex/packages/container/app/restore'\ngh api --method POST \"$endpoint\"": (
                "gh api DYNAMIC (endpoint not statically classified)"
            ),
            "gh api --method \"$METHOD\" \"$endpoint\"": "gh api DYNAMIC (endpoint not statically classified)",
            "api_args=(--method DELETE)\ngh api \"${api_args[@]}\" repos/example/connex/releases/1": (
                "gh api DYNAMIC .../releases"
            ),
            "docker buildx build --output type=registry,name=ttl.sh/example/image:1h .": (
                "docker buildx --output type=registry"
            ),
            "docker buildx build --cache-to=type=registry,ref=ghcr.io/example/cache .": (
                "docker buildx --cache-to type=registry"
            ),
            "docker buildx build --output type=image,name=ghcr.io/example/image:tag,push=true .": (
                "docker buildx --output type=image with push"
            ),
            "OUTPUT_DESTINATION=type=registry,name=ttl.sh/example/image:1h\n"
            "docker buildx build --output \"$OUTPUT_DESTINATION\" .": (
                "docker buildx --output dynamic destination"
            ),
        }
        for run, expected_sink in run_fixtures.items():
            with self.subTest(run=run):
                self.assertIn((CONFIRMED_SINK, expected_sink), publication_sinks({"run": run}))

        self.assertEqual(
            [],
            publication_sinks({"run": "docker run --rm alpine printf '%s\\n' push"}),
        )

        self.assertEqual(
            [],
            publication_sinks(
                {
                    "uses": "docker/build-push-action@sha",
                    "with": {"push": BUILD_PUSH_MODE_INPUT},
                }
            ),
        )
        self.assertTrue(
            publication_sinks(
                {
                    "uses": "docker/build-push-action@sha",
                    "with": {"push": True},
                }
            )
        )
        self.assertTrue(
            publication_sinks(
                {
                    "uses": "docker/build-push-action@sha",
                    "with": {
                        "push": BUILD_PUSH_MODE_INPUT,
                        "outputs": "${{ env.OUTPUT_DESTINATION }}",
                    },
                }
            )
        )
        self.assertTrue(
            publication_sinks(
                {
                    "uses": "docker/build-push-action@sha",
                    "with": {
                        "push": BUILD_PUSH_MODE_INPUT,
                        "outputs": "type=registry,name=ttl.sh/example/image:1h",
                    },
                }
            )
        )
        self.assertTrue(
            publication_sinks(
                {
                    "uses": "docker/build-push-action@sha",
                    "with": {
                        "push": BUILD_PUSH_MODE_INPUT,
                        "outputs": "type=image,name=ghcr.io/example/image:tag,push-by-digest=true",
                    },
                }
            )
        )
        for action in ("actions/attest@sha", "example/publish-package-action@sha"):
            with self.subTest(action=action):
                self.assertEqual(
                    [(CONFIRMED_SINK, f"uses: {action}")],
                    publication_sinks({"uses": action}),
                )
        self.assertEqual(
            [(UNREVIEWED, "uses: actions/setup-python@sha")],
            publication_sinks({"uses": "actions/setup-python@sha"}),
        )
        self.assertEqual(
            [(UNREVIEWED, "uses: example/build-action@sha")],
            publication_sinks(
                {
                    "uses": "example/build-action@sha",
                    "with": {"push": False},
                }
            ),
        )
        unreviewed_workflow = {
            "jobs": {
                "test": {
                    "runs-on": "ubuntu-latest",
                    "steps": [{"uses": "actions/setup-python@sha"}],
                }
            }
        }
        self.assertEqual(
            [
                ".github/workflows/release.yml: job 'test', step 'step 1': UNREVIEWED: "
                "audit this action before classifying it as nonpublishing: "
                "uses: actions/setup-python@sha"
            ],
            publication_gate_errors(unreviewed_workflow),
        )
        self.assertTrue(
            publication_sinks(
                {
                    "uses": "example/publish-build-action@sha",
                    "with": {"push": BUILD_PUSH_MODE_INPUT},
                }
            )
        )

        self.assertTrue(effective_publish_condition(PUBLISH_MODE_CONDITION))
        self.assertTrue(
            effective_publish_condition(
                f"${{{{ always() && {PUBLISH_MODE_CONDITION} && (success() || failure()) }}}}"
            )
        )
        self.assertFalse(effective_publish_condition(f"${{{{ {PUBLISH_MODE_CONDITION} || success() }}}}"))

    def test_publish_build_is_pristine_and_dry_run_build_cannot_push(self) -> None:
        publish_build = self.named_step("candidate-images", "Build and push candidate")
        self.assertIs(True, publish_build["with"]["push"])
        self.assertEqual("mode=max", publish_build["with"]["provenance"])
        self.assertNotIn("load", publish_build["with"])
        self.assertNotIn("release_mode", str(publish_build))

        dry_run_build = self.named_step("candidate-images-dry-run", "Build dry-run candidate")
        self.assertIs(False, dry_run_build["with"]["push"])
        self.assertIs(True, dry_run_build["with"]["load"])
        self.assertIs(False, dry_run_build["with"]["provenance"])

    def test_dry_run_candidate_declares_only_contents_read(self) -> None:
        dry_run = self.workflow["jobs"]["candidate-images-dry-run"]

        self.assertEqual({"contents": "read"}, dry_run["permissions"])
        self.assertEqual(DRY_RUN_MODE_CONDITION, dry_run["if"])
        serialized = str(dry_run)
        for forbidden in (
            "actions/attest",
            "cosign",
            "docker/login-action",
            "push-to-registry",
            "secrets.",
        ):
            self.assertNotIn(forbidden, serialized)

    def test_release_transaction_receives_provenance_for_every_signed_container(self) -> None:
        """Derives the requirement from the signing loop so a new component cannot skip it.

        The transaction requires .provenance.runtime.containers.<component>.imageReference for
        each component it iterates. A component that ships in the bundle but is never exported to
        the benchmark, or never passed to the report verifier, compares against null and fails
        every real release after the candidate images have already been published. Nothing in the
        pull-request pipeline exercises that path.
        """
        scripts = "\n".join(
            str(step.get("run", ""))
            for job in self.workflow["jobs"].values()
            for step in job.get("steps", [])
        )
        signed: set[str] = set()
        for listed in re.findall(r"for component in ([a-z0-9 ]+); do", scripts):
            signed.update(listed.split())

        self.assertIn("clamav", signed)
        for component in sorted(signed):
            with self.subTest(component=component):
                self.assertIn(
                    f'CONNEX_BENCHMARK_{component.upper()}_CONTAINER="$', scripts
                )
                self.assertIn(f"--{component}-image-reference ", scripts)

    def test_publish_only_jobs_do_not_depend_on_the_dry_run_build(self) -> None:
        jobs = self.workflow["jobs"]

        self.assertTrue(effective_publish_condition(jobs["candidate-images"]["if"]))
        self.assertEqual(["metadata", "candidate-images"], jobs["release-set-smoke"]["needs"])
        self.assertEqual(
            ["metadata", "candidate-images", "release-set-smoke"],
            jobs["promote"]["needs"],
        )
        self.assertEqual(["metadata", "promote"], jobs["release"]["needs"])
        self.assertIn("always()", jobs["promote"]["if"])
        self.assertTrue(effective_publish_condition(jobs["promote"]["if"]))
        for job_name in ("release-set-smoke", "promote", "release"):
            self.assertNotIn("candidate-images-dry-run", str(jobs[job_name]), job_name)

    def test_dry_run_keeps_required_nonpublishing_verification(self) -> None:
        metadata_names = [step.get("name") for step in self.steps("metadata")]
        for name in (
            "Verify release preconditions and immutable publication policy",
            "Require release commit to remain the main head",
            "Require successful trusted workflows for the release commit",
        ):
            self.assertIn(name, metadata_names)

        candidate = self.workflow["jobs"]["candidate-images-dry-run"]
        self.assertEqual(
            ["backend", "frontend", "ocr", "clamav"],
            candidate["strategy"]["matrix"]["component"],
        )
        build = self.named_step("candidate-images-dry-run", "Build dry-run candidate")
        self.assertEqual(
            "VERSION=${{ needs.metadata.outputs.version }}\n"
            "BUILD_TIME=${{ needs.metadata.outputs.build_time }}\n"
            "SOURCE_DATE_EPOCH=${{ needs.metadata.outputs.build_epoch }}\n",
            build["with"]["build-args"],
        )
        for name in ("Smoke dry-run OCR candidate", "Smoke dry-run frontend candidate"):
            self.assertNotIn("release_mode", self.named_step("candidate-images-dry-run", name)["if"])
        scan = self.named_step(
            "candidate-images-dry-run",
            "Scan dry-run candidate for high-severity vulnerabilities",
        )
        self.assertEqual("high", scan["with"]["severity-cutoff"])
        self.assertTrue(scan["with"]["fail-build"])
        sbom = self.named_step("candidate-images-dry-run", "Generate dry-run SBOM")
        self.assertNotIn("if", sbom)
        self.assertEqual("spdx-json", sbom["with"]["format"])

    def test_publish_job_body_is_unchanged_from_the_prepublication_pipeline(self) -> None:
        publish = self.workflow["jobs"]["candidate-images"]

        self.assertNotIn("release_mode", str(publish["steps"]))
        self.assertEqual(
            "${{ steps.digest.outputs.digest }}",
            self.named_step("candidate-images", "Sign candidate digest")["env"]["DIGEST"],
        )
        for name in ("Smoke exact OCR candidate", "Smoke exact frontend candidate"):
            step = self.named_step("candidate-images", name)
            self.assertEqual("${{ steps.digest.outputs.digest }}", step["env"]["DIGEST"])
            self.assertIn('"${IMAGE}@${DIGEST}"', step["run"])
        for name in (
            "Scan exact candidate for high-severity vulnerabilities",
            "Generate SBOM",
        ):
            self.assertIn(
                "@${{ steps.digest.outputs.digest }}",
                self.named_step("candidate-images", name)["with"]["image"],
            )

    def test_dry_run_smoke_scan_and_sbom_share_the_local_tag(self) -> None:
        local_tag = "connex-${{ matrix.component }}:dry-run-${{ github.run_id }}-${{ github.run_attempt }}"
        for name in ("Smoke dry-run OCR candidate", "Smoke dry-run frontend candidate"):
            step = self.named_step("candidate-images-dry-run", name)
            self.assertEqual(local_tag, step["env"]["IMAGE_REF"])
            self.assertIn('"$IMAGE_REF"', step["run"])
        for name in (
            "Scan dry-run candidate for high-severity vulnerabilities",
            "Generate dry-run SBOM",
        ):
            self.assertEqual(
                local_tag,
                self.named_step("candidate-images-dry-run", name)["with"]["image"],
            )

    def test_dry_run_summary_discloses_covered_and_uncovered_guarantees(self) -> None:
        summary_job = self.workflow["jobs"]["dry-run-summary"]
        self.assertIn(DRY_RUN_MODE_CONDITION, summary_job["if"])
        self.assertEqual(["metadata", "candidate-images-dry-run"], summary_job["needs"])
        summary = self.named_step("dry-run-summary", "Record dry-run coverage")["run"]
        for covered in (
            "Strict release-version validation",
            "publication policy and the requested version being unpublished",
            "Current main-head requirement",
            "ci.yml, security.yml, and deploy-smoke.yml",
            "image builds with release build arguments",
            "Frontend, OCR, and ClamAV image smoke tests",
            "High-severity Anchore scans",
            "SBOM generation",
        ):
            self.assertIn(covered, summary)
        for uncovered in (
            "Release-tag existence and its binding to this commit",
            "Candidate image descriptors and the workflow-artifact handoff",
            "GHCR push and registry round-trip",
            "GitHub artifact attestation",
            "actions/attest",
            "Keyless image signing",
            "SBOM attestation",
            "release-manifest signing",
            "Exact-digest release-set boot",
            "release-tag promotion",
            "GitHub Release",
        ):
            self.assertIn(uncovered, summary)
        self.assertIn("did not publish a release or modify release state", summary)
        self.assertIn('if [ "$METADATA_RESULT" != success ] || [ "$CANDIDATE_RESULT" != success ]; then', summary)

    def test_transaction_recovery_precedes_moving_head_and_ci_checks(self) -> None:
        names = [step.get("name") for step in self.steps("metadata")]
        recovery = names.index("Detect a committed release transaction from an earlier attempt")
        moving_head = names.index("Require release commit to remain the main head")
        trusted_ci = names.index("Require successful trusted workflows for the release commit")

        self.assertLess(recovery, moving_head)
        self.assertLess(recovery, trusted_ci)
        self.assertEqual(
            "steps.transaction.outputs.exists != 'true'",
            self.named_step("metadata", "Require release commit to remain the main head")["if"],
        )
        self.assertEqual(
            "steps.transaction.outputs.exists != 'true'",
            self.named_step("metadata", "Require successful trusted workflows for the release commit")["if"],
        )

    def test_buildkit_check_matches_the_current_inspection_field(self) -> None:
        checks = [
            step["run"]
            for job in ("candidate-images", "candidate-images-dry-run", "promote")
            for step in self.steps(job)
            if step.get("name") == "Verify pinned build toolchain"
        ]

        self.assertEqual(3, len(checks))
        self.assertTrue(all("BuildKit version: v0.31.1" in check for check in checks))

    def test_transaction_and_candidates_are_attempt_scoped(self) -> None:
        upload = self.named_step("release-set-smoke", "Upload committed release transaction")
        self.assertEqual(
            "release-transaction-${{ github.run_id }}-${{ github.run_attempt }}",
            upload["with"]["name"],
        )
        candidate_check = self.named_step("release-set-smoke", "Require complete candidates from this attempt")
        self.assertIn("${GITHUB_RUN_ATTEMPT}", candidate_check["run"])
        self.assertIn("Re-run all jobs", candidate_check["run"])

    def test_promotion_reresolves_transaction_for_failed_job_retries(self) -> None:
        resolver = self.named_step("promote", "Resolve the committed release transaction")
        promote_download = next(
            step for step in self.steps("promote") if step.get("uses", "").startswith("actions/download-artifact@")
        )
        release_download = next(
            step for step in self.steps("release") if step.get("uses", "").startswith("actions/download-artifact@")
        )

        self.assertIn("release-transaction-${GITHUB_RUN_ID}-", resolver["run"])
        self.assertEqual("${{ steps.transaction.outputs.name }}", promote_download["with"]["name"])
        self.assertEqual("${{ needs.promote.outputs.transaction_name }}", release_download["with"]["name"])

    def test_publication_has_explicit_repository_and_exact_assets(self) -> None:
        publish = self.named_step("release", "Publish the complete verified release atomically")
        self.assertEqual("${{ github.repository }}", publish["env"]["GH_REPO"])
        self.assertIn("diff -u /tmp/expected-release-assets /tmp/actual-release-assets", publish["run"])
        self.assertIn("gh release verify", publish["run"])
        self.assertEqual(
            4,
            WORKFLOW_PATH.read_text(encoding="utf-8").count("verify-release-preconditions.sh"),
        )

    def test_core_and_prerelease_tags_preserve_exact_artifact_identity(self) -> None:
        accepted = ("v0.9.0", "v0.9.0-tc.1", "v1.0.0", "v0.9.0-rc.2")
        rejected = ("v0.9", "v0.9.0.1", "v0.9.0-", "banana", "v01.9.0", "v0.9.0-rc.02")
        for tag in accepted:
            result = subprocess.run(
                ["bash", "-c", f'[[ "$1" =~ {SEMVER_TAG_PATTERN} ]]', "_", tag],
                check=False,
            )
            self.assertEqual(0, result.returncode, tag)
        for tag in rejected:
            result = subprocess.run(
                ["bash", "-c", f'[[ "$1" =~ {SEMVER_TAG_PATTERN} ]]', "_", tag],
                check=False,
            )
            self.assertNotEqual(0, result.returncode, tag)

        workflow_source = WORKFLOW_PATH.read_text(encoding="utf-8")
        preconditions_source = PRECONDITIONS_PATH.read_text(encoding="utf-8")
        self.assertIn(f'if [[ ! "$TAG_NAME" =~ {SEMVER_TAG_PATTERN} ]]; then', workflow_source)
        self.assertIn(f'[[ "$TAG_NAME" =~ {SEMVER_TAG_PATTERN} ]]', preconditions_source)

        metadata = self.named_step("metadata", "Resolve release event metadata")
        event_validation = metadata["run"].split('if [ "$RELEASE_MODE" = publish ]; then', maxsplit=1)[0]
        for event_name, ref_name, requested_version, expected_mode in (
            ("push", "v0.9.0", "", "publish"),
            ("workflow_dispatch", "main", "v0.9.0", "dry-run"),
            ("push", "v0.9.0-tc.1", "", "publish"),
            ("workflow_dispatch", "main", "v0.9.0-tc.1", "dry-run"),
            ("workflow_dispatch", "main", "v2.0.0", "dry-run"),
        ):
            result = subprocess.run(
                [
                    "bash",
                    "-c",
                    f'{event_validation}\nprintf "%s|%s|%s" "$VERSION" "$RELEASE_MODE" "$TAG_NAME"',
                    "_",
                ],
                env={
                    "DERIVED_RELEASE_MODE": expected_mode,
                    "GITHUB_EVENT_NAME": event_name,
                    "GITHUB_REF_NAME": ref_name,
                    "REQUESTED_VERSION": requested_version,
                },
                text=True,
                capture_output=True,
                check=True,
            )
            expected_tag = ref_name if event_name == "push" else requested_version
            self.assertEqual(f"{expected_tag.removeprefix('v')}|{expected_mode}|{expected_tag}", result.stdout)
        for event_name in ("push", "workflow_dispatch"):
            for tag in rejected:
                result = subprocess.run(
                    ["bash", "-c", event_validation],
                    env={
                        "DERIVED_RELEASE_MODE": "publish" if event_name == "push" else "dry-run",
                        "GITHUB_EVENT_NAME": event_name,
                        "GITHUB_REF_NAME": tag if event_name == "push" else "main",
                        "REQUESTED_VERSION": tag if event_name == "workflow_dispatch" else "",
                    },
                    text=True,
                    capture_output=True,
                    check=False,
                )
                self.assertNotEqual(0, result.returncode, f"{event_name}: {tag}")

    def test_event_metadata_enforces_container_tag_length_boundary(self) -> None:
        metadata = self.named_step("metadata", "Resolve release event metadata")
        validation = metadata["run"].split('if [ "$RELEASE_MODE" = publish ]; then', maxsplit=1)[0]

        for length, expected_returncode in ((128, 0), (129, 1)):
            version = f"0.0.0-{'a' * (length - len('0.0.0-'))}"
            for event_name, ref_name, requested_version in (
                ("push", f"v{version}", ""),
                ("workflow_dispatch", "main", f"v{version}"),
            ):
                result = subprocess.run(
                    ["bash", "-c", validation],
                    env={
                        "DERIVED_RELEASE_MODE": "publish" if event_name == "push" else "dry-run",
                        "GITHUB_EVENT_NAME": event_name,
                        "GITHUB_REF_NAME": ref_name,
                        "REQUESTED_VERSION": requested_version,
                    },
                    text=True,
                    capture_output=True,
                    check=False,
                )
                self.assertEqual(expected_returncode, result.returncode, result.stderr)

    def test_revalidation_enforces_container_tag_length_boundary(self) -> None:
        validation = PRECONDITIONS_PATH.read_text(encoding="utf-8").split(
            'if [ "$RELEASE_MODE" = publish ]; then', maxsplit=1
        )[0]

        for length, expected_returncode in ((128, 0), (129, 1)):
            version = f"0.0.0-{'a' * (length - len('0.0.0-'))}"
            result = subprocess.run(
                ["bash", "-c", validation, "_", "0" * 40, f"v{version}", "dry-run"],
                env={
                    "CONNEX_RELEASE_ADMIN_TOKEN": "test-token",
                    "GH_REPO": "example/connex",
                },
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(expected_returncode, result.returncode, result.stderr)

    def test_preconditions_step_is_reachable_in_both_modes(self) -> None:
        self.assertNotIn("if", self.workflow["jobs"]["metadata"])
        preconditions_step = self.named_step(
            "metadata",
            "Verify release preconditions and immutable publication policy",
        )
        self.assertNotIn("if", preconditions_step)
        self.assertEqual(
            'bash .github/scripts/verify-release-preconditions.sh "$GITHUB_SHA" "$TAG_NAME" "$RELEASE_MODE"',
            preconditions_step["run"],
        )
        workflow_source = WORKFLOW_PATH.read_text(encoding="utf-8")
        invocations = [
            line.strip()
            for line in workflow_source.splitlines()
            if "verify-release-preconditions.sh" in line
        ]
        self.assertEqual(4, len(invocations))
        self.assertTrue(all(line.endswith('"$RELEASE_MODE"') for line in invocations))

    def test_preconditions_execute_policy_and_mode_specific_tag_checks(self) -> None:
        immutable_call = [
            "gh",
            "api",
            "-H",
            "Accept: application/vnd.github+json",
            "-H",
            "X-GitHub-Api-Version: 2026-03-10",
            "repos/example/connex/immutable-releases",
        ]
        release_call = [
            "gh",
            "api",
            "--include",
            "-H",
            "Accept: application/vnd.github+json",
            "-H",
            "X-GitHub-Api-Version: 2026-03-10",
            "repos/example/connex/releases/tags/v2.0.0",
        ]

        dry_run, dry_run_calls = self.run_preconditions("dry-run")
        self.assertEqual(0, dry_run.returncode, dry_run.stderr)
        self.assertIn(immutable_call, dry_run_calls)
        self.assertIn(release_call, dry_run_calls)
        self.assertFalse(any(call[:2] == ["git", "fetch"] for call in dry_run_calls), dry_run_calls)

        publish, publish_calls = self.run_preconditions("publish")
        self.assertEqual(0, publish.returncode, publish.stderr)
        self.assertIn(immutable_call, publish_calls)
        self.assertIn(
            [
                "git",
                "fetch",
                "--force",
                "--no-tags",
                "origin",
                "+refs/tags/v2.0.0:refs/connex-release/1234-2",
            ],
            publish_calls,
        )
        self.assertNotIn(release_call, publish_calls)

    def test_dry_run_accepts_only_confirmed_missing_release(self) -> None:
        confirmed_missing, calls = self.run_preconditions("dry-run", "404")
        self.assertEqual(0, confirmed_missing.returncode, confirmed_missing.stderr)
        self.assertTrue(any(call[-1] == "repos/example/connex/releases/tags/v2.0.0" for call in calls))

        for status in (
            "200",
            "403",
            "429",
            "500",
            "redirect",
            "empty",
            "502-fake-404",
            "error",
        ):
            with self.subTest(status=status):
                result, status_calls = self.run_preconditions("dry-run", status)
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertTrue(
                    any(call[-1] == "repos/example/connex/releases/tags/v2.0.0" for call in status_calls),
                    status_calls,
                )
                if status in {"redirect", "502-fake-404"}:
                    self.assertIn("expected exactly one HTTP status line, found 2", result.stdout)
                if status in {"empty", "error"}:
                    self.assertIn("expected exactly one HTTP status line, found 0", result.stdout)

    def test_prereleases_are_explicitly_excluded_from_latest(self) -> None:
        publish = self.named_step("release", "Publish the complete verified release atomically")
        flag_setup = publish["run"].split("assets=(", maxsplit=1)[0]
        stable = subprocess.run(
            ["bash", "-c", f'{flag_setup}\nprintf "%s\\n" "${{prerelease_flags[@]}}"', "_"],
            env={"VERSION": "0.9.0"},
            text=True,
            capture_output=True,
            check=True,
        )
        prerelease = subprocess.run(
            ["bash", "-c", f'{flag_setup}\nprintf "%s\\n" "${{prerelease_flags[@]}}"', "_"],
            env={"VERSION": "0.9.0-tc.1"},
            text=True,
            capture_output=True,
            check=True,
        )
        self.assertEqual("\n", stable.stdout)
        self.assertEqual("--prerelease\n--latest=false\n", prerelease.stdout)
        self.assertIn('"${prerelease_flags[@]}" \\\n    --notes', publish["run"])
        self.assertIn('gh release edit "$GITHUB_REF_NAME" --draft=false "${prerelease_flags[@]}"', publish["run"])
        self.assertNotIn(":latest", WORKFLOW_PATH.read_text(encoding="utf-8"))

    def test_qualification_and_sbom_are_recomputed_and_bound(self) -> None:
        workflow_source = WORKFLOW_PATH.read_text(encoding="utf-8")
        self.assertEqual(2, workflow_source.count("benchmark/verify_report.py"))
        expected_argument_counts = {
            "--base-url": 2,
            "--requests-per-minute": 3,
            "--backend-image-reference": 2,
            "--frontend-image-reference": 2,
            "--ocr-image-reference": 2,
        }
        for argument, expected_count in expected_argument_counts.items():
            self.assertEqual(expected_count, workflow_source.count(argument))
        self.assertIn("verify-attested-sbom.py", workflow_source)
        self.assertNotIn("all(. == true)", workflow_source)
        self.assertNotIn("CONNEX_*_IMAGE", DEPLOYMENT_PATH.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
