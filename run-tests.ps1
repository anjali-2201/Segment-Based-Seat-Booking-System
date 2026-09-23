# Compile main sources
$mainFiles = Get-ChildItem -Recurse -Filter "*.java" src\main | Select-Object -ExpandProperty FullName
javac --release 17 -d out\classes $mainFiles

# Compile test sources
$testFiles = Get-ChildItem -Recurse -Filter "*.java" src\test | Select-Object -ExpandProperty FullName
javac --release 17 -cp "out\classes;lib\junit-platform-console-standalone-1.10.2.jar" -d out\test-classes $testFiles

# Run tests
java -jar lib\junit-platform-console-standalone-1.10.2.jar `
     --class-path "out\classes;out\test-classes" `
     --select-package com.shuttle `
     --details tree