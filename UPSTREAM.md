# Откуда собран FlowVeil

FlowVeil — форк двух открытых клиентов. Здесь записано, какие версии исходных проектов сейчас в сборке.
Раз в неделю `.github/workflows/upstream-watch.yml` сравнивает таблицу с последними релизами и открывает issue «Вышла версия X, у нас Y».
Когда обновили компонент — поменяйте строку здесь.

| Проект | У нас | Где используется |
|---|---|---|
| `2dust/v2rayNG` | `2.3.9` | основа Android-приложения (`android/`) |
| `2dust/v2rayN` | `7.25.2` | основа Windows-приложения (`windows/`) |
| `2dust/AndroidLibXrayLite` | `v26.9.9` | ядро Xray для Android (`libv2ray.aar`, `build.yml`) |
| `heiher/hev-socks5-tunnel` | `64cc609f945253b0e9ebc56317d544268f3c68c1` | туннель Android (коммит в `build.yml`) |
| `XTLS/Xray-core` | `latest` | ядро Xray для Windows (`build.yml` берёт последний релиз) |
| `SagerNet/sing-box` | `v1.14.x` | TUN на Windows и TUIC на Android (`build.yml`, ветка 1.12–1.14) |

Строки с `latest` и `v1.14.x` — не точные версии: watch будет открывать issue на каждый релиз, пока владелец не впишет точную версию из сборки.
