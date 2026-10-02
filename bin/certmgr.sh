#!/bin/sh
# Copyright contributors to the SyncWeave project
# SPDX-License-Identifier: Apache-2.0
#
# certmgr.sh — Launch the SDI Certificate Manager tool.
# Usage:  certmgr.sh [options]
#         certmgr.sh --automate <yaml-file> [--dry-run]

CMDFINDER=which
UNAME_OS=`uname`
if [ "$UNAME_OS" = "OS/390" -o "$UNAME_OS" = "OS400" ]; then
    CMDFINDER=whence
fi

TEMP_BIN_DIR=`$CMDFINDER $0`
TEMP_BIN_DIR=`dirname $TEMP_BIN_DIR`

SKIP_ISCDIR_SETUP=1
. "$TEMP_BIN_DIR/setupCmdLine.sh"

"$TDI_JAVA_PROGRAM" $TDI_MIXEDMODE_FLAG \
    -jar "$TDI_HOME_DIR/jars/tools/cert-manager.jar" "$@"
