[CmdletBinding()]
param(
    [string]$InstallDir = "$env:LOCALAPPDATA\M-CLI\bin",
    [string]$ClientSource = "https://raw.githubusercontent.com/mbky-159/M-CLI/master/deploy/client/m-cli-cloud.ps1",
    [switch]$SkipPathUpdate
)

$ErrorActionPreference = "Stop"
$clientFile = Join-Path $InstallDir "m-cli-cloud.ps1"
$launcherFile = Join-Path $InstallDir "mcli.cmd"

New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null

if (Test-Path -LiteralPath $ClientSource) {
    Copy-Item -LiteralPath $ClientSource -Destination $clientFile -Force
} else {
    $content = Invoke-RestMethod -Uri $ClientSource -UseBasicParsing
    [System.IO.File]::WriteAllText(
        $clientFile,
        [string]$content,
        [System.Text.UTF8Encoding]::new($true))
}

$launcher = '@echo off' + "`r`n" +
    'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0m-cli-cloud.ps1" %*' + "`r`n"
[System.IO.File]::WriteAllText($launcherFile, $launcher, [System.Text.Encoding]::ASCII)

if (-not $SkipPathUpdate) {
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $entries = @($userPath -split ";" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($entries -notcontains $InstallDir) {
        $newPath = (@($entries) + $InstallDir) -join ";"
        [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
    }
    if (($env:Path -split ";") -notcontains $InstallDir) {
        $env:Path = "$env:Path;$InstallDir"
    }
}

Write-Host "M-CLI 安装完成。" -ForegroundColor Green
Write-Host "打开一个新的 PowerShell，然后输入：mcli" -ForegroundColor Cyan
