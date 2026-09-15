param(
    [string]$StoreFile = 'C:\Users\Administrator\HavenCast-keys\havencast-app-signing.p12',
    [string]$KeyAlias = 'havencast-app-signing'
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$resolvedStoreFile = (Resolve-Path -LiteralPath $StoreFile).Path
$repoPrefix = $repoRoot.TrimEnd('\') + '\'
$originalTemp = $env:TEMP
$originalTmp = $env:TMP
$shortTemp = 'C:\jtmp'

if ($resolvedStoreFile.StartsWith($repoPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The app signing keystore must be outside the repository.'
}

$securePassword = Read-Host 'Enter the HavenCast app signing key password' -AsSecureString
$plainPassword = [System.Net.NetworkCredential]::new('', $securePassword).Password

try {
    New-Item -ItemType Directory -Force -Path $shortTemp | Out-Null
    $env:TEMP = $shortTemp
    $env:TMP = $shortTemp
    $env:HAVENCAST_RELEASE_STORE_FILE = $resolvedStoreFile
    $env:HAVENCAST_RELEASE_STORE_PASSWORD = $plainPassword
    $env:HAVENCAST_RELEASE_KEY_ALIAS = $KeyAlias
    $env:HAVENCAST_RELEASE_KEY_PASSWORD = $plainPassword
    $env:GRADLE_USER_HOME = Join-Path $repoRoot '.gradle-home'
    $env:ANDROID_USER_HOME = Join-Path $repoRoot '.android-home'

    & (Join-Path $repoRoot 'gradlew.bat') --no-daemon :app:assembleRelease
    if ($LASTEXITCODE -ne 0) {
        throw "assembleRelease failed with exit code $LASTEXITCODE"
    }

    $apkPath = Join-Path $repoRoot 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) {
        throw "Release APK was not found: $apkPath"
    }

    $sdkLine = Get-Content -LiteralPath (Join-Path $repoRoot 'local.properties') |
        Where-Object { $_ -like 'sdk.dir=*' } |
        Select-Object -First 1
    if (-not $sdkLine) {
        throw 'sdk.dir was not found in local.properties.'
    }
    $sdkDir = $sdkLine.Substring('sdk.dir='.Length).Replace('\:', ':').Replace('\\', '\')
    $apksigner = Get-ChildItem -Path (Join-Path $sdkDir 'build-tools\*\apksigner.bat') |
        Sort-Object { [version]$_.Directory.Name } -Descending |
        Select-Object -First 1
    if (-not $apksigner) {
        throw 'apksigner.bat was not found in Android SDK build-tools.'
    }

    & $apksigner.FullName verify --verbose --print-certs $apkPath
    if ($LASTEXITCODE -ne 0) {
        throw "APK signature verification failed with exit code $LASTEXITCODE"
    }

    Write-Host ''
    Write-Host "Direct-distribution APK generated and signature verified: $apkPath" -ForegroundColor Green
}
finally {
    Remove-Item Env:HAVENCAST_RELEASE_STORE_FILE -ErrorAction SilentlyContinue
    Remove-Item Env:HAVENCAST_RELEASE_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:HAVENCAST_RELEASE_KEY_ALIAS -ErrorAction SilentlyContinue
    Remove-Item Env:HAVENCAST_RELEASE_KEY_PASSWORD -ErrorAction SilentlyContinue
    $env:TEMP = $originalTemp
    $env:TMP = $originalTmp
    $plainPassword = $null
    if ($null -ne $securePassword) {
        $securePassword.Dispose()
    }
}
