# Container Build on macOS and Linux

`container_build.sh` builds SyncWeave inside a Podman (or Docker) container, so the only things you install on your machine are Git and a container engine. It is the macOS/Linux counterpart to the Windows PowerShell + Podman build.

```bash
./container_build.sh                 # ant package: compile + IDE zips for all platforms
./container_build.sh -t images       # full release build + installer archives
./container_build.sh --shell         # interactive shell in the build container
```

## What the script does

1. Starts the Podman machine on macOS if it is stopped.
2. Builds the build image the first time, or whenever `build/container/Containerfile` changes. The image is Red Hat UBI 9 with IBM Semeru JDK 21.0.12 and Apache Ant 1.10.17, both checksum-verified. No other packages are installed.
3. Mounts the repository at `/build` and runs `build/container/entrypoint.sh`, which:
    1. Puts `ivy/ivy.jar` on Ant's system classloader.
    2. For `images`: downloads the six platform JREs into `adks/ibm/jre/` (git-ignored) if missing, checking each SHA-256.
    3. Runs `ant resolve rename_jars`, retrying up to 3 times because Maven Central sometimes rate-limits (HTTP 429).
    4. Runs `ant <targets>` and writes `build.log`.
4. Restores tracked files that the build rewrites, so `git status` stays clean. Each build rewrites some committed jars in `lib/ivy/`, `examples/MSOutlook/MSOutlookConnector.jar` and a generated `build.xml` under `tools/eclipse/`. Files you had already modified before the build are left alone. Use `--keep-build-changes` to skip this step.

Ivy's download cache lives in the `syncweave-ivy-cache` volume, so later builds resolve much faster.

## Prerequisites

| | macOS | Linux |
| --- | --- | --- |
| Git | Xcode command line tools or Homebrew | Distribution package |
| Container engine | [Podman](https://podman.io) (`brew install podman`) or Podman Desktop | `podman` package (rootless is fine) |
| One-time setup | `podman machine init --cpus 4 --memory 8192 --disk-size 60` | None |
| Free disk | About 10 GB for `images` | About 10 GB for `images` |

Docker also works: set `CONTAINER_ENGINE=docker`.

### Apple Silicon

The build image is always `linux/amd64`, because the build runs `tools/tmsxml_to_properties/tmsxml_to_properties`, an x86-64 binary. On Apple Silicon, Podman runs it under emulation, which works but is slower. Enabling Rosetta for the Podman machine makes it noticeably faster (Podman Desktop: Settings > Preferences > Rosetta).

## Options

| Option | Meaning |
| --- | --- |
| `-t, --target TARGETS` | Ant target(s); default `package`. Quote several: `-t "clean package"` |
| `--shell` | Open a shell in the container instead of building |
| `--rebuild-image` | Rebuild the build image |
| `--skip-resolve` | Skip `ant resolve rename_jars` |
| `--with-jres` | Download the platform JREs without building `images` |
| `--keep-build-changes` | Keep the tracked files the build rewrites |

| Environment variable | Meaning |
| --- | --- |
| `CONTAINER_ENGINE` | `podman` (default when installed) or `docker` |
| `CONTAINER_RUN_ARGS` | Extra arguments for `podman run` |

## Outputs

| Target | Output |
| --- | --- |
| `package` | `export/ce_eclipse/eclipsece-<platform>.zip` (IDE for Linux, Windows, macOS x86_64 and aarch64) |
| `images` | Also `ship/install/SyncWeave-1.0.0-<platform>.zip` / `.tgz` (installer archives) |

Both directories are git-ignored. Timings measured on a 4-core Linux x86_64 machine: `package` about 9 minutes, `images` about 12 minutes, plus a few minutes for the first dependency download.

## Behind a proxy

Put the settings in an env file and pass it through `CONTAINER_RUN_ARGS`. Java (Ivy) reads the proxy from `JAVA_TOOL_OPTIONS`; `curl` (JRE downloads) reads `HTTPS_PROXY`:

```bash
cat > proxy.env <<'EOF'
HTTPS_PROXY=http://proxy.example.com:8080
JAVA_TOOL_OPTIONS=-Dhttps.proxyHost=proxy.example.com -Dhttps.proxyPort=8080
EOF
CONTAINER_RUN_ARGS="--env-file proxy.env" ./container_build.sh
```

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `no Podman machine found` (macOS) | Run the `podman machine init` command above, then retry |
| Build killed or very slow | Give the Podman machine 8 GB of memory: `podman machine stop && podman machine set --memory 8192 && podman machine start` |
| `Permission denied` on `/build` (Fedora/RHEL) | The script relabels the mount with `:Z` when SELinux is enforcing; make sure `selinuxenabled` is on your `PATH` |
| Files owned by root after a Docker build | The container hands files back to your user on exit; if a build was killed, run `sudo chown -R $(id -u):$(id -g) .` |
| `impossible to resolve dependencies` | Maven Central rate-limited all 3 attempts; wait a few minutes and rerun |
| `exec returned: 13` from `eclipse_rules.xml` | See `export/logs/ce_rcp.log` and `docs/syncweave_local_build_steps.md` |

For the non-container build, see `docs/syncweave_local_build_steps.md`.
