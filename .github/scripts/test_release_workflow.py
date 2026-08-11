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
MODE_SPOOF_FIXTURE = 'if [ "$REQUESTED_VERSION" = v2.0.0 ]; then RELEASE_MODE=publish; fi'
ALTERNATE_MODE_SPOOF_FIXTURE = (
    'if [ "$REQUESTED_VERSION" = v2.0.0 ]; then for RELEASE_MODE in publish; do :; done; fi'
)
QUOTED_MODE_SPOOF_FIXTURE = (
    'if [ "$REQUESTED_VERSION" = v2.0.0 ]; then printf -v RELEASE"_MODE" %s publish; fi'
)
RELEASE_OR_PACKAGE_ENDPOINT_PATTERN = re.compile(r"(?:^|/)(releases|packages)(?:/|\b)", re.IGNORECASE)
MODE_ASSIGNMENT_PATTERN = re.compile(r"(?<![A-Za-z0-9_])RELEASE_MODE\s*\+?=")
NONPUBLISHING_ACTIONS = {
    "actions/checkout",
    "anchore/scan-action",
    "docker/setup-buildx-action",
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


def publication_sinks(step: dict[str, object]) -> list[str]:
    sinks: list[str] = []
    run = step.get("run")
    if isinstance(run, str):
        for command in shell_commands(run):
            for index, token in enumerate(command):
                if token == "docker":
                    docker_arguments = command[index + 1:]
                    if "push" in docker_arguments:
                        sinks.append("docker push")
                    if "buildx" in docker_arguments:
                        buildx_index = docker_arguments.index("buildx")
                        buildx_arguments = docker_arguments[buildx_index + 1:]
                        push_index = next(
                            (
                                candidate_index
                                for candidate_index in range(buildx_index + 1, len(docker_arguments))
                                if docker_arguments[candidate_index] == "--push"
                                or docker_arguments[candidate_index].startswith("--push=")
                            ),
                            None,
                        )
                        if push_index is not None:
                            sinks.append("docker buildx ... --push")
                        sinks.extend(buildx_registry_exports(buildx_arguments))
                    if "imagetools" in docker_arguments:
                        imagetools_index = docker_arguments.index("imagetools")
                        if docker_arguments[imagetools_index + 1:imagetools_index + 2] == ["create"]:
                            sinks.append("docker buildx imagetools create")
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
                        sinks.append("buildx ... --push")
                    sinks.extend(buildx_registry_exports(buildx_arguments))
                if (
                    token == "imagetools"
                    and "docker" not in command[:index]
                    and command[index + 1:index + 2] == ["create"]
                ):
                    sinks.append("imagetools create")
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
                        sinks.append(f"cosign {operation}")
                if token != "gh":
                    continue
                next_gh = command.index("gh", index + 1) if "gh" in command[index + 1:] else len(command)
                gh_arguments = command[index + 1:next_gh]
                for subcommand_index, subcommand in enumerate(gh_arguments):
                    if subcommand == "release" and gh_arguments[subcommand_index + 1:subcommand_index + 2]:
                        operation = gh_arguments[subcommand_index + 1]
                        if operation in {"create", "upload", "edit", "delete"}:
                            sinks.append(f"gh release {operation}")
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
                            sinks.append(f"gh api {method} .../{endpoint.group(1).lower()}")
                        else:
                            sinks.append(f"gh api {method} (endpoint not statically classified)")

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
        sinks.append(f"uses: {uses}")
    for input_name in ("outputs", "cache-to"):
        input_value = action_inputs.get(input_name)
        if isinstance(input_value, str):
            destination = buildx_export_destination(input_value)
            if destination:
                sinks.append(f"uses: {uses} with {input_name}: {destination}")
    if "push" in action_inputs and {"build", "bake"} & action_tokens:
        if action_inputs["push"] != BUILD_PUSH_MODE_INPUT:
            sinks.append(f"uses: {uses} with push: {action_inputs['push']}")
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
        sinks.append(f"uses: {uses}")
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
            for sink in job_sinks:
                errors.append(
                    ".github/workflows/release.yml: "
                    f"job '{job_name}', step '<job-level uses>': publication sink lacks an effective "
                    f"publish-only condition: {sink}"
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
            for sink in sinks:
                errors.append(
                    ".github/workflows/release.yml: "
                    f"job '{job_name}', step '{step_name}': publication sink lacks an effective "
                    f"publish-only condition: {sink}"
                )
    return errors


def metadata_mode_errors(script: str) -> list[str]:
    errors: list[str] = []
    case_start = re.search(r'^\s*case\s+"\$GITHUB_EVENT_NAME"\s+in\s*$', script, re.MULTILINE)
    if not case_start:
        return ['metadata script must contain case "$GITHUB_EVENT_NAME" in']
    esac = re.search(r"^\s*esac\s*$", script[case_start.end():], re.MULTILINE)
    if not esac:
        return ["metadata event case must end with esac"]
    case_end = case_start.end() + esac.end()
    assignments = list(MODE_ASSIGNMENT_PATTERN.finditer(script))
    if len(assignments) != 2:
        errors.append(f"RELEASE_MODE must be assigned exactly twice; found {len(assignments)} assignments")
    outside_case = [
        assignment.group()
        for assignment in assignments
        if not case_start.end() <= assignment.start() < case_end
    ]
    if outside_case:
        errors.append(f"RELEASE_MODE assignments must stay inside the event case; found {outside_case}")

    before_case = script[:case_start.start()]
    if "RELEASE_MODE" in before_case:
        errors.append("RELEASE_MODE cannot be referenced before the event case")
    event_source_lines = [
        line.strip()
        for line in script.splitlines()
        if "GITHUB_EVENT_NAME" in line or "GITHUB_REF_NAME" in line
    ]
    expected_event_source_lines = [
        'case "$GITHUB_EVENT_NAME" in',
        'TAG_NAME="$GITHUB_REF_NAME"',
        'echo "::error::Unsupported release event: ${GITHUB_EVENT_NAME}"',
    ]
    if event_source_lines != expected_event_source_lines:
        errors.append(
            "GitHub event source variables may only be read by the exact event case; "
            f"found {event_source_lines}"
        )
    case_lines = [line.strip() for line in script[case_start.end():case_end].splitlines() if "RELEASE_MODE" in line]
    expected_case_lines = ["RELEASE_MODE=publish", "RELEASE_MODE=dry-run"]
    if case_lines != expected_case_lines:
        errors.append(
            f"RELEASE_MODE has unexpected assignment-capable uses inside the event case; found {case_lines}"
        )

    after_esac = script[case_end:]
    after_esac_lines = [
        line.strip()
        for line in after_esac.splitlines()
        if "RELEASE_MODE" in line
    ]
    expected_after_esac_lines = [
        'if [ "$RELEASE_MODE" = publish ]; then',
        'echo "release_mode=$RELEASE_MODE"',
    ]
    if after_esac_lines != expected_after_esac_lines:
        errors.append(
            "RELEASE_MODE cannot be reassigned, read, or substituted after esac outside its exact "
            f"validated reads; found {after_esac_lines}"
        )
    release_mode_outputs = [line.strip() for line in script.splitlines() if "release_mode=" in line]
    if release_mode_outputs != ['echo "release_mode=$RELEASE_MODE"']:
        errors.append(
            "release_mode output must be written exactly once from RELEASE_MODE; "
            f"found {release_mode_outputs}"
        )
    tokenized_mode_commands = [
        command
        for command in shell_commands(after_esac)
        if any("RELEASE_MODE" in token for token in command)
    ]
    expected_mode_commands = [
        ["if", "[", "$RELEASE_MODE", "=", "publish", "]"],
        ["echo", "release_mode=$RELEASE_MODE"],
    ]
    if tokenized_mode_commands != expected_mode_commands:
        errors.append(
            "RELEASE_MODE token uses after esac must match the exact validated reads; "
            f"found {tokenized_mode_commands}"
        )
    dynamic_shell_commands = [
        command
        for command in shell_commands(script)
        if any(token in {"eval", "source"} for token in command)
    ]
    if dynamic_shell_commands:
        errors.append(f"metadata mode derivation cannot use dynamic shell evaluation; found {dynamic_shell_commands}")
    return errors


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
                          404)
                            printf 'HTTP/2.0 404 Not Found\\r\\n\\r\\n{"message":"Not Found"}\\n'
                            exit 1
                            ;;
                          500)
                            printf 'HTTP/2.0 500 Internal Server Error\\r\\n\\r\\n{"message":"Error"}\\n'
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

        self.assertIn("github.event_name == 'push'", group)
        self.assertIn("-dry-run", group)
        self.assertEqual(
            "release-${{ github.repository }}",
            group.replace(
                "${{ github.event_name == 'push' && '' || '-dry-run' }}", ""
            ),
        )
        self.assertIs(False, self.workflow["concurrency"]["cancel-in-progress"])

    def test_dispatch_requires_version_and_metadata_is_the_only_mode_derivation(self) -> None:
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
        metadata_errors = metadata_mode_errors(metadata["run"])
        self.assertEqual([], metadata_errors, "\n".join(metadata_errors))
        self.assertEqual(
            "${{ steps.metadata.outputs.release_mode }}",
            self.workflow["jobs"]["metadata"]["outputs"]["release_mode"],
        )
        for job_name, job in self.workflow["jobs"].items():
            if job_name == "metadata":
                continue
            self.assertIn("needs.metadata.outputs.release_mode", str(job), job_name)
            self.assertNotIn("github.event_name", str(job), job_name)
            self.assertNotIn("GITHUB_EVENT_NAME", str(job), job_name)

    def test_metadata_mode_invariant_rejects_version_input_spoof_fixture(self) -> None:
        metadata = self.named_step("metadata", "Resolve release event metadata")
        mutated = metadata["run"].replace("esac\n", f"esac\n{MODE_SPOOF_FIXTURE}\n", 1)
        errors = metadata_mode_errors(mutated)
        self.assertTrue(any("assigned exactly twice; found 3" in error for error in errors), errors)
        self.assertTrue(any("after esac" in error for error in errors), errors)

        alternate_mutated = metadata["run"].replace(
            "esac\n",
            f"esac\n{ALTERNATE_MODE_SPOOF_FIXTURE}\n",
            1,
        )
        alternate_errors = metadata_mode_errors(alternate_mutated)
        self.assertTrue(any("after esac" in error for error in alternate_errors), alternate_errors)

        quoted_mutated = metadata["run"].replace(
            "esac\n",
            f"esac\n{QUOTED_MODE_SPOOF_FIXTURE}\n",
            1,
        )
        quoted_errors = metadata_mode_errors(quoted_mutated)
        self.assertTrue(any("token uses after esac" in error for error in quoted_errors), quoted_errors)

        event_spoof_mutated = metadata["run"].replace(
            'case "$GITHUB_EVENT_NAME" in\n',
            'if [ "$REQUESTED_VERSION" = v2.0.0 ]; then\n'
            '  GITHUB_EVENT_NAME=push\n'
            '  GITHUB_REF_NAME="$REQUESTED_VERSION"\n'
            'fi\n'
            'case "$GITHUB_EVENT_NAME" in\n',
            1,
        )
        event_spoof_errors = metadata_mode_errors(event_spoof_mutated)
        self.assertTrue(any("event source variables" in error for error in event_spoof_errors), event_spoof_errors)

    def test_every_publication_sink_has_an_effective_publish_only_condition(self) -> None:
        errors = publication_gate_errors(self.workflow)
        self.assertEqual([], errors, "\n".join(errors))

    def test_publication_sink_scanner_rejects_unconditional_helper_fixture(self) -> None:
        workflow = yaml.safe_load(WORKFLOW_PATH.read_text(encoding="utf-8"))
        workflow["jobs"]["candidate-images"]["steps"].append(
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
                ".github/workflows/release.yml: job 'candidate-images', step 'Publish helper image': "
                "publication sink lacks an effective publish-only condition: docker push"
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
                "publication sink lacks an effective publish-only condition: "
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
                self.assertIn(expected_sink, publication_sinks({"run": run}))

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
                self.assertEqual([f"uses: {action}"], publication_sinks({"uses": action}))
        self.assertTrue(publication_sinks({"uses": "peaceiris/actions-gh-pages@sha"}))
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

    def test_build_mode_inputs_preserve_dry_run_behavior(self) -> None:
        build = self.named_step("candidate-images", "Build candidate")
        self.assertEqual(BUILD_PUSH_MODE_INPUT, build["with"]["push"])
        self.assertEqual("${{ needs.metadata.outputs.release_mode == 'dry-run' }}", build["with"]["load"])
        self.assertEqual(
            "${{ needs.metadata.outputs.release_mode == 'publish' && 'mode=max' || false }}",
            build["with"]["provenance"],
        )

    def test_dry_run_keeps_required_nonpublishing_verification(self) -> None:
        metadata_names = [step.get("name") for step in self.steps("metadata")]
        for name in (
            "Verify release preconditions and immutable publication policy",
            "Require release commit to remain the main head",
            "Require successful trusted workflows for the release commit",
        ):
            self.assertIn(name, metadata_names)

        candidate = self.workflow["jobs"]["candidate-images"]
        self.assertEqual("needs.metadata.outputs.transaction_exists != 'true'", candidate["if"])
        self.assertEqual(["backend", "frontend", "ocr"], candidate["strategy"]["matrix"]["component"])
        build = self.named_step("candidate-images", "Build candidate")
        self.assertEqual(
            "VERSION=${{ needs.metadata.outputs.version }}\n"
            "BUILD_TIME=${{ needs.metadata.outputs.build_time }}\n"
            "SOURCE_DATE_EPOCH=${{ needs.metadata.outputs.build_epoch }}\n",
            build["with"]["build-args"],
        )
        for name in ("Smoke exact OCR candidate", "Smoke exact frontend candidate"):
            self.assertNotIn("release_mode", self.named_step("candidate-images", name)["if"])
        scan = self.named_step("candidate-images", "Scan exact candidate for high-severity vulnerabilities")
        self.assertEqual("high", scan["with"]["severity-cutoff"])
        self.assertTrue(scan["with"]["fail-build"])
        sbom = self.named_step("candidate-images", "Generate SBOM")
        self.assertNotIn("if", sbom)
        self.assertEqual("spdx-json", sbom["with"]["format"])

    def test_smoke_scan_and_sbom_share_the_mode_resolved_image_reference(self) -> None:
        resolver = self.named_step("candidate-images", "Resolve candidate image reference")
        self.assertIn('image_ref="${IMAGE}@${digest}"', resolver["run"])
        self.assertIn('image_ref="${IMAGE}:${CANDIDATE_TAG}"', resolver["run"])

        for name in ("Smoke exact OCR candidate", "Smoke exact frontend candidate"):
            step = self.named_step("candidate-images", name)
            self.assertEqual("${{ steps.image.outputs.image_ref }}", step["env"]["IMAGE_REF"])
            self.assertIn('"$IMAGE_REF"', step["run"])
        for name in (
            "Scan exact candidate for high-severity vulnerabilities",
            "Generate SBOM",
        ):
            self.assertEqual(
                "${{ steps.image.outputs.image_ref }}",
                self.named_step("candidate-images", name)["with"]["image"],
            )

    def test_dry_run_summary_discloses_covered_and_uncovered_guarantees(self) -> None:
        summary_job = self.workflow["jobs"]["dry-run-summary"]
        self.assertIn(DRY_RUN_MODE_CONDITION, summary_job["if"])
        summary = self.named_step("dry-run-summary", "Record dry-run coverage")["run"]
        for covered in (
            "Strict release-version validation",
            "immutable publication policy",
            "Current main-head requirement",
            "ci.yml, security.yml, and deploy-smoke.yml",
            "image builds with release build arguments",
            "Frontend and OCR image smoke tests",
            "High-severity Anchore scans",
            "SBOM generation",
        ):
            self.assertIn(covered, summary)
        for uncovered in (
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
            for job in ("candidate-images", "promote")
            for step in self.steps(job)
            if step.get("name") == "Verify pinned build toolchain"
        ]

        self.assertEqual(2, len(checks))
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

        for status in ("200", "500", "error"):
            with self.subTest(status=status):
                result, status_calls = self.run_preconditions("dry-run", status)
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertTrue(
                    any(call[-1] == "repos/example/connex/releases/tags/v2.0.0" for call in status_calls),
                    status_calls,
                )

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
