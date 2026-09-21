# MeteoOne Privacy Policy

Effective date: 2026-09-21

This policy describes the current MeteoOne Android application source, package `com.sl.meteoone`, after completion of M4 Verification Engine. The published `0.2.0-alpha.1` build predates the M4 verification-data flow described below.

## Location

MeteoOne does not request location automatically when the app starts. Approximate location is requested only after the user explicitly chooses the location action.

The app requests Android `ACCESS_COARSE_LOCATION` only. It does not request `ACCESS_FINE_LOCATION`.

A location returned by Android is immediately normalized to MeteoOne's canonical 0.1-degree forecast grid before it is used as the forecast target. The raw device latitude and longitude are not persisted and are not used as forecast-cache keys.

## Data stored on the device

MeteoOne stores the minimum state needed for its offline-first forecast behavior:

- the active privacy-reduced 0.1-degree forecast-grid coordinate;
- the active time-zone identifier;
- cached fused forecast snapshots, per-source/model comparison evidence, failed-source identities, and forecast provenance/freshness information;\n- bounded local verification forecast history plus public observation-station metadata and weather observations used to evaluate model skill.

The current alpha location flow does not persist device elevation for the active target.

Cached snapshots are keyed by privacy-reduced forecast-grid coordinates. Changing the active target does not necessarily delete previously cached snapshots for other grid coordinates.

Android backup is disabled for the application. Clearing MeteoOne's app storage or uninstalling the app removes its locally stored application data subject to normal Android platform behavior.

## Network requests and weather providers

MeteoOne obtains forecast data over HTTPS from weather-data services, including Open-Meteo and direct official-source paths for NOAA/NCEP GFS, ECMWF IFS Open Data, and DWD ICON Open Data. M4 also retrieves public surface-station observations over HTTPS from NOAA/NCEI Global Historical Climatology Network hourly (GHCNh) for on-device forecast verification.

Where a forecast-provider request needs a location, MeteoOne may send the privacy-reduced forecast-grid coordinate and related forecast request parameters. MeteoOne does not send the raw device latitude or longitude to forecast providers. GHCNh station selection is performed locally against the privacy-reduced coordinate; GHCNh data requests identify public stations/years rather than sending the device coordinate.

As with any Internet request, an external service can receive ordinary transport metadata such as the public IP address used for the connection. Processing and retention performed by an external weather provider is governed by that provider's own terms and privacy practices.

## Accounts, advertising, and analytics

The current application source has no MeteoOne account system, advertising SDK, analytics SDK, or behavioral-tracking SDK. It contains no mechanism for selling user data.

## Permissions

The alpha application uses:

- `INTERNET` to retrieve weather data;
- `ACCESS_COARSE_LOCATION` to obtain an approximate location after an explicit user action.

No fine-location permission is requested.

## Contact

For privacy questions or a report about the application's privacy behavior, use the public MeteoOne repository issue tracker:

https://github.com/StanleyLl0yd/meteoone/issues

Store-console developer/legal contact information is maintained separately by the distributor account holder.

## Changes

If MeteoOne's data behavior changes, this policy must be updated before a release containing that change is distributed.

---

# Политика конфиденциальности MeteoOne

Дата вступления в силу: 21 сентября 2026 года

Эта политика описывает текущий исходный код Android-приложения MeteoOne с пакетом `com.sl.meteoone` после завершения M4 Verification Engine. Опубликованная сборка `0.2.0-alpha.1` создана до появления описанного ниже потока данных M4 для проверки прогноза.

## Местоположение

MeteoOne не запрашивает местоположение автоматически при запуске. Приблизительное местоположение запрашивается только после явного действия пользователя.

Приложение запрашивает только Android-разрешение `ACCESS_COARSE_LOCATION`. Разрешение `ACCESS_FINE_LOCATION` не запрашивается.

Координата, полученная от Android, сразу нормализуется до канонической сетки прогноза MeteoOne с шагом 0,1° до использования в качестве цели прогноза. Исходные широта и долгота устройства не сохраняются и не используются как ключи кэша прогноза.

## Данные, сохраняемые на устройстве

MeteoOne хранит минимальный набор данных, необходимый для offline-first работы прогноза:

- активную координату сетки прогноза с пониженной точностью 0,1°;
- идентификатор часового пояса активной цели;
- кэшированные объединённые прогнозы, данные отдельных источников/моделей для сравнения, идентификаторы недоступных источников, а также сведения о происхождении и актуальности прогноза;\n- ограниченную по сроку локальную историю прогнозов для проверки, метаданные публичных метеостанций и погодные наблюдения, используемые для оценки качества моделей.

Текущий alpha-сценарий определения местоположения не сохраняет высоту устройства для активной цели.

Кэш прогнозов привязан к координатам сетки с пониженной точностью. Смена активного места не обязательно удаляет ранее сохранённые прогнозы для других координат сетки.

Резервное копирование Android для приложения отключено. Очистка данных приложения или удаление MeteoOne удаляет локально сохранённые данные приложения в соответствии с обычным поведением Android.

## Сетевые запросы и поставщики погодных данных

MeteoOne получает прогнозы по HTTPS от погодных сервисов, включая Open-Meteo и прямые официальные источники NOAA/NCEP GFS, ECMWF IFS Open Data и DWD ICON Open Data. M4 также получает по HTTPS публичные станционные наблюдения NOAA/NCEI Global Historical Climatology Network hourly (GHCNh) для локальной проверки прогноза.

Если запрос к поставщику прогноза требует местоположение, MeteoOne может передавать координату сетки прогноза с пониженной точностью и связанные параметры запроса. Исходные широта и долгота устройства поставщикам прогноза не передаются. Выбор станции GHCNh выполняется локально по координате с пониженной точностью; запросы данных GHCNh указывают публичную станцию и год, а не координату устройства.

Как и при любом интернет-соединении, внешний сервис может получать обычные транспортные метаданные, например публичный IP-адрес соединения. Обработка и сроки хранения данных внешним поставщиком определяются его собственными условиями и политикой конфиденциальности.

## Аккаунты, реклама и аналитика

В текущем исходном коде приложения нет системы аккаунтов MeteoOne, рекламного SDK, SDK аналитики или SDK поведенческого трекинга. В нём нет механизма продажи пользовательских данных.

## Разрешения

Alpha-версия использует:

- `INTERNET` для получения погодных данных;
- `ACCESS_COARSE_LOCATION` для получения приблизительного местоположения после явного действия пользователя.

Разрешение на точное местоположение не запрашивается.

## Контакты

Для вопросов о конфиденциальности или сообщений о поведении приложения используйте публичный issue tracker MeteoOne:

https://github.com/StanleyLl0yd/meteoone/issues

Контактные и юридические данные разработчика для магазина указываются отдельно владельцем аккаунта распространения.

## Изменения

Если работа MeteoOne с данными изменится, эта политика должна быть обновлена до публикации релиза с такими изменениями.
