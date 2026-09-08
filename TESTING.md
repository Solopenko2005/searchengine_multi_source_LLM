# Доказательная база тестирования

Этот документ связывает результаты, исходный код тестов и воспроизводимые запуски CI/CD научной поисковой системы с LLM.

## Публичные ссылки

| Что показать | Ссылка |
|---|---|
| Интерактивная панель качества и покрытие по модулям | [GitHub Pages](https://solopenko2005.github.io/searchengine_multi_source_LLM/) |
| История автоматических запусков JUnit, JaCoCo и сборки Docker | [CI and Quality Evidence](https://github.com/Solopenko2005/searchengine_multi_source_LLM/actions/workflows/ci.yml) |
| Интеграционные проверки production | [Production Integration Evidence](https://github.com/Solopenko2005/searchengine_multi_source_LLM/actions/workflows/integration-evidence.yml) |
| Ручной ограниченный нагрузочный тест | [Manual Load Test Evidence](https://github.com/Solopenko2005/searchengine_multi_source_LLM/actions/workflows/load-evidence.yml) |
| Формальный протокол | [TEST_PROTOCOL.md](docs/testing/2026-09-07/TEST_PROTOCOL.md) |
| Интерактивный зафиксированный отчёт | [report.html](https://solopenko2005.github.io/searchengine_multi_source_LLM/evidence/2026-09-07/report.html) |
| Итоговая презентация | [PowerPoint](docs/testing/2026-09-07/Комплексное_тестирование_научной_поисковой_системы_итог.pptx) |
| База, на которой выполнялся тест | [Карточка набора данных](docs/testing/2026-09-07/database/DATASET_CARD.md) |

## Зафиксированный результат

- JUnit: **92 из 92 тестов пройдено**; failures 0, errors 0.
- Общее покрытие строк JaCoCo: **20,7%**.
- Search + LLM: 43 теста, покрытие строк 18,2%.
- Authorization: 48 тестов, покрытие строк 55,9%.
- Email sender: 1 тест, покрытие строк 29,8%.
- Интеграционные проверки production: **9 из 9**.
- HTTP, 20 клиентов: **119,43 RPS**, p95 **268,45 мс**, ошибок 0.
- Поисковый SQL, 10 клиентов: **85,89 TPS**, средняя задержка **116,43 мс**, ошибок 0.
- LLM smoke: HTTP 200, 10,48 с, 70 токенов.

Покрытие 20,7% является честно зафиксированным ограничением, а не признаком промышленной готовности. Перед итоговым production-релизом критические сервисы следует покрыть минимум на 60% и повторить авторизованный RAG load-тест на отдельном staging-контуре.

## Где находится код тестирования

- `Searchengine_1/src/test` — поиск, сниппеты, леммы, индексация, роли, RAG, LLM, темы и профиль.
- `authorization/src/test` — регистрация, JWT, captcha, группы, приглашения и восстановление пароля.
- `emailsender/src/test` — запуск и конфигурация почтового сервиса.
- `scripts/testing/production-check.mjs` — read-only интеграционный тест публичного контура.
- `scripts/testing/load-test.mjs` — ограниченный HTTP load-тест с p50/p90/p95/p99 и порогами ошибок.
- `scripts/testing/database-load-test.sh` — read-only pgbench-сценарий поискового SQL.
- `scripts/testing/llm-smoke-test.py` — проверка OpenAI-совместимого LLM endpoint.
- `scripts/testing/build-ci-dashboard.mjs` — сборка публичной панели покрытия из JUnit XML и JaCoCo XML.

## База данных тестирования

Нагрузочный SQL-тест выполнен на production-снимке PostgreSQL: 65 источников, 10 974 страницы, 230 955 лемм, 2 447 508 строк обратного индекса и 44 972 RAG-фрагмента. Полный дамп не публикуется: в нём есть тексты пользовательских документов, идентификаторы владельцев и данные авторизации. Для проверки методики опубликованы:

- агрегированная [карточка набора](docs/testing/2026-09-07/database/DATASET_CARD.md) и её [JSON-версия](docs/testing/2026-09-07/database/dataset-card.json);
- Liquibase-миграции со схемой таблиц в исходном коде;
- SQL-сценарий pgbench;
- необработанные результаты каждого прогона в `docs/testing/2026-09-07/database-load`.

## Как получить новые доказательства

1. Каждый push или pull request автоматически запускает три Maven-модуля, формирует JUnit XML и JaCoCo HTML/XML/CSV.
2. В карточке запуска GitHub Actions доступны логи, статус каждой матрицы и скачиваемые артефакты сроком хранения 90 дней.
3. После успешной обработки результат публикуется на GitHub Pages, где можно открыть детальное покрытие до класса и строки.
4. Интеграционный workflow запускается вручную и еженедельно; он проверяет страницы, API-защиту, captcha и TLS без изменения данных.
5. Нагрузочный workflow запускается только вручную и ограничен 20 клиентами и 30 секундами в интерфейсе GitHub.

Для отчёта или защиты удобно сделать три скриншота: общий статус последнего CI-запуска, главную панель GitHub Pages и детальную страницу JaCoCo нужного пакета. Численные результаты и ограничения уже оформлены в презентации и формальном протоколе.
