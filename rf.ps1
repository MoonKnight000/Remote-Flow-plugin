param(
    [Parameter(Position=0, Mandatory=$false)]
    [string]$Action = "status",
    [Parameter(Position=1, ValueFromRemainingArguments=$true)]
    [string[]]$RemainingArgs
)

$portFile = Join-Path $PSScriptRoot ".remote-flow.port"
$exitFile = Join-Path $PSScriptRoot ".remote-flow.exitcode"

if (-not (Test-Path $portFile)) {
    Write-Error "[Remote Flow] Error: IntelliJ IDEA Remote Flow bridge is not running. Please ensure IntelliJ is open with Remote Flow active."
    exit 1
}

$port = (Get-Content $portFile -Raw).Trim()
if (Test-Path $exitFile) { Remove-Item $exitFile -Force -ErrorAction SilentlyContinue }

switch ($Action.ToLower()) {
    "test" {
        & curl.exe -s -N -X POST "http://127.0.0.1:$port/api/test"
    }
    "build" {
        & curl.exe -s -N -X POST "http://127.0.0.1:$port/api/build"
    }
    "sync" {
        & curl.exe -s -N -X POST "http://127.0.0.1:$port/api/sync"
    }
    "status" {
        & curl.exe -s "http://127.0.0.1:$port/api/status"
        Write-Host ""
    }
    "exec" {
        $cmd = $RemainingArgs -join " "
        & curl.exe -s -N -X POST "http://127.0.0.1:$port/api/exec" --data-binary "$cmd"
    }
    default {
        Write-Host "Usage: .\rf.ps1 <test | build | sync | exec <command> | status>"
        exit 1
    }
}

if (Test-Path $exitFile) {
    $code = (Get-Content $exitFile -Raw).Trim()
    exit [int]$code
}
exit 0
