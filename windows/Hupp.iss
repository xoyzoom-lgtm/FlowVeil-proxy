; Inno Setup script for Hupp (built by BUILD-Hupp.bat)
#define AppName "Hupp"
#define AppVersion "1.0"

[Setup]
AppId={{6F1C2A7E-4B7D-4E0B-9C51-2D8E3A9F7B14}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppName}
DefaultDirName={localappdata}\Programs\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=.
OutputBaseFilename=Hupp-Setup
SetupIconFile=v2rayN\v2rayN\Resources\v2rayN.ico
UninstallDisplayIcon={app}\Hupp.exe
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=no
; Reinstalling over an existing copy just updates it in place (same folder, settings kept).
UsePreviousAppDir=yes
UsePreviousTasks=yes
DisableDirPage=auto
DisableReadyPage=yes

[Languages]
Name: "ru"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Создать значок Hupp на рабочем столе"; GroupDescription: "Значки:"
Name: "autostart"; Description: "Запускать Hupp вместе с Windows"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "Hupp\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Hupp"; Filename: "{app}\Hupp.exe"
Name: "{group}\Удалить Hupp"; Filename: "{uninstallexe}"
Name: "{userdesktop}\Hupp"; Filename: "{app}\Hupp.exe"; Tasks: desktopicon
Name: "{userstartup}\Hupp"; Filename: "{app}\Hupp.exe"; Tasks: autostart

[Run]
Filename: "{app}\Hupp.exe"; Description: "{cm:LaunchProgram,Hupp}"; Flags: nowait postinstall skipifsilent

[Code]
// A running Hupp keeps the old window alive (it is single-instance and hides to tray
// instead of closing), so stop it and its core before files are replaced.
procedure KillHupp();
var
  Code: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM Hupp.exe /T', '', SW_HIDE, ewWaitUntilTerminated, Code);
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM xray.exe /T', '', SW_HIDE, ewWaitUntilTerminated, Code);
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM sing-box.exe /T', '', SW_HIDE, ewWaitUntilTerminated, Code);
  Sleep(800);
end;

// Folder of a running Hupp.exe (installed or portable), or '' when none is running.
function RunningHuppDir(): String;
var
  Tmp: String;
  Lines: TArrayOfString;
  Code: Integer;
begin
  Result := '';
  Tmp := ExpandConstant('{tmp}\hupp-path.txt');
  Exec('powershell.exe',
    '-NoProfile -ExecutionPolicy Bypass -Command "$p=(Get-Process Hupp -ErrorAction SilentlyContinue | Select-Object -First 1).Path; ' +
    'if ($p) { Set-Content -Encoding UTF8 -Path ''' + Tmp + ''' -Value (Split-Path $p) }"',
    '', SW_HIDE, ewWaitUntilTerminated, Code);
  if LoadStringsFromFile(Tmp, Lines) and (GetArrayLength(Lines) > 0) then
    Result := Trim(Lines[0]);
end;

procedure InitializeWizard();
var
  Dir: String;
begin
  // Update the copy the user is actually running, even a portable one in another folder.
  Dir := RunningHuppDir();
  if (Dir <> '') and FileExists(AddBackslash(Dir) + 'Hupp.exe') then
    WizardForm.DirEdit.Text := Dir;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  KillHupp();
  Result := '';
end;

// If the Xray core was not bundled at build time, fetch it during installation.
procedure CurStepChanged(CurStep: TSetupStep);
var
  Zip, Dir, Cmd: String;
  Code: Integer;
begin
  if (CurStep = ssPostInstall) and not FileExists(ExpandConstant('{app}\bin\xray\xray.exe')) then
  begin
    WizardForm.StatusLabel.Caption := 'Скачиваю ядро Xray...';
    try
      DownloadTemporaryFile('https://github.com/XTLS/Xray-core/releases/latest/download/Xray-windows-64.zip', 'xray.zip', '', nil);
      Zip := ExpandConstant('{tmp}\xray.zip');
      Dir := ExpandConstant('{app}\bin');
      Cmd := '-NoProfile -ExecutionPolicy Bypass -Command "$t=Join-Path $env:TEMP ''hupp-xray''; Expand-Archive -Force ''' + Zip + ''' $t; ' +
             'New-Item -ItemType Directory -Force ''' + Dir + '\xray'' | Out-Null; ' +
             'Copy-Item (Join-Path $t ''xray.exe'') ''' + Dir + '\xray\'' -Force; Copy-Item (Join-Path $t ''*.dat'') ''' + Dir + ''' -Force"';
      Exec('powershell.exe', Cmd, '', SW_HIDE, ewWaitUntilTerminated, Code);
    except
      MsgBox('Не удалось скачать ядро Xray. Проверь интернет и запусти установку ещё раз.', mbError, MB_OK);
    end;
  end;
end;

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM Hupp.exe /T"; Flags: runhidden; RunOnceId: "KillHupp"
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM xray.exe /T"; Flags: runhidden; RunOnceId: "KillXray"

[UninstallDelete]
Type: filesandordirs; Name: "{app}\bin"
