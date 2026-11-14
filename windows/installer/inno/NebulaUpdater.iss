; ============================================================
;  EXE 安装包（Inno Setup）
;
;  为什么还要一个 exe：MSI 在某些精简系统 / 学校机房策略下会被拦，
;  Inno 的单文件 exe 兼容性更好，也不需要 Windows Installer 服务。
; ============================================================

#define AppName "星云更新器"
#define AppVersion "1.0.0"
#define AppPublisher "UNSA Studio"
#define AppExeName "NebulaUpdater.exe"

[Setup]
AppId={{8F3C2A41-7B5D-4E19-9C6A-2D48B1F0E7A3}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={localappdata}\Programs\NebulaUpdater
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
; 按用户安装，不请求管理员权限
PrivilegesRequired=lowest
OutputDir=..\out
OutputBaseFilename=NebulaUpdater-{#AppVersion}-x64-setup
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayIcon={app}\{#AppExeName}
SetupIconFile=..\..\src\NebulaUpdater.App\Assets\app.ico
; 不自动删用户数据，卸载时保留 .xdyl
MinVersion=10.0.17763

[Languages]
Name: "chinesesimplified"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加任务:"; Flags: unchecked

[Files]
; PublishDir 由命令行 /DPublishDir=... 传入
Source: "{#PublishDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExeName}"
Name: "{group}\卸载 {#AppName}"; Filename: "{uninstallexe}"
Name: "{userdesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExeName}"; Description: "启动 {#AppName}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; 只清安装目录自身的残留，不碰 %USERPROFILE%\.xdyl
Type: filesandordirs; Name: "{app}"
