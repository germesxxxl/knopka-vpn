# Кнопка VPN

Бесплатный VPN для Android с одной кнопкой. Без настроек, без ручного поиска профилей.

- Берёт публичные бесплатные серверы из [igareck/vpn-configs-for-russia](https://github.com/igareck/vpn-configs-for-russia). Там они проверяются из России каждые 2–4 часа.
- Ядро [Mihomo](https://github.com/MetaCubeX/mihomo) пингует все серверы **из сети пользователя**, выбирает самый быстрый и автоматически переключается, если сервер упал.
- Российские сайты (банки, Госуслуги, маркетплейсы) идут напрямую, мимо публичных серверов.
- Списки скачиваются через зеркала (githack, jsDelivr, GitLab, Codeberg), а после подключения через сам VPN. Снимок списков вшит в APK, так что первое подключение работает даже при заблокированном GitHub.
- Есть плитка в шторке уведомлений (Quick Settings) с той же кнопкой.
- Место под рекламу: интерфейс `AdProvider` в [`ads/Ads.kt`](app/src/main/java/app/knopka/vpn/ads/Ads.kt).

## Как собрать APK (через GitHub, ничего ставить не нужно)

1. Создайте на GitHub **публичный** репозиторий. Лицензия GPL-3.0 требует открытого кода, и бесплатные Actions для публичных репозиториев безлимитны.
2. Загрузите туда этот проект (`git push`).
3. Откройте вкладку **Actions → Build APK**. Сборка идёт около 10–15 минут.
4. Внизу страницы сборки будет архив **knopka-vpn-apk**. Внутри:
   - `app-universal-*.apk` подходит для любого телефона;
   - `app-arm64-v8a-*.apk` меньше по размеру, подходит почти всем современным телефонам.

Без ключа подписи собирается **debug**-версия. Для тестов её достаточно.

### Подпись для публикации (один раз)

Для раздачи пользователям нужна release-сборка, подписанная **вашим** ключом. Если ключ потерять, обновления у пользователей перестанут устанавливаться поверх старой версии.

```bash
keytool -genkeypair -v -keystore release.jks -alias knopka -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks > release.jks.b64
```

В репозитории: **Settings → Secrets and variables → Actions**. Добавьте секреты:
`KEYSTORE_BASE64` (содержимое `release.jks.b64`), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`knopka`), `KEY_PASSWORD`.

Релиз: `git tag v0.1.0 && git push --tags`. APK появятся в разделе **Releases**.

Сборка также запускается раз в неделю, чтобы обновить вшитый снимок списков.

## Структура

| Путь | Что там |
|---|---|
| `core/` | Обёртка над Mihomo на Go. gomobile собирает её в `app/libs/core.aar` |
| `vpn/KnopkaVpnService.kt` | VPN-сервис: TUN, запуск ядра, статус, фоновое обновление списков |
| `vpn/ConfigBuilder.kt` | Конфиг Mihomo: автовыбор сервера, DNS, правила «РФ напрямую» |
| `data/Sources.kt` | Откуда брать серверы (и зеркала) |
| `ui/MainActivity.kt` | Экран с кнопкой |
| `ads/Ads.kt` | Подключение рекламной сети |
| `scripts/fetch-assets.sh` | Вшивает в APK правила и снимок списков |

## Лицензия и благодарности

GPL-3.0. Используются [Mihomo](https://github.com/MetaCubeX/mihomo) (GPL-3.0), списки серверов [igareck/vpn-configs-for-russia](https://github.com/igareck/vpn-configs-for-russia) (GPL-3.0), правила [MetaCubeX/meta-rules-dat](https://github.com/MetaCubeX/meta-rules-dat) (GPL-3.0).

Серверы публичные и не принадлежат проекту. Их скорость и доступность не гарантируются.
