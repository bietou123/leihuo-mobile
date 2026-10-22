param(
    [string]$BuildRoot = (Join-Path $env:USERPROFILE '.local/share/leihuo-build'),
    [string]$Output = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jdkRoot = Join-Path $BuildRoot 'jdk'
$jdk = Get-ChildItem -LiteralPath $jdkRoot -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') } | Select-Object -First 1
if (-not $jdk) { throw '请先在本机构建目录安装 JDK 17。' }
$gradle = Join-Path $BuildRoot 'gradle/gradle-8.10.2/bin/gradle.bat'
$sdk = Join-Path $BuildRoot 'android-sdk'
if (-not (Test-Path -LiteralPath $gradle)) { throw '缺少 Gradle 8.10.2。' }
if (-not (Test-Path -LiteralPath (Join-Path $sdk 'platforms/android-35/android.jar'))) { throw '缺少 Android SDK 35。' }

# Tool paths apply only to this PowerShell process; system environment is not modified.
$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:GRADLE_USER_HOME = Join-Path $BuildRoot 'gradle-cache'
$env:PATH = "$($jdk.FullName)/bin;$env:PATH"

# Preserve a separate local release key so subsequent APKs can update this installation.
$signingRoot = Join-Path $BuildRoot 'signing'
New-Item -ItemType Directory -Force -Path $signingRoot | Out-Null
$signingFile = Join-Path $signingRoot 'leihuo-release.jks'
$passwordFile = Join-Path $signingRoot 'leihuo-release.password'
if (-not (Test-Path -LiteralPath $passwordFile)) {
    $random = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $generator.GetBytes($random)
    $generator.Dispose()
    $password = [Convert]::ToBase64String($random)
    [System.IO.File]::WriteAllText($passwordFile, $password, (New-Object System.Text.UTF8Encoding($false)))
}
$password = [System.IO.File]::ReadAllText($passwordFile).Trim()
$env:LEIHUO_SIGNING_FILE = $signingFile
$env:LEIHUO_SIGNING_PASSWORD = $password
if (-not (Test-Path -LiteralPath $signingFile)) {
    & (Join-Path $jdk.FullName 'bin/keytool.exe') -genkeypair -keystore $signingFile -storetype JKS -alias leihuo -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Leihuo Personal App' -storepass:env LEIHUO_SIGNING_PASSWORD -keypass:env LEIHUO_SIGNING_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw '无法创建本地 APK 签名。' }
}

Push-Location -LiteralPath $projectRoot
try {
    & $gradle --no-daemon --console=plain assembleRelease
    if ($LASTEXITCODE -ne 0) { throw 'APK 构建失败。' }
    $apk = Join-Path $projectRoot 'app/build/outputs/apk/release/app-release.apk'
    if (-not (Test-Path -LiteralPath $apk)) { throw '构建完成但找不到 APK。' }
    if ($Output) {
        Copy-Item -LiteralPath $apk -Destination $Output -Force
        Write-Output "APK: $Output"
    } else {
        Write-Output "APK: $apk"
    }
} finally {
    Pop-Location
    Remove-Item Env:LEIHUO_SIGNING_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:LEIHUO_SIGNING_FILE -ErrorAction SilentlyContinue
}
