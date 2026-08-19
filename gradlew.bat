@echo off
setlocal enabledelayedexpansion

echo [gradlew] Searching for JDK 17-21 (required by IntelliJ Platform build plugin)...

set SCRIPT_DIR=%~dp0
set WRAPPER_JAR=%SCRIPT_DIR%gradle\wrapper\gradle-wrapper.jar
set FOUND_JDK=

:: Try IntelliJ 2026.1 bundled JDK — most reliable choice
for %%P in (
    "%PROGRAMFILES%\JetBrains\IntelliJ IDEA 2026.1\jbr"
    "%PROGRAMFILES%\JetBrains\IntelliJ IDEA 2026.2\jbr"
    "%PROGRAMFILES%\JetBrains\IntelliJ IDEA\jbr"
    "%PROGRAMFILES%\JetBrains\IntelliJ IDEA Community Edition 2026.1\jbr"
    "%PROGRAMFILES%\JetBrains\IntelliJ IDEA Community Edition\jbr"
    "%LOCALAPPDATA%\Programs\IntelliJ IDEA 2026.1\jbr"
    "%LOCALAPPDATA%\Programs\IntelliJ IDEA\jbr"
) do (
    if exist "%%~P\bin\java.exe" (
        set FOUND_JDK=%%~P
        goto :found
    )
)

:: Check if current JAVA_HOME is ok
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        for /f "tokens=3" %%v in ('"%JAVA_HOME%\bin\java" -version 2^>^&1 ^| findstr /i "version"') do (
            set JVER=%%v
            set JVER=!JVER:"=!
            for /f "delims=." %%m in ("!JVER!") do (
                if %%m LEQ 21 (
                    set FOUND_JDK=%JAVA_HOME%
                    goto :found
                )
            )
        )
    )
)

echo.
echo  ERROR: Could not find JDK 17-21.
echo.
echo  Options:
echo  1) Install Temurin 21: https://adoptium.net/temurin/releases/?version=21
echo     set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21...
echo     gradlew.bat buildPlugin
echo.
echo  2) Set org.gradle.java.home in gradle.properties
echo.
exit /b 1

:found
echo [gradlew] Using JDK at: %FOUND_JDK%
set JAVA_HOME=%FOUND_JDK%

"%JAVA_HOME%\bin\java" ^
    --enable-native-access=ALL-UNNAMED ^
    -Dorg.gradle.appname=Gradle ^
    -classpath "%WRAPPER_JAR%" ^
    org.gradle.wrapper.GradleWrapperMain ^
    %*
