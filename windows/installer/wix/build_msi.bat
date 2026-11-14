@echo off
setlocal enabledelayedexpansion

REM ============================================================
REM  MSI 安装包构建（WiX v4）
REM  用法：build_msi.bat <publishDir> <version> <outputDir>
REM ============================================================

set "PUBLISH_DIR=%~1"
set "VERSION=%~2"
set "OUT_DIR=%~3"

if "%PUBLISH_DIR%"=="" (
    echo 用法: build_msi.bat ^<publishDir^> ^<version^> ^<outputDir^>
    exit /b 1
)
if "%VERSION%"=="" set "VERSION=1.0.0"
if "%OUT_DIR%"=="" set "OUT_DIR=%~dp0out"

echo [MSI] 发布目录: %PUBLISH_DIR%
echo [MSI] 版本: %VERSION%
echo [MSI] 输出: %OUT_DIR%

if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

REM 用 wix 命令行打包。
REM 采用 heat 收集文件清单 + candle/light 是 v3 的老路子；
REM WiX v4 起用 wix build 直接吃 wxs，配合 HarvestDirectory 自动收集。
dotnet tool install --global wix --version 5.* 2>nul
dotnet tool update --global wix --version 5.* 2>nul

wix build "%~dp0Product.wxs" ^
    -arch x64 ^
    -d PublishDir="%PUBLISH_DIR%" ^
    -d ProductVersion="%VERSION%" ^
    -o "%OUT_DIR%\NebulaUpdater-%VERSION%-x64.msi"

if errorlevel 1 (
    echo [MSI] 构建失败
    exit /b 1
)

echo [MSI] 完成: %OUT_DIR%\NebulaUpdater-%VERSION%-x64.msi
endlocal