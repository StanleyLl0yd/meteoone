# RuStore listing draft — 0.1.0-alpha.1

This is store-copy/source material for the first private MeteoOne alpha. Copy it into RuStore Console only after checking the fields against the current console UI and the exact alpha build.

Current RuStore publication documentation was rechecked on 2026-09-16. The mobile application name is limited to 30 characters, the short description to 80 characters, and the detailed description to 4000 characters. Mobile screenshots are required and must show functionality that actually exists in the submitted build.

## Application name

MeteoOne

## Short description

Прогноз погоды из нескольких моделей с офлайн-кэшем

## Detailed description

MeteoOne объединяет данные нескольких погодных моделей и источников в один понятный почасовой прогноз.

В первой закрытой alpha-версии доступны:

- почасовой прогноз до 72 часов;
- температура, погодные условия, осадки и ветер при наличии данных;
- объединение независимых источников прогноза;
- приблизительное текущее местоположение только после явного действия пользователя;
- сохранённый прогноз, доступный после перезапуска приложения и без подключения к сети;
- явный статус свежести сохранённого прогноза;
- ручное обновление прогноза при наличии сети;
- русский и английский интерфейс.

MeteoOne не запрашивает точное местоположение. Полученная от Android приблизительная координата сразу приводится к сетке прогноза 0,1° до сохранения. В alpha-версии нет аккаунтов MeteoOne, рекламы и SDK аналитики.

Это ранняя закрытая версия для технического тестирования. Интерфейс намеренно минимален: сравнение моделей, расширенные экраны, карты, виджеты и другие функции следующих этапов разработки пока не входят в эту сборку.

Политика конфиденциальности: используйте публичный URL актуального `PRIVACY.md` из защищённой ветки `main` после merge release-prep PR.

## What's new / Что нового

Первая закрытая alpha-версия MeteoOne:

- почасовой объединённый прогноз до 72 часов;
- offline-first кэш прогноза;
- приблизительное местоположение с понижением точности до сетки 0,1°;
- ручное обновление и сохранение кэша при сетевых ошибках;
- статусы свежего, устаревшего и истёкшего прогноза;
- русский и английский интерфейс.

## Category and age rating

Select the current weather/application category and age rating in RuStore Console from the options available at submission time. Do not encode a guessed category identifier in repository automation.

## Developer contact

RuStore requires at least one developer-contact method in the console. This is an owner/account field and must not be fabricated in repository metadata.

The repository issue tracker remains the project-level technical/privacy contact:

https://github.com/StanleyLl0yd/meteoone/issues

## Icon

Use the existing canonical store icon:

`docs/branding/assets/meteoone-icon-store-512.png`

Verify it remains exactly 512×512 and within the current RuStore size/format limit before upload. The repository CI already verifies canonical icon integrity.

## Screenshots

Do not generate marketing mockups that show nonexistent functionality. Capture the real `0.1.0-alpha.1` app from a release-equivalent build.

Prepare at least three consistent-orientation phone screenshots so both console/manual and API upload paths are safely covered. Suggested real states:

1. cached/fresh hourly forecast list with forecast-grid location visible;
2. stale or expired cached forecast still visible offline;
3. initial/no-cache screen with the explicit approximate-location action.

Do not include the Android permission dialog, notification shade, status-bar overlays from another app, or functionality not present in this alpha.

Use 9:16 or 16:9 media consistent with current RuStore requirements and validate final pixel/file limits in the console before submission.

## Public links to recheck at submission

- RuStore application publication guide: https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication
- RuStore application requirements: https://www.rustore.ru/help/developers/publishing-and-verifying-apps/requirement-apps
- RuStore alpha testing: https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication/testing/alpha-testing

Store requirements can change independently of the repository; recheck these pages immediately before upload/moderation.
