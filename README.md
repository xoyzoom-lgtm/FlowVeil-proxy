# Hupp

VPN-клиент для Android и Windows с темами в стиле Happ (17 тем, по умолчанию iOS 27 Glass).

## Скачать

Готовые файлы: **[Releases → последняя версия](../../releases/latest)**

- `Hupp-android.apk` — Android (универсальный), `Hupp-android-arm64.apk` — для современных телефонов
- `Hupp-Setup.exe` — установщик для Windows 10/11
- `Hupp-windows-portable.zip` — Windows без установки

Файлы собираются автоматически (GitHub Actions) после каждого изменения в `main`.

## Что умеет

- Подписки любых провайдеров: VLESS, VMess, Trojan, Shadowsocks, Hysteria/Hysteria2, TUIC, WireGuard, Xray JSON; транспорты TCP, WS, gRPC, XHTTP и др.
- Отправляет стандартные заголовки устройства (`x-hwid`, `x-device-os`, `x-ver-os`, `x-device-model`) — нужны панелям с лимитом устройств (Remnawave и др.). HWID случайный, на Android отключается в настройках.
- Показывает трафик, срок подписки, объявление и поддержку провайдера (заголовки `subscription-userinfo`, `profile-title`, `announce`, `support-url`).
- Темы Happ, свой код темы (JSON).

## Исходники

- `android/` — форк [2dust/v2rayNG](https://github.com/2dust/v2rayNG) (GPL-3.0)
- `windows/` — форк [2dust/v2rayN](https://github.com/2dust/v2rayN) (GPL-3.0); локальная сборка: `windows/BUILD-Hupp.bat`

## Стабильная подпись APK (по желанию)

Без настройки каждая сборка APK подписывается новым ключом, и перед установкой новой версии старую надо удалить.
Чтобы обновления ставились поверх, добавьте в Settings → Secrets → Actions:
`HUPP_KEYSTORE_BASE64` (keystore в base64, alias `hupp`) и `HUPP_KEYSTORE_PASSWORD`.

## Политика конфиденциальности

[PRIVACY.md](PRIVACY.md) · Автор: Telegram [@GxoyzoomG](https://t.me/GxoyzoomG)
