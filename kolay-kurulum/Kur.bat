@echo off
setlocal EnableExtensions
chcp 65001 >nul
title TCL Live+ Kolay Kurulum
color 0F

set "ROOT=%~dp0"
set "APK=%ROOT%TCL-Live-Plus.apk"
set "ADB="
set "TEMP_LIST=%TEMP%\tcl_live_devices.txt"

echo ============================================
echo   TCL Live+ - Kolay Kurulum
echo ============================================
echo.

if not exist "%APK%" (
  echo [HATA] TCL-Live-Plus.apk bulunamadi.
  echo Bu dosyayi Kur.bat ile ayni klasore koyun.
  echo.
  pause
  exit /b 1
)

where adb >nul 2>&1
if not errorlevel 1 (
  set "ADB=adb"
  goto :adb_ready
)
if exist "%ROOT%platform-tools\adb.exe" (
  set "ADB=%ROOT%platform-tools\adb.exe"
  goto :adb_ready
)
if exist "%LOCALAPPDATA%\TCL-Live-Plus\platform-tools\adb.exe" (
  set "ADB=%LOCALAPPDATA%\TCL-Live-Plus\platform-tools\adb.exe"
  goto :adb_ready
)

echo ADB indiriliyor... (ilk seferde, yaklasik 10 MB)
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $zip=Join-Path $env:TEMP 'platform-tools.zip'; Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip; $dest=Join-Path $env:LOCALAPPDATA 'TCL-Live-Plus'; if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }; Expand-Archive -Path $zip -DestinationPath $dest -Force; Remove-Item $zip -Force"
if errorlevel 1 (
  echo.
  echo [HATA] ADB indirilemedi.
  echo Internet baglantisini kontrol edin.
  echo Alternatif: platform-tools klasorunu bu dosyanin yanina cikarin.
  echo.
  pause
  exit /b 1
)
set "ADB=%LOCALAPPDATA%\TCL-Live-Plus\platform-tools\adb.exe"
if not exist "%ADB%" (
  echo [HATA] ADB bulunamadi.
  pause
  exit /b 1
)

:adb_ready
echo ADB hazir.
"%ADB%" start-server >nul 2>&1

:check_device
echo.
echo TV baglantisi kontrol ediliyor...
"%ADB%" devices > "%TEMP_LIST%" 2>&1
set "FOUND="
set "UNAUTH="
for /f "usebackq skip=1 tokens=1,2" %%a in ("%TEMP_LIST%") do (
  if "%%b"=="device" set "FOUND=%%a"
  if "%%b"=="unauthorized" set "UNAUTH=%%a"
)
if defined FOUND goto :install

if defined UNAUTH (
  echo.
  echo TV bagli ama izin bekliyor: %UNAUTH%
  echo TV ekranindaki "Allow USB debugging" / "Izin ver" penceresini onaylayin.
  echo.
  pause
  goto :check_device
)

echo.
echo Bagli TV bulunamadi.
echo.
echo TV'de su ayarlar acik olmali:
echo   - Gelistirici secenekleri
echo   - USB hata ayiklama / ADB hata ayiklama
echo   - TV ve bilgisayar ayni Wi-Fi aginda
echo.
echo TV'nin IP adresini yazin (ornek: 192.168.1.50).
echo Port yazmayin. Bos birakip Enter'a basarsaniz cikilir.
set /p TVIP=TV IP: 
if "%TVIP%"=="" goto :cancel
"%ADB%" connect %TVIP%:5555
echo.
echo TV ekraninda izin penceresi cikarsa onaylayin.
timeout /t 3 /nobreak >nul
goto :check_device

:install
echo.
echo TV bulundu: %FOUND%
echo Uygulama yukleniyor...
"%ADB%" -s %FOUND% install -r "%APK%"
if errorlevel 1 (
  echo.
  echo [HATA] Yukleme basarisiz.
  echo TV ekranindaki izin penceresini onaylayin.
  echo Onceki bir surum kuruluysa once kaldirin:
  echo   "%ADB%" -s %FOUND% uninstall io.tcllive.overlay
  echo.
  pause
  exit /b 1
)

echo.
echo Uygulama yuklendi.
echo.
echo TV'de Erisilebilirlik ayarlari aciliyor...
"%ADB%" -s %FOUND% shell am start -a android.settings.ACCESSIBILITY_SETTINGS >nul 2>&1
echo.
echo ============================================
echo   SON ADIM (TV'de yapilacak)
echo ============================================
echo 1) TV'de "TCL Live+" servisini bulun ve ACIN.
echo 2) Bu pencerede Enter'a basin; uygulama acilacak.
echo.
pause
"%ADB%" -s %FOUND% shell am start -n io.tcllive.overlay/.MainActivity >nul 2>&1
echo.
echo Kurulum tamamlandi.
echo Uygulamada "Kanallari ice aktar" ile baslayin.
echo.
pause
exit /b 0

:cancel
echo.
echo Islem iptal edildi.
pause
exit /b 0
