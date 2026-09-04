@echo off
setlocal EnableExtensions
set "NATIVE_GIT=C:\Program Files\Git\cmd\git.exe"
set "WRAPPER=%~dp0..\git-docker.ps1"

if /I "%~1"=="pull" goto :docker
if /I "%~1"=="push" goto :docker
if /I "%~1"=="fetch" goto :docker
if /I "%~1"=="ls-remote" goto :docker
if /I "%~1"=="clone" goto :docker

"%NATIVE_GIT%" %*
exit /b %ERRORLEVEL%

:docker
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%WRAPPER%" -- %*
exit /b %ERRORLEVEL%
