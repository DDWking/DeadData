param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [Parameter(Mandatory = $true)]
    [int]$VersionCode,

    [Parameter(Mandatory = $true)]
    [string]$VersionName,

    [Parameter(Mandatory = $true)]
    [string]$ApkUrl,

    [Parameter(Mandatory = $true)]
    [string]$ReleaseNotes,

    [string]$OutputPath = ".\update.json"
)

if ($VersionCode -lt 2) {
    throw "VersionCode must be greater than the initial app version (1)."
}
if (-not $ApkUrl.StartsWith("https://", [StringComparison]::OrdinalIgnoreCase)) {
    throw "ApkUrl must use HTTPS."
}

$resolvedApk = (Resolve-Path -LiteralPath $ApkPath -ErrorAction Stop).Path
$sha256 = (Get-FileHash -LiteralPath $resolvedApk -Algorithm SHA256).Hash.ToLowerInvariant()
$manifest = [ordered]@{
    versionCode = $VersionCode
    versionName = $VersionName
    apkUrl = $ApkUrl
    sha256 = $sha256
    releaseNotes = $ReleaseNotes
}

$json = $manifest | ConvertTo-Json -Depth 4
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
[System.IO.File]::WriteAllText(
    $resolvedOutput,
    $json + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false)
)
Write-Output "Wrote $resolvedOutput"
