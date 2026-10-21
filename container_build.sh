#!/bin/bash
#
# Build SyncWeave inside a container on macOS or Linux.
#
# Usage: ./container_build.sh [options]
#
#   -t, --target TARGETS   Ant target(s) to run (default: "package").
#                          Use "images" for the full release build with
#                          installer archives. Quote several: -t "clean package"
#       --shell            Open a shell in the build container instead.
#       --rebuild-image    Rebuild the build image even if it exists.
#       --skip-resolve     Skip "ant resolve rename_jars" (dependencies
#                          already resolved in lib/ivy).
#       --with-jres        Download the platform JREs even without "images".
#       --keep-build-changes
#                          Keep tracked files the build rewrites (by default
#                          they are restored with git so your tree stays clean).
#   -h, --help             Show this help.
#
# Environment:
#   CONTAINER_ENGINE       podman (default when installed) or docker.
#   CONTAINER_RUN_ARGS     Extra arguments for "<engine> run", e.g. proxy
#                          settings: CONTAINER_RUN_ARGS="-e HTTPS_PROXY=..."
#
# See docs/container_build.md for details.

set -uo pipefail

REPO=$(cd "$(dirname "$0")" && pwd)
CONTAINER_DIR="${REPO}/build/container"

TARGETS="package"
BUILD_SHELL=0
REBUILD_IMAGE=0
SKIP_RESOLVE=0
WITH_JRES=0
KEEP_CHANGES=0

usage() { sed -n '3,26p' "$0" | sed 's/^# \{0,1\}//'; }
die() { echo "Error: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        -t|--target) [ $# -ge 2 ] || die "$1 needs a value"; TARGETS="$2"; shift ;;
        --shell) BUILD_SHELL=1 ;;
        --rebuild-image) REBUILD_IMAGE=1 ;;
        --skip-resolve) SKIP_RESOLVE=1 ;;
        --with-jres) WITH_JRES=1 ;;
        --keep-build-changes) KEEP_CHANGES=1 ;;
        -h|--help) usage; exit 0 ;;
        *) usage; die "unknown option: $1" ;;
    esac
    shift
done

command -v git >/dev/null 2>&1 || die "git is required"

# Pick the container engine.
ENGINE="${CONTAINER_ENGINE:-}"
if [ -z "${ENGINE}" ]; then
    if command -v podman >/dev/null 2>&1; then
        ENGINE=podman
    elif command -v docker >/dev/null 2>&1; then
        ENGINE=docker
    else
        die "neither podman nor docker is installed"
    fi
fi
command -v "${ENGINE}" >/dev/null 2>&1 || die "${ENGINE} is not installed"

OS=$(uname -s)

# On macOS, Podman runs containers in a Linux VM ("podman machine").
if [ "${ENGINE}" = "podman" ] && [ "${OS}" = "Darwin" ]; then
    state=$(podman machine inspect --format '{{.State}}' 2>/dev/null | head -1)
    if [ -z "${state}" ]; then
        die "no Podman machine found. Create one with:
    podman machine init --cpus 4 --memory 8192 --disk-size 60
    podman machine start"
    fi
    if [ "${state}" != "running" ]; then
        echo "Starting Podman machine..."
        podman machine start || die "could not start the Podman machine"
    fi
    mem=$(podman machine inspect --format '{{.Resources.Memory}}' 2>/dev/null | head -1)
    if [ -n "${mem}" ] && [ "${mem}" -lt 6144 ] 2>/dev/null; then
        echo "Warning: the Podman machine has ${mem} MiB of memory; 8192 MiB is recommended."
        echo "         podman machine stop && podman machine set --memory 8192 && podman machine start"
    fi
fi

# The image tag follows the Containerfile, so editing it triggers a rebuild.
IMAGE="localhost/syncweave-build:$(git hash-object "${CONTAINER_DIR}/Containerfile" | cut -c1-12)"

if [ "${REBUILD_IMAGE}" = "1" ] || ! "${ENGINE}" image inspect "${IMAGE}" >/dev/null 2>&1; then
    echo "Building image ${IMAGE}..."
    "${ENGINE}" build --platform linux/amd64 -t "${IMAGE}" \
        -f "${CONTAINER_DIR}/Containerfile" "${CONTAINER_DIR}" || die "image build failed"
fi

# Relabel the bind mount only where SELinux is enforcing (e.g. Fedora/RHEL).
MOUNT_OPTS=""
if [ "${OS}" = "Linux" ] && command -v selinuxenabled >/dev/null 2>&1 && selinuxenabled; then
    MOUNT_OPTS=":Z"
fi

# Rootless Podman maps container root to you. Docker (and rootful Podman) on
# Linux write as real root, so the container hands files back afterwards.
HOST_UID=""
HOST_GID=""
if [ "${OS}" = "Linux" ]; then
    rootless=true
    if [ "${ENGINE}" = "docker" ]; then
        rootless=false
    elif [ "$(podman info --format '{{.Host.Security.Rootless}}' 2>/dev/null)" = "false" ]; then
        rootless=false
    fi
    if [ "${rootless}" = "false" ]; then
        HOST_UID="${SUDO_UID:-$(id -u)}"
        HOST_GID="${SUDO_GID:-$(id -g)}"
    fi
fi

TTY_ARGS=""
if [ "${BUILD_SHELL}" = "1" ]; then
    TTY_ARGS="-it"
fi

# Remember which tracked files were already modified, so only files the
# build itself rewrites are restored afterwards.
BEFORE=$(mktemp)
AFTER=$(mktemp)
RESTORE=$(mktemp)
trap 'rm -f "${BEFORE}" "${AFTER}" "${RESTORE}"' EXIT
git -C "${REPO}" diff --name-only | sort > "${BEFORE}"

echo "Engine: ${ENGINE}   Image: ${IMAGE}"
echo "Repo:   ${REPO}"
[ "${BUILD_SHELL}" = "1" ] || echo "Target: ant ${TARGETS}"

# shellcheck disable=SC2086  # TTY_ARGS and CONTAINER_RUN_ARGS are word lists
"${ENGINE}" run --rm ${TTY_ARGS} \
    --platform linux/amd64 \
    --name "syncweave-build-$$" \
    -v "${REPO}:/build${MOUNT_OPTS}" \
    -v syncweave-ivy-cache:/root/.ivy2 \
    -e BUILD_TARGETS="${TARGETS}" \
    -e BUILD_SHELL="${BUILD_SHELL}" \
    -e SKIP_RESOLVE="${SKIP_RESOLVE}" \
    -e WITH_JRES="${WITH_JRES}" \
    -e HOST_UID="${HOST_UID}" \
    -e HOST_GID="${HOST_GID}" \
    ${CONTAINER_RUN_ARGS:-} \
    --entrypoint /bin/bash \
    "${IMAGE}" /build/build/container/entrypoint.sh
rc=$?

if [ "${KEEP_CHANGES}" = "0" ]; then
    git -C "${REPO}" diff --name-only | sort > "${AFTER}"
    comm -13 "${BEFORE}" "${AFTER}" > "${RESTORE}"
    if [ -s "${RESTORE}" ]; then
        while IFS= read -r f; do
            git -C "${REPO}" checkout -- "${f}"
        done < "${RESTORE}"
        echo "Restored $(wc -l < "${RESTORE}" | tr -d ' ') tracked file(s) the build rewrote (use --keep-build-changes to keep them)."
    fi
fi

exit "${rc}"
