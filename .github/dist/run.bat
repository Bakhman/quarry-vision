@echo off
setlocal
title QuarryVision
cd /d "%~dp0"

set "JAVA=%~dp0jre\bin\javaw.exe"

set OPTS=-Xms256m -Xmx1024m -Xss512k -XX:MaxDirectMemorySize=512m ^
 -Dorg.bytedeco.javacpp.maxbytes=4G ^
 -Dorg.bytedeco.javacpp.maxphysicalbytes=4G

set "CP=%~dp0config;%~dp0app\quarry-vision.jar"

if exist "%~dp0run.args" (
  for /f "usebackq tokens=* delims=" %%A in ("%~dp0run.args") do set "OPTS=%OPTS% %%A"
)

"%JAVA%" %OPTS% -cp "%CP%" com.quarryvision.app.Boot
endlocal
exit /b 0