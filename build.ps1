Set-Location $PSScriptRoot
& .\gradlew.bat build --console=plain
$jar = Get-ChildItem "$PSScriptRoot\build\libs\FetchPets-*.jar" | Select-Object -First 1
Write-Host "Built: $($jar.FullName)" -ForegroundColor Green
Read-Host 'Press Enter to exit'
