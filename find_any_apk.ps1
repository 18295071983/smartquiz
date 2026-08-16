Get-ChildItem -Path "d:\qzq\smartquiz" -Filter "*.apk" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 5 | ForEach-Object { Write-Host $_.FullName }
