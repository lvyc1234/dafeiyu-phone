$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$bt = Join-Path $sdk "build-tools\34.0.0"
$androidJar = Join-Path $sdk "platforms\android-34\android.jar"
$aapt2 = Join-Path $bt "aapt2.exe"
$zipalign = Join-Path $bt "zipalign.exe"
$apksigner = Join-Path $bt "apksigner.bat"
$d8 = Join-Path $bt "d8.bat"
$jdk = Join-Path $env:ProgramFiles "Java\jdk-17"
$javac = Join-Path $jdk "bin\javac.exe"
$jarExe = Join-Path $jdk "bin\jar.exe"
$keytool = Join-Path $jdk "bin\keytool.exe"
$env:JAVA_HOME = $jdk
$env:Path = "$(Join-Path $jdk 'bin');$env:Path"
$build = Join-Path $root "build"
$dist = Join-Path $root "dist"

if (-not (Test-Path $aapt2)) { throw "aapt2 not found: $aapt2" }
if (-not (Test-Path $androidJar)) { throw "android.jar not found: $androidJar" }

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force -Path $build, (Join-Path $build "classes"), (Join-Path $build "dex"), $dist | Out-Null

Write-Host "[1/7] compile resources"
& $aapt2 compile --dir (Join-Path $root "res") -o (Join-Path $build "res.zip")

Write-Host "[2/7] link resources + assets"
& $aapt2 link `
    -o (Join-Path $build "app-unsigned.apk") `
    -I $androidJar `
    --manifest (Join-Path $root "AndroidManifest.xml") `
    -A (Join-Path $root "assets") `
    --auto-add-overlay `
    --min-sdk-version 19 `
    --target-sdk-version 34 `
    (Join-Path $build "res.zip")

Write-Host "[3/7] compile java"
$sources = Get-ChildItem (Join-Path $root "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$androidJar;$bt\core-lambda-stubs.jar" -d (Join-Path $build "classes") $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[4/7] d8"
& $jarExe cf (Join-Path $build "classes.jar") -C (Join-Path $build "classes") .
& $d8 --lib $androidJar --min-api 19 --output (Join-Path $build "dex") (Join-Path $build "classes.jar")
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[5/7] add classes.dex"
& $jarExe uf (Join-Path $build "app-unsigned.apk") -C (Join-Path $build "dex") classes.dex

Write-Host "[6/7] zipalign"
& $zipalign -f 4 (Join-Path $build "app-unsigned.apk") (Join-Path $build "app-aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[7/7] sign"
$ks = Join-Path $root "debug.keystore"
if (-not (Test-Path $ks)) {
    & $keytool -genkeypair -keystore $ks -alias androiddebugkey -storepass android -keypass android `
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" | Out-Null
}
$out = Join-Path $dist "DafeiyuBoot.apk"
& $apksigner sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --min-sdk-version 18 --v1-signing-enabled true --v2-signing-enabled true --out $out (Join-Path $build "app-aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
& $apksigner verify --verbose $out

Get-Item $out | Select-Object FullName, Length, LastWriteTime
