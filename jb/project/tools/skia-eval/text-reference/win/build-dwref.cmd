@echo off
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvarsall.bat" arm64 >nul
cd /d %~dp0
cl /nologo /EHsc /O2 /W3 /std:c++17 /utf-8 /DUNICODE /D_UNICODE dwref.cpp /Fe:dwref.exe /link d2d1.lib dwrite.lib windowscodecs.lib gdi32.lib user32.lib ole32.lib
