@echo off
rem Start mini-redis. Any arguments are passed through, e.g.:
rem   run.cmd --port=6400 --appendonly=no
setlocal
set "JAVA=E:\jdk\bin\java.exe"

if not exist dist\miniredis.jar (
  echo [run] dist\miniredis.jar missing, building first...
  call build.cmd
  if errorlevel 1 exit /b 1
)

"%JAVA%" -jar dist\miniredis.jar %*
endlocal