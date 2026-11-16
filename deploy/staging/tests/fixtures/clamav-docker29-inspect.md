# Docker 29.6.1 ClamAV inspect fixture

The JSON fixture comes from the redacted staging inspect that refused the first refresh.
It preserves the complete `HostConfig`, `Config.User`, redacted `Config.Env`, mounts, running state,
network name and aliases. Container/image IDs are synthetic; unrelated daemon metadata is omitted.
The only rejected field in the original validator was `HostConfig.MaskedPaths`, whose membership
matched the allowed defaults but whose order differed.

Set `CONNEX_CLAMAV_REFRESH_TEST_INSPECT` to a full redacted inspect file to exercise the same cases
against that input directly. The harness reads its original IDs and image reference, stubs
`docker container inspect`, and compares the complete generated create invocation and filtered
environment against the staging expectations. No Docker daemon is used.
