@echo off
rem Build and run the load generator.
rem   bench.cmd [host] [port] [threads] [opsPerThread]
rem Example: bench.cmd 127.0.0.1 6399 8 20000
setlocal
set "JDK=E:\jdk"
set "JAVAC=%JDK%\bin\javac.exe"
set "JAVA=%JDK%\bin\java.exe"

if not exist dist\miniredis.jar (
  call build.cmd
  if errorlevel 1 exit /b 1
)

if not exist build\bench mkdir build\bench
echo [bench] compiling bench\Bench.java...
"%JAVAC%" -encoding UTF-8 -d build\bench bench\Bench.java
if errorlevel 1 (
  echo [bench] compile FAILED
  exit /b 1
)

echo [bench] running...
"%JAVA%" -cp "dist\miniredis.jar;build\bench" Bench %*
endlocal