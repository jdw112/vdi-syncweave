# Building SyncWeave on macOS and Linux

`build-SyncWeave.sh` builds SyncWeave inside a Podman (or Docker) container, so the only things you install on your machine are Git and a container engine. It is the macOS/Linux counterpart to `build-SyncWeave.ps1`, which builds on Windows in an AlmaLinux 9 WSL distro. Both use AlmaLinux 9, matching the RHEL 9 CI build host.

```bash
./build-SyncWeave.sh                    # ant package: compile + IDE zips for all platforms
./build-SyncWeave.sh -t images          # full release build + installer archives
./build-SyncWeave.sh --build-tests      # package + unit-test JARs and zip
./build-SyncWeave.sh --shell            # interactive shell in the build container
```

The script can live in the repository root, or, like the Windows script, in the parent folder next to a `SyncWeave/` clone. `--repo PATH` points it anywhere else.

## What the script does

1. Starts the Podman machine on macOS if it is stopped.
2. Builds the build image the first time, or whenever `build/container/Containerfile` changes. The image is AlmaLinux 9 with IBM Semeru JDK 21.0.12 and Apache Ant 1.10.17 (both checksum-verified), plus `libxslt`, which the build's `tools/tmsxml_to_properties` binary needs.
3. Mounts the repository at `/build` and runs `build/container/entrypoint.sh`, which:
    1. Puts `ivy/ivy.jar` on Ant's system classloader.
    2. For `images`: downloads the six platform JREs into `adks/ibm/jre/` (git-ignored) if missing, checking each SHA-256.
    3. Runs `ant resolve rename_jars`, retrying up to 3 times because Maven Central sometimes rate-limits (HTTP 429).
    4. Removes stale Eclipse IDE `bin/` folders from `osgi/plugins` and `ce_rcp/plugins`, which conflict with the PDE build.
    5. Runs `ant <targets>` and writes `build.log`; with `--build-tests`, then runs `ant package_unit_tests zip_unit_tests` and writes `unit_tests_build.log`.
4. Restores tracked files that the build rewrites, so `git status` stays clean. Each build rewrites some committed jars in `lib/ivy/`, `examples/MSOutlook/MSOutlookConnector.jar` and a generated `build.xml` under `tools/eclipse/`. Files you had already modified before the build are left alone. Use `--keep-build-changes` to skip this step.

Ivy's download cache lives in the `syncweave-ivy-cache` volume, so later builds resolve much faster.

## Prerequisites

