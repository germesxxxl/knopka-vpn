# Политика конфиденциальности butone / butone Privacy Policy

**Android · app.butone.vpn · 25 ноября 2026 г. / November 25, 2026**

## Русский

butone — приложение проекта [germesxxxl/knopka-vpn](https://github.com/germesxxxl/knopka-vpn). Оно работает без аккаунта пользователя и не запрашивает контакты или точную геолокацию.

**VPN.** При подключении Android передаёт приложению сетевые пакеты для маршрутизации. Для выбранных российских и локальных ресурсов применяются правила прямого доступа. Остальной маршрутизируемый трафик идёт по зашифрованному соединению к публичному VPN-серверу третьей стороны. Оператор узла может видеть IP подключения, назначения, время и объём соединений и незашифрованные данные приложений. Мы не управляем этими операторами и их журналами. Приложение не передаёт данные VPN рекламному SDK.

**Списки серверов и проверки.** Приложение получает публичные списки по HTTPS и отправляет короткие тестовые запросы к заранее заданным сервисам для проверки связи. Сервисы списков и тестовые адреса видят технические данные сетевого запроса, в том числе IP подключения или VPN-узла, через который пришёл запрос.

**Отчёт.** На устройстве хранятся события подключения, тип сети, результаты проверок, выбранный узел и диагностические ошибки. Экспортируемый отчёт может включать модель устройства, версию Android и приложения, объёмы трафика и журнал событий. Он не включает журнал посещённых адресов из ядра. Журнал ограничен размером, а старые временные копии отчёта удаляются при очередном экспорте. butone не отправляет отчёт автоматически. Вы можете сохранить его, отправить выбранным вами способом или удалить локальные данные в настройках Android.

**Реклама.** В сборках с включённой монетизацией реклама может появляться в приложении, когда VPN выключен. В этих сборках Google Mobile Ads и Google UMP могут получать IP (для приблизительной геолокации), идентификаторы устройства или приложения, взаимодействия с рекламой и диагностические сведения. Google использует их для показа и измерения рекламы, аналитики и предотвращения мошенничества. Перед рекламными запросами приложение проверяет настройки согласия. Если Google требует доступ к настройкам рекламы, его можно открыть с главного экрана. Приложение сохраняет срок бонуса «без рекламы» на устройстве. [Обработка данных Google](https://policies.google.com/technologies/partner-sites) · [Данные рекламного SDK](https://developers.google.com/admob/android/privacy/play-data-disclosure)

**Вопросы и удаление данных.** Связаться с проектом можно через [форму обратной связи](https://github.com/germesxxxl/knopka-vpn/issues/new). Форма публичная, поэтому не прикладывайте отчёты, IP-адреса и личные сведения. Локальные данные приложения удаляются через настройки Android или при удалении приложения. Для данных Google доступны [настройки рекламы и конфиденциальности](https://myadcenter.google.com/).

## English

butone is developed by the [germesxxxl/knopka-vpn project](https://github.com/germesxxxl/knopka-vpn). The app requires no user account and does not request contacts or precise location.

**VPN.** When connected, Android supplies network packets to the app for routing. Selected Russian and local destinations follow direct-routing rules. Other routed traffic is encrypted between the device and a third-party public VPN endpoint. Endpoint operators may see your connecting IP, destinations, connection timing and volume, and content sent using unencrypted protocols. The project does not control these operators or their logs. VPN data is not shared with the ad SDK.

**Server lists and checks.** The app fetches public server lists over HTTPS and sends short probes to predefined services. Providers may see standard connection metadata, including the IP address originating the request or the VPN exit IP.

**Diagnostics.** Connection events, network type, check outcomes, selected server names and diagnostic errors are stored locally. An exported report can include the device model, OS and app versions, traffic totals and the local event log. It omits the core's connection destination logs. The local history has a size limit and old temporary exports are removed during the next export. The app does not transmit reports automatically. You may save or share a report yourself and can erase local data in Android settings.

**Ads.** In monetized builds, ads can appear inside the app while its VPN is off. Google Mobile Ads and Google UMP may receive IP-derived general location, device or app identifiers, ad interactions and diagnostic data for advertising, measurement, analytics and fraud prevention. The app checks consent before requesting ads. When required by Google, ad privacy options are available from the main screen. The ad-free reward expiration is stored locally. [How Google uses data](https://policies.google.com/technologies/partner-sites) · [Ad SDK data disclosure](https://developers.google.com/admob/android/privacy/play-data-disclosure)

**Contact and deletion.** Use the [project's feedback form](https://github.com/germesxxxl/knopka-vpn/issues/new) for privacy questions. It is public, so do not attach logs, IP addresses or personal information. You can delete local app data by clearing storage or uninstalling. See [Google's ad and privacy controls](https://myadcenter.google.com/) for data handled by Google.
