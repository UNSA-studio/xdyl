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
; 官方 Inno 安装包不含中文 isl（只有第三方语言包），
; 所以这里不引用 MessagesFile，直接用 default + 中文自定义消息。
Name: "default"; MessagesFile: "compiler:Default.isl"

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

[Messages]
; 界面文案中文化（default 语言包自带英文，这里覆盖常用项）
SetupAppTitle=安装 - {#AppName}
SetupWindowTitle=安装 {#AppName}，版本 {#AppVersion}
WelcomeLabel1=欢迎安装 [name]
WelcomeLabel2=即将在你的电脑上安装 [name/ver]。%n%n建议关闭其他应用程序后继续。
WizardSelectDir=选择安装位置
SelectDirDesc=将 [name] 安装到哪里？
SelectDirLabel3=安装程序将把 [name] 安装到以下文件夹。
SelectDirBrowseLabel=点击“下一步”继续；如需更改位置，点击“浏览”。
WizardReady=准备安装
ReadyLabel1=安装程序已准备好，即将开始安装 [name]。
ReadyLabel2a=点击“安装”开始安装；如需检查或更改设置，点击“上一步”。
WizardInstalling=正在安装
InstallingLabel=正在安装 [name]，请稍候...
FinishedHeadingLabel=[name] 安装完成
FinishedLabelNoIcons=安装程序已完成 [name] 的安装。
FinishedLabel=安装程序已完成 [name] 的安装。
ClickFinish=点击“完成”退出安装程序。
ButtonNext=下一步(&N) >
ButtonBack=< 上一步(&B)
ButtonInstall=安装(&I)
ButtonFinish=完成(&F)
ButtonCancel=取消
ButtonBrowse=浏览(&R)...
