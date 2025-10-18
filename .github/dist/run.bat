@echo off
title QuarryVision
cd /d "%~dp0"
set "JAVA=%~dp0jre\bin\javaw.exe"
set OPTS=-Xms256m -Xmx1024m -Xss512k -XX:MaxDirectMemorySize=512m ^
 -Dorg.bytedeco.javacpp.maxbytes=4G ^
 -Dorg.bytedeco.javacpp.maxphysicalbytes=4G
start "" "%JAVA%" %OPTS% -cp "%~dp0config;%~dp0app\quarry-vision.jar" com.quarryvision.app.Boot
