@echo off
setlocal
cd /d "%~dp0"
set "DOTNET=dotnet"
where dotnet >nul 2>nul || (
  echo === Installing .NET 10 SDK via winget ^(one time^) ===
  winget install -e --id Microsoft.DotNet.SDK.10 --accept-package-agreements --accept-source-agreements --silent
  set "DOTNET=%ProgramFiles%\dotnet\dotnet.exe"
)
if not "%DOTNET%"=="dotnet" if not exist "%DOTNET%" (
  echo [!] .NET 10 SDK was not installed. Install it: https://dotnet.microsoft.com/download/dotnet/10.0
  start https://dotnet.microsoft.com/download/dotnet/10.0
  pause & exit /b 1
)
if exist "Hupp" rmdir /s /q "Hupp"
echo === Building Hupp for Windows (1-3 min) ===
"%DOTNET%" publish "v2rayN\v2rayN\v2rayN.csproj" -c Release -r win-x64 -p:SelfContained=true -o "Hupp"
if errorlevel 1 ( echo [!] Build failed, send a screenshot of this window. & pause & exit /b 1 )

echo === Downloading Xray core ===
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol='Tls12';" ^
  "$z=Join-Path $env:TEMP 'xray-win64.zip'; $t=Join-Path $env:TEMP 'xray-win64';" ^
  "Invoke-WebRequest 'https://github.com/XTLS/Xray-core/releases/latest/download/Xray-windows-64.zip' -OutFile $z;" ^
  "if (Test-Path $t) { Remove-Item $t -Recurse -Force }; Expand-Archive $z $t;" ^
  "New-Item -ItemType Directory -Force 'Hupp\bin\xray' | Out-Null;" ^
  "Copy-Item (Join-Path $t 'xray.exe') 'Hupp\bin\xray\' -Force;" ^
  "Copy-Item (Join-Path $t '*.dat') 'Hupp\bin\' -Force"
if errorlevel 1 echo [!] Core download failed. Start Hupp anyway and use Help - Check update - Xray-Core.

ren "Hupp\v2rayN.exe" "Hupp.exe" 2>nul

echo === Making installer Hupp-Setup.exe ===
call :findiscc
if not defined ISCC (
  echo Installing Inno Setup via winget...
  winget install -e --id JRSoftware.InnoSetup --accept-package-agreements --accept-source-agreements --silent
  call :findiscc
)
if not defined ISCC (
  echo [!] Inno Setup not found. Install it: https://jrsoftware.org/isdl.php and run this file again.
  echo     Or just use the ready folder Hupp\Hupp.exe
  explorer "Hupp" & pause & exit /b 1
)
"%ISCC%" /Q "Hupp.iss"
if errorlevel 1 ( echo [!] Installer build failed. & pause & exit /b 1 )
echo.
echo === Done! Hupp-Setup.exe is ready ===
explorer /select,"%~dp0Hupp-Setup.exe"
pause
exit /b 0

:findiscc
set "ISCC="
for %%P in ("%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe" "%ProgramFiles%\Inno Setup 6\ISCC.exe" "%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe") do if exist %%P set "ISCC=%%~P"
exit /b 0
