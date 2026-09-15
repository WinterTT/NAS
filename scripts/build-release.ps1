param(
    [string]$StoreFile = 'C:\Users\Administrator\HavenCast-keys\havencast-upload.p12',
    [string]$KeyAlias = 'havencast-upload'
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$resolvedStoreFile = (Resolve-Path -LiteralPath $StoreFile).Path
$repoPrefix = $repoRoot.TrimEnd('\') + '\'
$originalTemp = $env:TEMP
$originalTmp = $env:TMP
$shortTemp = 'C:\jtmp'

if ($resolvedStoreFile.StartsWith($repoPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The upload keystore must be outside the repository.'
}

$securePassword = Read-Host 'Enter the HavenCast upload key password' -AsSecureString
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

    & (Join-Path $repoRoot 'gradlew.bat') --no-daemon :app:bundleRelease
    if ($LASTEXITCODE -ne 0) {
        throw "bundleRelease failed with exit code $LASTEXITCODE"
    }

    $bundlePath = Join-Path $repoRoot 'app\build\outputs\bundle\release\app-release.aab'
    if (-not (Test-Path -LiteralPath $bundlePath -PathType Leaf)) {
        throw "Release AAB was not found: $bundlePath"
    }

    & jarsigner -verify -verbose -certs $bundlePath
    if ($LASTEXITCODE -ne 0) {
        throw "AAB signature verification failed with exit code $LASTEXITCODE"
    }

    Write-Host ''
    Write-Host 'Upload certificate:'
    & keytool -printcert -jarfile $bundlePath
    if ($LASTEXITCODE -ne 0) {
        throw "Reading the AAB certificate failed with exit code $LASTEXITCODE"
    }

    Write-Host ''
    Write-Host "Release AAB generated and signature verified: $bundlePath" -ForegroundColor Green
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
