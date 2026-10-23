param([string]$BuildRoot = (Join-Path $env:USERPROFILE '.local/share/leihuo-build'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jdk = Get-ChildItem -LiteralPath (Join-Path $BuildRoot 'jdk') -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/javac.exe') } | Select-Object -First 1
if (-not $jdk) { throw 'Existing JDK build tools were not found.' }
$checkRoot = Join-Path ([IO.Path]::GetTempPath()) ('leihuo-nas-session-check-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $checkRoot | Out-Null
try {
    & (Join-Path $jdk.FullName 'bin/javac.exe') -encoding UTF-8 -d $checkRoot (Join-Path $projectRoot 'app/src/main/java/com/leihuo/chat/NasSessionPolicy.java') (Join-Path $PSScriptRoot 'checks/com/leihuo/chat/NasSessionPolicyCheck.java')
    if ($LASTEXITCODE -ne 0) { throw 'NAS session boundary check compilation failed.' }
    & (Join-Path $jdk.FullName 'bin/java.exe') -cp $checkRoot com.leihuo.chat.NasSessionPolicyCheck
    if ($LASTEXITCODE -ne 0) { throw 'NAS session boundary checks failed.' }
} finally {
    $resolvedCheck = [IO.Path]::GetFullPath($checkRoot)
    $resolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($resolvedCheck.StartsWith($resolvedTemp, [StringComparison]::OrdinalIgnoreCase) -and [IO.Path]::GetFileName($resolvedCheck).StartsWith('leihuo-nas-session-check-')) {
        Remove-Item -LiteralPath $resolvedCheck -Recurse -Force
    }
}
