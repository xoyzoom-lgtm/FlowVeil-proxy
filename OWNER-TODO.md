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
- Фаза 3: создать ключ подписи обновлений офлайн (команды будут в этом файле перед фазой 3).
- Фаза 4: защита ветки `main` в GitHub (Settings → Branches), Environment `release`.
- SignPath: заявка (см. `SIGNING.md`).
- RuStore / F-Droid: аккаунты (фаза 12).
