#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0

# adDn schema present and declares the attribute + aux class
f="$ROOT/schema/fds-adDn.ldif"
grep -q "NAME 'adDn'" "$f"           || { echo "FAIL: adDn attributetype missing"; fail=1; }
grep -q "NAME 'fdsSyncMetadata'" "$f" || { echo "FAIL: fdsSyncMetadata objectclass missing"; fail=1; }
grep -qi "AUXILIARY" "$f"            || { echo "FAIL: fdsSyncMetadata not AUXILIARY"; fail=1; }

# adDn equality index (member translation searches (adDn=...) once per member)
xf="$ROOT/schema/fds-adDn-index.ldif"
[ -f "$xf" ] || { echo "FAIL: schema/fds-adDn-index.ldif missing"; fail=1; }
grep -q "^olcDbIndex: adDn eq" "$xf" 2>/dev/null || { echo "FAIL: fds-adDn-index.ldif does not index adDn"; fail=1; }
grep -q "^changetype: modify" "$xf" 2>/dev/null   || { echo "FAIL: fds-adDn-index.ldif must be a modify"; fail=1; }

# Stock FDS linked-entry + PTA schema (generic-LDAP target writes these)
lf="$ROOT/schema/fds-linked-entry.ldif"
[ -f "$lf" ] || { echo "FAIL: schema/fds-linked-entry.ldif missing"; fail=1; }
for nm in adObjectGUIDStr ibm-ptaLinkAttribute ibm-ptaLinkValue activedirectorylinkedentry ibm-ptaReferral; do
  grep -q "NAME '$nm'" "$lf" 2>/dev/null || { echo "FAIL: fds-linked-entry.ldif missing definition for $nm"; fail=1; }
done

# Extend stock, don't overwrite: password helpers appended to stock customScript.js
cs="$ROOT/maps/customScript-additions.js"
[ -f "$cs" ] || { echo "FAIL: maps/customScript-additions.js missing"; fail=1; }
grep -q "function migratePassword" "$cs" || { echo "FAIL: customScript-additions.js missing migratePassword"; fail=1; }
# person.map patch documents the one-line password change (not a wholesale map)
pm="$ROOT/maps/person.map.patch"
[ -f "$pm" ] || { echo "FAIL: maps/person.map.patch missing"; fail=1; }
grep -q "userPassword=migratePassword(work)" "$pm" || { echo "FAIL: person.map.patch missing the userPassword change"; fail=1; }
# we must NOT ship replacement stock maps (they would overwrite stock and break it)
for m in person group organizationalunit organization domain; do
  [ -f "$ROOT/maps/$m.map" ] && { echo "FAIL: maps/$m.map should not be shipped (overwrites stock LDAPSync map)"; fail=1; }
done

# Nothing shipped here may be deployable as an FDS_Target* config (FDS would use it as the target definition)
if find "$ROOT" -iname 'FDS_Target*' -not -path '*/.git/*' | grep -q .; then echo "FAIL: an FDS_Target* file exists under the example folder"; fail=1; fi

# Docker scaffold: openldap image + seed LDIF with base DN
grep -q 'osixia/openldap' "$ROOT/docker/docker-compose.yml" 2>/dev/null || { echo "FAIL: docker-compose missing openldap image"; fail=1; }
grep -q 'dc=example,dc=com' "$ROOT/docker/seed.ldif" 2>/dev/null        || { echo "FAIL: seed.ldif missing base DN"; fail=1; }

# README: operator guide covers deploy prerequisites, aux modes, and test procedure
R="$ROOT/README.md"
for kw in "target.isLDAP" "target.isTDS" "fds-adDn.ldif" "fds-linked-entry.ldif" "fds-adDn-index.ldif" "migratePassword" "person.map.patch" "ldapwhoami" "LDAPS" "Advanced Settings" "Custom properties" "Example Guide"; do
  grep -q "$kw" "$R" 2>/dev/null || { echo "FAIL: README missing '$kw'"; fail=1; }
done
! grep -qiE 'custom[- ]target' "$R" 2>/dev/null || { echo "FAIL: README must not describe this as a custom target"; fail=1; }
! grep -q 'FDS_Target' "$R" 2>/dev/null || { echo "FAIL: README must not mention FDS_Target (FDS creates its own configs on first access)"; fail=1; }

# password + aux helpers exported by the additions module (for Node tests)
for fn in migratePassword targetObjectClasses parseList; do
  grep -q "$fn:" "$ROOT/maps/customScript-additions.js" || { echo "FAIL: customScript-additions.js does not export $fn"; fail=1; }
done
# node unit tests must pass
node --test "$ROOT/test/helpers.test.js" >/dev/null 2>&1 || { echo "FAIL: node helper tests failing"; fail=1; }

[ "$fail" -eq 0 ] && echo "consistency.test.sh: PASS"
exit "$fail"
