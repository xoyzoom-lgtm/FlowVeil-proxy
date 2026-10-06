# Что нужно сделать владельцу (я это сделать не могу)

## Сейчас
1. **Посмотреть карту «старое → новое»** в `PROGRESS.md` и сказать ОК (до фазы 5).
2. **Замеры на телефоне** (для фазы 9, «до»): подключи телефон по USB, включи отладку, и пришли вывод:
   ```
   adb shell am force-stop com.flowveil.app
   adb shell am start -W -n com.flowveil.app/com.v2ray.ang.ui.main.MainActivity
   adb shell dumpsys meminfo com.flowveil.app:RunSoLibV2RayDaemon | head -30
   ```
   и размер APK из последнего релиза.

## Позже (по фазам)
- Фаза 3: ключи подписи обновлений — см. раздел ниже.
- Фаза 4: защита ветки `main` в GitHub (Settings → Branches), Environment `release`.
- SignPath: заявка (см. `SIGNING.md`).
- RuStore / F-Droid: аккаунты (фаза 12).

## Ключи подписи обновлений (фаза 3)
Код проверки уже в приложениях; пока ключей нет, каждое обновление считается «не подписанным» и приложение спрашивает пользователя.
1. На своём компьютере (не в GitHub!) создайте два ключа — основной и запасной:
   ```
   openssl ecparam -name prime256v1 -genkey -noout -out update-signing.key
   openssl ec -in update-signing.key -pubout -outform DER | base64 -w0 > update-signing.pub.b64
   openssl ecparam -name prime256v1 -genkey -noout -out update-signing-spare.key
   openssl ec -in update-signing-spare.key -pubout -outform DER | base64 -w0 > update-signing-spare.pub.b64
   ```
   Файлы `.key` храните офлайн (флешка, менеджер паролей). Потеряете оба — старые версии не смогут проверить новые.
2. Пришлите мне содержимое двух файлов `.pub.b64` (это открытые ключи, их можно показывать). Я впишу их в
   `android/.../handler/UpdateKeys.kt` и `windows/.../Handler/UpdateManifest.cs` (`UpdateKeys`).
3. После каждого релиза: скачайте `FlowVeil-manifest.json` из релиза, подпишите и загрузите подпись в тот же релиз:
   ```
   openssl dgst -sha256 -sign update-signing.key -out FlowVeil-manifest.json.sig FlowVeil-manifest.json
   ```
   Файл манифеста не меняйте ни на байт (подпись считается по точным байтам).
4. Через две версии после первого подписанного релиза скажите — включу строгий режим (`REQUIRE_SIGNED = true`): неподписанные обновления перестанут ставиться совсем.

## Защита ветки main (фаза 4)
GitHub → Settings → Branches → Add rule для `main`: «Require status checks» (job `tests`), запрет force-push.
