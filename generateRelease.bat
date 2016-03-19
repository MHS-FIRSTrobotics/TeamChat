@echo off
set NAME=TeamChat
set VERSION=0.1
echo Generating Team Chat release...
echo Cleaning Up...
call gradlew clean > nul
echo Building...
call gradlew distZip > nul
xcopy /y .\build\distributions\TeamChat-0.1.zip . > nul
echo Done! Press any key to continue...
pause > nul
