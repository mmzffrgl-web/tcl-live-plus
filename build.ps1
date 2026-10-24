# Builds TCL-Live-Plus.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Requires: JDK 17+ (JAVA_HOME) and Android SDK build-tools 34+ / platform android-34.
#
# Build happens in an ASCII temp directory because the project path contains
# Turkish characters (aapt2/d8 dislike non-ASCII paths on Windows).
param(
    [string]$Sdk = $env:ANDROID_HOME
)

$ErrorActionPreference = 'Stop'
$src = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not $Sdk -or -not (Test-Path $Sdk)) {
    foreach ($p in @("$env:LOCALAPPDATA\Android\Sdk", $env:ANDROID_SDK_ROOT, "$env:USERPROFILE\Android\Sdk")) {
        if ($p -and (Test-Path "$p\build-tools")) { $Sdk = $p; break }
    }
}
if (-not $Sdk -or -not (Test-Path "$Sdk\build-tools")) {
    throw "Android SDK bulunamadi. ANDROID_HOME ayarla veya -Sdk ver."
}

$bt = Get-ChildItem "$Sdk\build-tools" -Directory | Where-Object { Test-Path "$($_.FullName)\d8.bat" } | Sort-Object { [version]($_.Name -replace '[^0-9.].*$','') } -Descending | Select-Object -First 1
if (-not $bt) { throw "build-tools bulunamadi." }
$btDir = $bt.FullName

$platform = Get-ChildItem "$Sdk\platforms" -Directory -Filter "android-*" | Sort-Object Name -Descending | Select-Object -First 1
if (-not $platform) { throw "platform android-34 bulunamadi." }
$androidJar = Join-Path $platform.FullName "android.jar"

$javaHome = $env:JAVA_HOME
if (-not $javaHome) { throw "JAVA_HOME ayarli degil (JDK 17+)." }
$javac = Join-Path $javaHome "bin\javac.exe"
$keytool = Join-Path $javaHome "bin\keytool.exe"

$aapt2 = Join-Path $btDir "aapt2.exe"
$d8 = Join-Path $btDir "d8.bat"
$zipalign = Join-Path $btDir "zipalign.exe"
$apksigner = Join-Path $btDir "apksigner.bat"

foreach ($t in @($aapt2, $d8, $zipalign, $apksigner, $javac)) {
    if (-not (Test-Path $t)) { throw "Arac bulunamadi: $t" }
}

# --- ASCII calisma dizini
$work = Join-Path $env:TEMP "tcl-live-build"
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Path $work | Out-Null
Copy-Item "$src\AndroidManifest.xml" $work
Copy-Item "$src\res" $work -Recurse
Copy-Item "$src\src" $work -Recurse
Copy-Item "$src\assets" $work -Recurse

New-Item -ItemType Directory -Path "$work\out\gen" | Out-Null
New-Item -ItemType Directory -Path "$work\out\classes" | Out-Null
New-Item -ItemType Directory -Path "$work\out\dex" | Out-Null

Write-Host "[1/6] aapt2 compile"
& $aapt2 compile --dir "$work\res" -o "$work\out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[2/6] aapt2 link"
& $aapt2 link -o "$work\out\base.apk" -I $androidJar --manifest "$work\AndroidManifest.xml" `
    -R "$work\out\res.zip" --java "$work\out\gen" --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[3/6] javac"
$sources = Get-ChildItem "$work\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$rjava = Get-ChildItem "$work\out\gen" -Recurse -Filter R.java | ForEach-Object { $_.FullName }
$allSources = @($sources) + @($rjava)
$ErrorActionPreference = 'Continue'
& $javac --release 11 -encoding UTF-8 -classpath $androidJar -d "$work\out\classes" $allSources 2>&1 | ForEach-Object { Write-Host $_ }
$javacRc = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($javacRc -ne 0) { throw "javac failed" }

Write-Host "[4/6] d8"
$classes = Get-ChildItem "$work\out\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
$ErrorActionPreference = 'Continue'
& $d8 --release --min-api 28 --lib $androidJar --output "$work\out\dex" $classes 2>&1 | ForEach-Object { Write-Host $_ }
$d8Rc = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($d8Rc -ne 0) { throw "d8 failed" }

Write-Host "[5/6] paketleme"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$work\out\base.apk", [System.IO.Compression.ZipArchiveMode]::Update)
$dexEntry = $zip.GetEntry("classes.dex")
if ($dexEntry) { $dexEntry.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$work\out\dex\classes.dex", "classes.dex") | Out-Null
# aapt2 on Windows stores asset paths with backslashes; AssetManager needs '/'.
$assetRoot = "$work\assets"
Get-ChildItem $assetRoot -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($assetRoot.Length + 1).Replace('\', '/')
    $entryName = "assets/$rel"
    $existing = $zip.GetEntry($entryName)
    if ($existing) { $existing.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $entryName) | Out-Null
}
$zip.Dispose()

& $zipalign -f 4 "$work\out\base.apk" "$work\out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[6/6] imzalama"
$ks = Join-Path $src "debug.keystore"
$ErrorActionPreference = 'Continue'
if (-not (Test-Path $ks)) {
    & $keytool -genkeypair -v -keystore $ks -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=TCL Live Plus, OU=dev, O=dev, L=dev, S=dev, C=TR" 2>&1 | ForEach-Object { Write-Host $_ }
}
$keytoolRc = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($keytoolRc -ne 0) { throw "keytool failed" }
$ErrorActionPreference = 'Continue'
& $apksigner sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out "$work\TCL-Live-Plus.apk" "$work\out\aligned.apk" 2>&1 | ForEach-Object { Write-Host $_ }
$signRc = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($signRc -ne 0) { throw "apksigner failed" }

Copy-Item "$work\TCL-Live-Plus.apk" "$src\TCL-Live-Plus.apk" -Force

Write-Host ""
Write-Host "OK -> $src\TCL-Live-Plus.apk"
Write-Host "Kurulum: adb install -r TCL-Live-Plus.apk"
