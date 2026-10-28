@echo off
rem Build mini-redis with the JDK only (no Maven, no third-party jars).
setlocal
set "JDK=E:\jdk"
set "JAVAC=%JDK%\bin\javac.exe"
set "JAR=%JDK%\bin\jar.exe"

if not exist "%JAVAC%" (
  echo [build] ERROR: javac not found at %JAVAC%
  exit /b 1
)

if not exist build\classes mkdir build\classes
if not exist dist mkdir dist

echo [build] compiling...
"%JAVAC%" -encoding UTF-8 -d build\classes -sourcepath src\main\java src\main\java\com\fyb\miniredis\MiniRedisServer.java
if errorlevel 1 (
  echo [build] FAILED
  exit /b 1
)

echo [build] packaging dist\miniredis.jar...
"%JAR%" --create --file dist\miniredis.jar --main-class com.fyb.miniredis.MiniRedisServer -C build\classes .
if errorlevel 1 (
  echo [build] jar FAILED
  exit /b 1
)

echo [build] OK: dist\miniredis.jar
endlocal