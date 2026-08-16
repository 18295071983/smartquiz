Get-ChildItem -Path "d:\qzq\smartquiz\app\build\outputs" -Recurse -Filter "*.apk" -ErrorAction SilentlyContinue | ForEach-Object { Write-Host $_.FullName }
