@echo off
title QuarryVision
cd /d "%~dp0"

:: путь к встроенной Java
set JAVA="%~dp0jre\bin\javaw.exe"

:: параметры JVM (ограничения и защита от утечек)
set OPTS=-Xms256m -Xmx1024m -Xss512k -XX:MaxDirectMemorySize=512m ^
 -Dorg.bytedeco.javacpp.maxbytes=4G ^
 -Dorg.bytedeco.javacpp.maxphysicalbytes=4G

:: запуск приложения с внешним конфигом
start "" "%JAVA%" %OPTS% -cp "%~dp0config;%~dp0app\quarry-vision-1.0-SNAPSHOT.jar" com.quarryvision.app.Boot
exit