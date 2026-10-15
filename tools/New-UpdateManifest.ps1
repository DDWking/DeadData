<#
.SYNOPSIS
Creates update.json for a DeadData release from a signed APK.

.DESCRIPTION
The manifest contract is shared three ways and must stay in sync:
  * the app that consumes it (UpdateClient.parseManifest)
  * app/src/test/java/app/deaddata/mobile/update/UpdateManifestTest.java (pins it)
  * tools/release_manifest.py (the CI release workflow uses it)

  versionCode  integer >= 1
  versionName  non-blank
  apkUrl       must use https://
  sha256       64 lowercase hex characters of the APK
  releaseNotes release notes text, taken from CHANGELOG.md when not supplied

.EXAMPLE
pwsh -File tools/New-UpdateManifest.ps1 -ApkPath .\DeadData-1.4.1.apk `
    -VersionCode 10 -VersionName 1.4.1 `
    -ApkUrl https://github.com/DDWking/DeadData/releases/download/v1.4.1/DeadData-1.4.1.apk
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [Parameter(Mandatory = $true)]
    [int]$VersionCode,

    [Parameter(Mandatory = $true)]
    [string]$VersionName,

    [Parameter(Mandatory = $true)]
    [string]$ApkUrl,

    [string]$ReleaseNotes,

    [string]$ChangelogPath,

    [string]$OutputPath = ".\update.json"
)

$ErrorActionPreference = "Stop"

if ($VersionCode -lt 1) {
    throw "VersionCode must be >= 1; the app ignores manifests with a lower value."
}
if ([string]::IsNullOrWhiteSpace($VersionName)) {
    throw "VersionName must not be blank."
}
if (-not $ApkUrl.StartsWith("https://", [StringComparison]::OrdinalIgnoreCase)) {
    throw "ApkUrl must use HTTPS."
}

function Get-ChangelogSection {
    param([string]$Text, [string]$Name)

    $pattern = '(?ms)^##\s*\[' + [regex]::Escape($Name) + '\][^\n]*$(.*?)(?=^##\s|\z)'
    $match = [regex]::Match($Text, $pattern)
    if ($match.Success) {
        return $match.Groups[1].Value.Trim()
    }
    return ""
}

if ([string]::IsNullOrWhiteSpace($ReleaseNotes)) {
    if ([string]::IsNullOrWhiteSpace($ChangelogPath)) {
        $ChangelogPath = Join-Path (Split-Path -Parent $PSScriptRoot) "CHANGELOG.md"
    }
    if (-not (Test-Path -LiteralPath $ChangelogPath -PathType Leaf)) {
        throw "ReleaseNotes was not supplied and no changelog exists at $ChangelogPath."
    }
    $changelogText = [System.IO.File]::ReadAllText(
        (Resolve-Path -LiteralPath $ChangelogPath -ErrorAction Stop).Path)
    $ReleaseNotes = Get-ChangelogSection -Text $changelogText -Name $VersionName
    if ([string]::IsNullOrWhiteSpace($ReleaseNotes)) {
        $ReleaseNotes = Get-ChangelogSection -Text $changelogText -Name "Unreleased"
    }
    if ([string]::IsNullOrWhiteSpace($ReleaseNotes)) {
        throw "Neither '[${VersionName}]' nor '[Unreleased]' has content in $ChangelogPath."
    }
}

$resolvedApk = (Resolve-Path -LiteralPath $ApkPath -ErrorAction Stop).Path
$sha256 = (Get-FileHash -LiteralPath $resolvedApk -Algorithm SHA256).Hash.ToLowerInvariant()
$manifest = [ordered]@{
    versionCode  = $VersionCode
    versionName  = $VersionName.Trim()
    apkUrl       = $ApkUrl.Trim()
    sha256       = $sha256
    releaseNotes = $ReleaseNotes.Trim()
}

$json = $manifest | ConvertTo-Json -Depth 4

# Self check: the serialized manifest must satisfy the same rules the app applies.
$parsed = $json | ConvertFrom-Json
if ($parsed.sha256 -notmatch '^[0-9a-f]{64}$') {
    throw "Refusing to write a manifest whose sha256 is not 64 lowercase hex characters."
}
if ($parsed.versionCode -lt 1 -or
    [string]::IsNullOrWhiteSpace($parsed.versionName) -or
    [string]::IsNullOrWhiteSpace($parsed.apkUrl) -or
    [string]::IsNullOrWhiteSpace($parsed.releaseNotes)) {
    throw "Refusing to write a manifest that the app would reject."
}

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
[System.IO.File]::WriteAllText(
    $resolvedOutput,
    $json + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false)
)
Write-Output "Wrote $resolvedOutput (versionCode=$VersionCode versionName=$($manifest.versionName))"
