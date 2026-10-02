@echo off
:: Copyright contributors to the SyncWeave project
:: SPDX-License-Identifier: Apache-2.0
::
:: certmgr.bat - Launch the SDI Certificate Manager tool.
:: Usage:  certmgr.bat [options]
::         certmgr.bat --automate <yaml-file> [--dry-run]

setlocal

:: Switch console to UTF-8 so Unicode output renders correctly
chcp 65001 > nul

set TEMP_BIN_DIR=%~d0%~p0

set SKIP_ISCDIR_SETUP=1
call "%TEMP_BIN_DIR%\setupCmdLine.bat"

"%TDI_JAVA_PROGRAM%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%TDI_HOME_DIR%\jars\tools\cert-manager.jar" %*

endlocal