| | macOS | Linux |
| --- | --- | --- |
| Git | Xcode command line tools or Homebrew | Distribution package |
| Container engine | [Podman](https://podman.io) (`brew install podman`) or Podman Desktop | `podman` package (rootless is fine) |
| One-time setup | `podman machine init --cpus 4 --memory 8192 --disk-size 60` | None |
| Free disk | About 10 GB for `images` | About 10 GB for `images` |

Docker also works, including Docker Desktop on macOS: the script uses it automatically when Podman is not installed, or set `CONTAINER_ENGINE=docker`. On Apple Silicon, turn on Settings > General > "Use Rosetta for x86_64/amd64 emulation on Apple Silicon".

### Apple Silicon

The build image is always `linux/amd64`, because the build runs `tools/tmsxml_to_properties/tmsxml_to_properties`, an x86-64 binary. On Apple Silicon, Podman runs it under emulation, which works but is slower. Enabling Rosetta for the Podman machine makes it noticeably faster (Podman Desktop: Settings > Preferences > Rosetta).

## Options

| Option | Meaning |
| --- | --- |
| `-t, --target TARGETS` | Ant target(s); default `package`. Quote several: `-t "clean package"` |
| `--build-tests` | Also run `ant package_unit_tests zip_unit_tests` after a successful build |
| `--repo PATH` | Repository to build (default: the script's folder, or `./SyncWeave` next to it) |
| `--shell` | Open a shell in the container instead of building |
| `--rebuild-image` | Rebuild the build image |
| `--skip-resolve` | Skip `ant resolve rename_jars` |
| `--with-jres` | Download the platform JREs without building `images` |
| `--keep-build-changes` | Keep the tracked files the build rewrites |

| Environment variable | Meaning |
| --- | --- |
| `CONTAINER_ENGINE` | `podman` (default when installed) or `docker` |
| `CONTAINER_RUN_ARGS` | Extra arguments for `podman run` |

To build the image on Ubuntu 24.04 instead of AlmaLinux 9, build it once by hand with `--build-arg BASE_IMAGE=docker.io/library/ubuntu:24.04` and the tag the script prints.

## Comparison with build-SyncWeave.ps1

| | `build-SyncWeave.ps1` (Windows) | `build-SyncWeave.sh` (macOS/Linux) |
| --- | --- | --- |
| Build environment | AlmaLinux 9 WSL distro, imported once by hand | AlmaLinux 9 container image, built automatically |
| JDK | `adks/ibm/jdk` in the repo (`setup-jdk.sh`) | Semeru JDK 21.0.12 inside the image |
| Ant | EPEL `ant` package, run with `--noconfig` | Apache Ant 1.10.17 tarball inside the image |
| Dependency resolve | Skipped whenever `lib/ivy/` contains any jar | Runs by default, with retries; `--skip-resolve` to skip |
| Unit tests | `-BuildTests`, `-RunTests -TestInstallRoot` | `--build-tests` (running the suite is not supported yet) |
| Faster filesystem | `-NativeFs` (rsync into ext4) | Not needed on Linux; bind mount on macOS |
| Tracked files rewritten by the build | Left modified | Restored automatically |

Note on the resolve check: `lib/ivy/` always contains 251 committed jars after a clone, so the Windows script never runs `ant resolve` on a fresh clone. Resolving also downloads 64 versioned jars that git ignores; whether the build needs them has not been verified.

## Outputs

| Target | Output |
| --- | --- |
| `package` | `export/ce_eclipse/eclipsece-<platform>.zip` (IDE for Linux, Windows, macOS x86_64 and aarch64) |
| `images` | Also `ship/install/SyncWeave-1.0.0-<platform>.zip` / `.tgz` (installer archives) |
| `--build-tests` | `export/unit_tests/*.jar` and `ship/tests/unit_tests.zip` |

All are git-ignored. Measured `package` times: about 9 minutes on a 4-core Linux x86_64 machine (`images` about 12), and 16 minutes on macOS with Docker Desktop. The first run adds a few minutes to build the image and download dependencies.

## Behind a proxy

Put the settings in an env file and pass it through `CONTAINER_RUN_ARGS`. Java (Ivy) reads the proxy from `JAVA_TOOL_OPTIONS`; `curl` (JRE downloads) reads `HTTPS_PROXY`:

```bash
cat > proxy.env <<'EOF'
HTTPS_PROXY=http://proxy.example.com:8080
JAVA_TOOL_OPTIONS=-Dhttps.proxyHost=proxy.example.com -Dhttps.proxyPort=8080
EOF
CONTAINER_RUN_ARGS="--env-file proxy.env" ./build-SyncWeave.sh
```

Building the image itself also needs the proxy: `podman build` passes `HTTPS_PROXY` from your environment automatically.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `no Podman machine found` (macOS) | Run the `podman machine init` command above, then retry |
| Build killed or very slow | Give the Podman machine 8 GB of memory: `podman machine stop && podman machine set --memory 8192 && podman machine start` |
| `Permission denied` on `/build` (Fedora/RHEL) | The script relabels the mount with `:Z` when SELinux is enforcing; make sure `selinuxenabled` is on your `PATH` |
| Files owned by root after a Docker build | The container hands files back to your user on exit; if a build was killed, run `sudo chown -R $(id -u):$(id -g) .` |
| `impossible to resolve dependencies` | Maven Central rate-limited all 3 attempts; wait a few minutes and rerun |
| `exec returned: 127` in `doc_rules.xml` | `tmsxml_to_properties` cannot load `libxslt`; rebuild the image with `--rebuild-image` |
| `exec returned: 13` from `eclipse_rules.xml` | See `export/logs/ce_rcp.log` and `docs/syncweave_local_build_steps.md` |

For the non-container build, see `docs/syncweave_local_build_steps.md`.
