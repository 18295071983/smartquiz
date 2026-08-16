Get-ChildItem -Path "d:\qzq\smartquiz\app\build\outputs\apk\debug" -Filter "*.apk" -ErrorAction SilentlyContinue | ForEach-Object { Write-Host $_.Name }
