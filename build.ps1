$ErrorActionPreference = 'Stop'
$sdk = 'C:\Android\Sdk'
$bt  = "$sdk\build-tools\34.0.0"
$jar = "$sdk\platforms\android-34\android.jar"
$out = Join-Path $PSScriptRoot 'build'

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory "$out\classes" | Out-Null

& "$bt\aapt2.exe" compile --dir "$PSScriptRoot\res" -o "$out\res.zip"
if ($LASTEXITCODE) { throw 'aapt2 compile falhou' }

$numFile = Join-Path $PSScriptRoot 'build-number.txt'
$num = 1 + $(if (Test-Path $numFile) { [int](Get-Content $numFile) } else { 1 })
Set-Content $numFile $num

& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$PSScriptRoot\AndroidManifest.xml" "$out\res.zip" `
    --min-sdk-version 25 --target-sdk-version 22 --version-code $num --version-name 0.2
if ($LASTEXITCODE) { throw 'aapt2 falhou' }

$src = Get-ChildItem "$PSScriptRoot\src" -Recurse -Filter *.java | ForEach-Object FullName
& javac -encoding UTF-8 --release 8 -Xlint:-options -cp $jar -d "$out\classes" $src
if ($LASTEXITCODE) { throw 'javac falhou' }

$cls = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName
& "$bt\d8.bat" --release --min-api 25 --lib $jar --output $out $cls
if ($LASTEXITCODE) { throw 'd8 falhou' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$out\base.apk", 'Update')
[void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\classes.dex", 'classes.dex')
$zip.Dispose()

& "$bt\zipalign.exe" -f 4 "$out\base.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'zipalign falhou' }

$ks = Join-Path $PSScriptRoot 'debug.keystore'
if (-not (Test-Path $ks)) {
    & keytool -genkeypair -keystore $ks -storepass android -keypass android -alias folio `
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Folio'
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out "$out\folio.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'apksigner falhou' }

"OK: $out\folio.apk (" + [math]::Round((Get-Item "$out\folio.apk").Length / 1KB) + ' KB)'
