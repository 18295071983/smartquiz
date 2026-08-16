Get-ChildItem -Path "app\build\outputs\apk\debug" -Filter "*.apk" | ForEach-Object { $_.Name }
