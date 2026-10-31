# Cria (uma vez) e abre um emulador parecido com o SM-T561: Android 7.1, 800x1280, ~200 dpi, 1,5 GB de RAM.
# Uso:  ./emulador.ps1            abre o emulador
#       ./emulador.ps1 -Instalar  compila o app, instala no emulador e abre o Folio
param([switch]$Instalar)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:ANDROID_SDK_ROOT = 'C:\Android\Sdk'
$sdk = 'C:\Android\Sdk'
$name = 'folio_tab'
$avdm = "$sdk\cmdline-tools\latest\bin\avdmanager.bat"
$emu = "$sdk\emulator\emulator.exe"
$adb = "$sdk\platform-tools\adb.exe"

if (-not (& $emu -list-avds | Select-String -SimpleMatch $name)) {
    'no' | & $avdm create avd -n $name -k 'system-images;android-25;default;x86' -d 'Nexus 7' --force
    $ini = "$env:USERPROFILE\.android\avd\$name.avd\config.ini"
    Add-Content $ini @'
hw.lcd.width=800
hw.lcd.height=1280
hw.lcd.density=200
hw.ramSize=1024
hw.keyboard=yes
disk.dataPartition.size=2048M
'@
}

if (-not (& $adb devices | Select-String 'emulator-')) {
    Start-Process $emu -ArgumentList "-avd $name -no-snapshot -gpu swiftshader_indirect -cores 2 -no-boot-anim"
    & $adb -e wait-for-device
    do { Start-Sleep 3 } until ((& $adb -e shell getprop sys.boot_completed 2>$null) -match '1')
}

if ($Instalar) {
    & "$PSScriptRoot\build.ps1"
    & $adb -e install -r "$PSScriptRoot\build\folio.apk"
    & $adb -e shell mkdir -p /sdcard/Folio
    Get-ChildItem "$PSScriptRoot\amostras" -File -ErrorAction SilentlyContinue |
        ForEach-Object { & $adb -e push $_.FullName "/sdcard/Folio/$($_.Name)" }
    & $adb -e shell am start -n com.folio.launcher/.MainActivity
}
