@echo off
setlocal
set APP_HOME=%~dp0
set GV=8.9
set CACHE=%USERPROFILE%\.gradle\wrapper\dists\gradle-%GV%-bin\pkg
set GB=%CACHE%\gradle-%GV%\bin\gradle.bat
if defined GRADLE_HOME if exist "%GRADLE_HOME%\bin\gradle.bat" set GB=%GRADLE_HOME%\bin\gradle.bat
if not exist "%GB%" (
  echo Gradle %GV% not found. Downloading...
  if not exist "%CACHE%" mkdir "%CACHE%"
  powershell -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GV%-bin.zip' -OutFile '%CACHE%\g.zip'"
  powershell -Command "Expand-Archive -Force '%CACHE%\g.zip' '%CACHE%'"
)
cd /d "%APP_HOME%"
call "%GB%" %*
