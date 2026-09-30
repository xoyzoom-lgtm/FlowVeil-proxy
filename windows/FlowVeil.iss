; Inno Setup script for FlowVeil (built by BUILD-FlowVeil.bat)
#define AppName "FlowVeil"
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
OutputBaseFilename=FlowVeil-Setup
SetupIconFile=v2rayN\v2rayN\Resources\v2rayN.ico
UninstallDisplayIcon={app}\FlowVeil.exe
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
Name: "desktopicon"; Description: "Создать значок FlowVeil на рабочем столе"; GroupDescription: "Значки:"
Name: "autostart"; Description: "Запускать FlowVeil вместе с Windows"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "FlowVeil\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[InstallDelete]
; Leftovers from before the Hupp -> FlowVeil rename.
Type: files; Name: "{app}\Hupp.exe"
Type: files; Name: "{userdesktop}\Hupp.lnk"
Type: files; Name: "{userstartup}\Hupp.lnk"
Type: files; Name: "{group}\Hupp.lnk"
Type: files; Name: "{group}\Удалить Hupp.lnk"

[Registry]
; flowveil:// invite links open FlowVeil and add the subscription.
Root: HKCU; Subkey: "Software\Classes\flowveil"; ValueType: string; ValueName: ""; ValueData: "URL:FlowVeil"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\Classes\flowveil"; ValueType: string; ValueName: "URL Protocol"; ValueData: ""
Root: HKCU; Subkey: "Software\Classes\flowveil\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: "{app}\FlowVeil.exe,0"
Root: HKCU; Subkey: "Software\Classes\flowveil\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\FlowVeil.exe"" ""%1"""

[Icons]
Name: "{group}\FlowVeil"; Filename: "{app}\FlowVeil.exe"
Name: "{group}\Удалить FlowVeil"; Filename: "{uninstallexe}"
Name: "{userdesktop}\FlowVeil"; Filename: "{app}\FlowVeil.exe"; Tasks: desktopicon
Name: "{userstartup}\FlowVeil"; Filename: "{app}\FlowVeil.exe"; Tasks: autostart

[Run]
Filename: "{app}\FlowVeil.exe"; Description: "{cm:LaunchProgram,FlowVeil}"; Flags: nowait postinstall skipifsilent
; Silent updates started from inside FlowVeil: relaunch it when done.
Filename: "{app}\FlowVeil.exe"; Flags: nowait; Check: WizardSilent

[Code]
// A running FlowVeil keeps the old window alive (it is single-instance and hides to tray
// instead of closing), so stop it and its core before files are replaced.
procedure KillHupp();
var
  Code: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM FlowVeil.exe /T', '', SW_HIDE, ewWaitUntilTerminated, Code);
  // Versions before the rename were called Hupp.exe.
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM Hupp.exe /T', '', SW_HIDE, ewWaitUntilTerminated, Code);
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM xray.exe /T', '', SW_HIDE, ewWaitUntilTerminated, Code);
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM sing-box.exe /T', '', SW_HIDE, ewWaitUntilTerminated, Code);
  Sleep(800);
end;

// Folder of a running FlowVeil.exe (installed or portable), or '' when none is running.
function RunningHuppDir(): String;
var
  Tmp: String;
  Lines: TArrayOfString;
  Code: Integer;
begin
  Result := '';
  Tmp := ExpandConstant('{tmp}\hupp-path.txt');
  Exec('powershell.exe',
    '-NoProfile -ExecutionPolicy Bypass -Command "$p=(Get-Process FlowVeil,Hupp -ErrorAction SilentlyContinue | Select-Object -First 1).Path; ' +
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
  if (Dir <> '') and (FileExists(AddBackslash(Dir) + 'FlowVeil.exe') or FileExists(AddBackslash(Dir) + 'Hupp.exe')) then
    WizardForm.DirEdit.Text := Dir;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  KillHupp();
  Result := '';
end;

// TUN needs administrator rights. One UAC prompt here (at install time) registers a Task Scheduler
// entry that starts FlowVeil with those rights later, so switching TUN on never asks again.
procedure RegisterTunTask();
var
  Code: Integer;
begin
  if WizardSilent then Exit; // silent updates never show a prompt; the app registers it on first TUN use
  if Exec(ExpandConstant('{sys}\schtasks.exe'), '/query /tn "FlowVeil (TUN)"', '', SW_HIDE, ewWaitUntilTerminated, Code) and (Code = 0) then Exit;
  WizardForm.StatusLabel.Caption := 'Разрешите один раз запуск для режима TUN...';
  ShellExec('runas', ExpandConstant('{app}\FlowVeil.exe'), '--register-tun-task', '', SW_HIDE, ewWaitUntilTerminated, Code);
end;

// If the Xray core was not bundled at build time, fetch it during installation.
procedure CurStepChanged(CurStep: TSetupStep);
var
  Zip, Dir, Cmd: String;
  Code: Integer;
begin
  if CurStep = ssPostInstall then RegisterTunTask();
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
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM FlowVeil.exe /T"; Flags: runhidden; RunOnceId: "KillHupp"
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM xray.exe /T"; Flags: runhidden; RunOnceId: "KillXray"
; The elevated start task that lets TUN run without a UAC prompt every time.
Filename: "{sys}\schtasks.exe"; Parameters: "/delete /f /tn ""FlowVeil (TUN)"""; Flags: runhidden; RunOnceId: "DelTunTask"

[UninstallDelete]
Type: filesandordirs; Name: "{app}\bin"
