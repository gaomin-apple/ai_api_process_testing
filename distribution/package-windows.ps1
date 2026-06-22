$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$jar = Join-Path $root "aft-server\target\aft-server-0.1.0-SNAPSHOT-exec.jar"
$output = Join-Path $root "distribution\output"

if (-not (Test-Path $jar)) {
    throw "Build the project first with build.ps1"
}

New-Item -ItemType Directory -Force -Path $output | Out-Null
jpackage `
    --type app-image `
    --name "AFT Studio" `
    --input (Split-Path $jar) `
    --main-jar (Split-Path $jar -Leaf) `
    --dest $output `
    --java-options "-Dfile.encoding=UTF-8"
