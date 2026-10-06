# Безопасность FlowVeil

## Как сообщить об уязвимости
Пишите приватно: GitHub → вкладка **Security** → **Report a vulnerability** (Private Vulnerability Reporting) или в Telegram [@GxoyzoomG](https://t.me/GxoyzoomG).
Не публикуйте детали в issue и чатах, пока нет исправления. Ответ — в течение 7 дней, исправление серьёзной проблемы — как можно быстрее, обычно в течение 30 дней.

## Как проверить скачанный файл
1. В каждом релизе есть `FlowVeil-manifest.json`: имя, размер и SHA-256 каждого файла.
   - Windows (PowerShell): `Get-FileHash .\FlowVeil-Setup.exe -Algorithm SHA256`
   - Linux/macOS: `sha256sum FlowVeil-android.apk`
   Сумма должна совпасть со строкой `sha256` для этого файла.
2. Если у релиза есть `FlowVeil-manifest.json.sig`, манифест подписан ключом владельца (ECDSA P-256). Открытые ключи — в
   `android/V2rayNG/app/src/main/java/com/v2ray/ang/handler/UpdateKeys.kt`. Проверка:
   `openssl dgst -sha256 -verify update-signing.pub.pem -signature FlowVeil-manifest.json.sig FlowVeil-manifest.json`
3. Приложения делают эти проверки сами перед установкой обновления.

## Что считается уязвимостью
Утечка ключей подписок или адресов серверов, выполнение чужого кода, обход подтверждения при импорте ссылки, установка
подменённого обновления, трафик мимо сервера, когда пользователь этого не выбирал. Подробнее — `docs/THREAT-MODEL.md`.
