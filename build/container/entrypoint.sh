#!/bin/bash
#
# Runs inside the build container (started by build-SyncWeave.sh).
# The repository is mounted at /build.
#
# Environment (set by build-SyncWeave.sh):
#   BUILD_TARGETS  Ant targets to run, e.g. "package" or "images".
#   BUILD_SHELL    1 = open an interactive shell instead of building.
#   SKIP_RESOLVE   1 = skip "ant resolve rename_jars".
#   WITH_JRES      1 = download the platform JREs even if not building images.
#   BUILD_TESTS    1 = after a successful build, also run
#                  "ant package_unit_tests zip_unit_tests".
#   HOST_UID/GID   When set (Docker), files are handed back to this owner.

set -uo pipefail

cd /build

log() { printf '\n==> %s\n' "$*"; }

fix_ownership() {
    if [ -n "${HOST_UID:-}" ] && [ "${HOST_UID}" != "0" ]; then
        chown -R "${HOST_UID}:${HOST_GID:-${HOST_UID}}" /build 2>/dev/null || true
    fi
}
trap fix_ownership EXIT

# Ivy must be on Ant's system classloader (see docs/syncweave_local_build_steps.md).
cp -f /build/ivy/ivy.jar "${ANT_HOME}/lib/ivy.jar"

if [ "${BUILD_SHELL:-0}" = "1" ]; then
    log "Interactive shell. Run e.g.: ant resolve rename_jars && ant package"
    exec bash
fi

# The installer step of "ant images" packages one Semeru JRE per platform
# from adks/ibm/jre (git-ignored). Download any that are missing.
JRE_RELEASE=https://github.com/ibmruntimes/semeru21-binaries/releases/download/jdk-21.0.12.0
JRES="ibm-semeru-open-jre_x64_linux_21.0.12.0.tar.gz
ibm-semeru-open-jre_x64_windows_21.0.12.0.zip
ibm-semeru-open-jre_s390x_linux_21.0.12.0.tar.gz
ibm-semeru-open-jre_ppc64_aix_21.0.12.0.tar.gz
ibm-semeru-open-jre_aarch64_mac_21.0.12.0.tar.gz
ibm-semeru-open-jre_x64_mac_21.0.12.0.tar.gz"

case " ${BUILD_TARGETS} " in
    *" images "*) WITH_JRES=1 ;;
esac

if [ "${WITH_JRES:-0}" = "1" ]; then
    log "Checking platform JREs in adks/ibm/jre"
    mkdir -p adks/ibm/jre
    for jre in ${JRES}; do
        if [ -f "adks/ibm/jre/${jre}" ]; then
            echo "present: ${jre}"
            continue
        fi
        echo "downloading: ${jre}"
        curl -fsSL -o "adks/ibm/jre/${jre}.part" "${JRE_RELEASE}/${jre}" || { echo "Error: download failed: ${jre}"; exit 1; }
        expected=$(curl -fsSL "${JRE_RELEASE}/${jre}.sha256.txt" | cut -d' ' -f1)
        actual=$(sha256sum "adks/ibm/jre/${jre}.part" | cut -d' ' -f1)
        if [ -z "${expected}" ] || [ "${expected}" != "${actual}" ]; then
            echo "Error: checksum mismatch for ${jre}"
            rm -f "adks/ibm/jre/${jre}.part"
            exit 1
        fi
        mv "adks/ibm/jre/${jre}.part" "adks/ibm/jre/${jre}"
    done
fi

# Maven Central sometimes rate-limits (HTTP 429), so retry with a pause.
if [ "${SKIP_RESOLVE:-0}" != "1" ]; then
    for attempt in 1 2 3; do
        log "Resolving Ivy dependencies (attempt ${attempt} of 3)"
        if ant resolve rename_jars; then
            break
        fi
        if [ "${attempt}" = "3" ]; then
            echo "Error: dependency resolution failed 3 times."
            exit 1
        fi
        sleep $((attempt * 30))
    done
fi

# bin/ folders left by Eclipse IDE compiles in the plugin projects conflict
# with the PDE build. No tracked files live in them.
log "Removing stale IDE bin/ folders from osgi/plugins and ce_rcp/plugins"
find osgi/plugins ce_rcp/plugins -type d -name bin -prune -exec rm -rf {} + 2>/dev/null || true

log "Running: ant ${BUILD_TARGETS}"
start=$(date +%s)
ant ${BUILD_TARGETS} 2>&1 | tee build.log
rc=${PIPESTATUS[0]}
log "ant ${BUILD_TARGETS} finished with exit code ${rc} in $(( ($(date +%s) - start) / 60 )) min (log: build.log)"

if [ "${rc}" = "0" ] && [ "${BUILD_TESTS:-0}" = "1" ]; then
    log "Running: ant package_unit_tests zip_unit_tests"
    ant package_unit_tests zip_unit_tests 2>&1 | tee unit_tests_build.log
    rc=${PIPESTATUS[0]}
    log "Unit-test build finished with exit code ${rc} (log: unit_tests_build.log)"
fi

if [ "${rc}" = "0" ]; then
    for dir in export/ce_eclipse ship/install ship/tests; do
        if [ -d "${dir}" ]; then
            files=$(find "${dir}" -maxdepth 1 -type f \( -name '*.zip' -o -name '*.tgz' \) \
                -newermt "@${start}" -printf '  %f (%s bytes)\n')
            if [ -n "${files}" ]; then
                echo "Built in ${dir}:"
                echo "${files}"
            fi
        fi
    done
fi

exit "${rc}"
