# Java 8 离线安装脚本
$javaUrl = "https://javadl.oracle.com/webapps/download/AutoDL?BundleId=246808_2dee051a5d0647d5be72a7c0ab2a7d02"
$installer = "$env:TEMP\java8_installer.exe"

Write-Host "Downloading Java 8..."
Invoke-WebRequest -Uri $javaUrl -OutFile $installer

Write-Host "Installing Java 8 (silent mode)..."
Start-Process -FilePath $installer -ArgumentList "/s" -Wait

Write-Host "Java 8 installation completed."