@echo off
rem Compile the server and the dependency-free test suite, then run it.
setlocal
set "JDK=E:\jdk"
set "JAVAC=%JDK%\bin\javac.exe"
set "JAVA=%JDK%\bin\java.exe"

call build.cmd
if errorlevel 1 exit /b 1

if not exist build\test-classes mkdir build\test-classes

echo [test] compiling tests...
"%JAVAC%" -encoding UTF-8 -cp build\classes -d build\test-classes src\test\java\com\fyb\miniredis\AllTests.java
if errorlevel 1 (
  echo [test] compile FAILED
  exit /b 1
)

echo [test] running AllTests...
"%JAVA%" -cp "build\classes;build\test-classes" com.fyb.miniredis.AllTests
if errorlevel 1 (
  echo [test] FAILED
  exit /b 1
)

echo [test] PASSED
endlocal