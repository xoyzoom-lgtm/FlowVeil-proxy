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
CloseApplications=yes

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
