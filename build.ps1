$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$npm = (Get-Command npm.cmd -ErrorAction Stop).Source
$maven = Join-Path $root "mvnw.cmd"

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(ValueFromRemainingArguments = $true)][string[]]$CommandArgs
    )
    & $FilePath @CommandArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code $LASTEXITCODE`: $FilePath $($CommandArgs -join ' ')"
    }
}

Push-Location (Join-Path $root "aft-web")
try {
    Invoke-Checked $npm install --cache .npm-cache
    Invoke-Checked $npm run build
} finally {
    Pop-Location
}

Invoke-Checked $maven "-Dmaven.repo.local=$root\.m2-repository" package
