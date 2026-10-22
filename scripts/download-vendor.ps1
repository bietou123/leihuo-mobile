$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$vendorPath = Join-Path $projectRoot 'app/src/main/assets/vendor'
$downloadPath = Join-Path $env:LOCALAPPDATA 'leihuo-vendor-downloads'
New-Item -ItemType Directory -Force -Path $downloadPath, $vendorPath | Out-Null
$licensePath = Join-Path $vendorPath 'licenses'
New-Item -ItemType Directory -Force -Path $licensePath | Out-Null
$packages = @(
  @{ Name='marked'; Version='12.0.2'; Files=@{'package/marked.min.js'='marked.min.js'} },
  @{ Name='dompurify'; Version='3.2.7'; Files=@{'package/dist/purify.min.js'='purify.min.js'} },
  @{ Name='@highlightjs/cdn-assets'; Version='11.11.1'; Files=@{'package/highlight.min.js'='highlight.min.js'} },
  @{ Name='pdfjs-dist'; Version='3.11.174'; Files=@{'package/build/pdf.min.js'='pdf.min.js';'package/build/pdf.worker.min.js'='pdf.worker.min.js'} },
  @{ Name='mammoth'; Version='1.9.1'; Files=@{'package/mammoth.browser.min.js'='mammoth.browser.min.js'} }
)
foreach ($pkg in $packages) {
  $packageLeaf = ($pkg.Name -split '/')[-1]
  $archive = Join-Path $downloadPath "$packageLeaf-$($pkg.Version).tgz"
  $unpackPath = Join-Path $downloadPath "$packageLeaf-$($pkg.Version)"
  if (!(Test-Path -LiteralPath $archive)) {
    $url = "https://registry.npmjs.org/$($pkg.Name)/-/$packageLeaf-$($pkg.Version).tgz"
    Invoke-WebRequest -Uri $url -OutFile $archive -UseBasicParsing
  }
  New-Item -ItemType Directory -Force -Path $unpackPath | Out-Null
  & tar -xzf $archive -C $unpackPath
  if ($LASTEXITCODE -ne 0) { throw "Cannot extract $archive" }
  foreach ($source in $pkg.Files.Keys) {
    Copy-Item -LiteralPath (Join-Path $unpackPath $source) -Destination (Join-Path $vendorPath $pkg.Files[$source]) -Force
  }
  $licenseFile = Get-ChildItem -LiteralPath (Join-Path $unpackPath 'package') -File | Where-Object { $_.Name -match '^LICENSE(?:\.|$)' } | Select-Object -First 1
  if ($licenseFile) { Copy-Item -LiteralPath $licenseFile.FullName -Destination (Join-Path $licensePath "$packageLeaf-LICENSE.txt") -Force }
  if ($pkg.Name -eq 'pdfjs-dist') {
    Copy-Item -LiteralPath (Join-Path $unpackPath 'package/cmaps') -Destination $vendorPath -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $unpackPath 'package/standard_fonts') -Destination $vendorPath -Recurse -Force
  }
  Write-Output "Saved $($pkg.Name) $($pkg.Version)"
}
