@echo off
set JAVA_HOME=D:\Program Files\Java\jdk-17.0.10
echo Building jadx-mcp-server...
echo NOTE: If build fails with "file in use", close Claude Code first (JAR is locked by MCP).
echo.
"D:\Logan\tools\apache-maven-3.9.6\bin\mvn.cmd" package -DskipTests -o %*
if %errorlevel% equ 0 (
    echo.
    echo BUILD SUCCESS - restart Claude Code to use the new JAR.
) else (
    echo.
    echo BUILD FAILED - check errors above.
)
pause
