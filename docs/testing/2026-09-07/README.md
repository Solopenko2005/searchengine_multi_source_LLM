# Комплексное тестирование научной поисковой системы с LLM

Дата: 7 сентября 2026 года  
Контур: https://search.5-42-117-227.sslip.io/  
Вердикт: **Условно готово к пилотной эксплуатации**

## Краткий результат

- Модульные тесты: **92/92**, ошибок нет.
- Интеграционные production-проверки: **9/9**.
- HTTP при 20 параллельных запросах: **119.43 RPS**, p95 **268.45 мс**, ошибок **0**.
- Поисковый SQL при 10 клиентах: **85.890645 TPS**, средняя задержка **116.427 мс**, ошибок **0**.
- Локальная LLM: HTTP 200, короткий ответ за **10.5 с**.
- Production-набор: **65 источников**, **10974 страниц**, **2447508 строк индекса**, **44972 RAG-фрагментов**.

## Что именно проверено

Модульные тесты охватывают поиск и подсветку лемм, разграничение доступа, индексацию сайтов, очередь и удаление источников, обработку RAG-фрагментов, streaming/cancel LLM, анализ тематик, профиль ассистента, регистрацию, JWT, восстановление пароля, captcha и почтовый контекст.

Интеграционные проверки выполнены через публичный HTTPS-контур и внутреннюю Docker-сеть: Caddy, search, authorization, PostgreSQL, Redis, email и OpenAI-совместимый LM Studio endpoint.

Нагрузочные проверки разделены на HTTP edge-контур и фактический поисковый SQL в production. Все операции с БД были только чтением.

## Важные ограничения

- Покрытие строками составляет менее 60 процентов в поисковом и e-mail модулях.
- Нагрузочный HTTP-тест production выполнен для публичного web-контура без пользовательских данных.
- Поисковый SQL проверен под read-only нагрузкой на фактическом production-наборе, но полный авторизованный RAG-сценарий под нагрузкой не запускался.
- LLM smoke-тест подтверждает доступность модели, однако 10,48 секунды на короткий ответ требуют оптимизации перед массовой эксплуатацией.

## Рекомендации

1. Поднять покрытие критических сервисов SearchService, DocumentIndexingService, AssistantService и email-контроллера до 60 процентов и выше.
2. Добавить отдельный staging-контур с обезличенной копией данных для авторизованных E2E и RAG load-тестов.
3. Ввести SLO: p95 поиска до 2 секунд, ошибки до 1 процента, time-to-first-token LLM до 5 секунд.
4. Подключить непрерывный мониторинг p95, ошибок, очереди индексации, длины контекста и времени генерации LLM.

## Как воспроизвести

1. Выполнить `mvn verify` отдельно в каталогах `Searchengine_1`, `authorization` и `emailsender`.
2. Запустить integration smoke-тест: `node scripts/testing/production-check.mjs --base https://search.5-42-117-227.sslip.io/ --output docs/testing/latest/integration-results.json`.
3. Запустить безопасную HTTP-нагрузку: `node scripts/testing/load-test.mjs --base https://search.5-42-117-227.sslip.io/ --endpoint / --concurrency 20 --duration 15 --output docs/testing/latest/load-c20.json`.
4. На сервере выполнить read-only сценарии `server-probe.sh`, `database-load-test.sh` и `llm-smoke-test.py`.
5. Обновить сводку: `node scripts/testing/generate-evidence.mjs`, затем `node scripts/testing/generate-charts.mjs docs/testing/2026-09-07/summary.json docs/testing/2026-09-07/charts`.

## Доказательства

- [Публичная панель CI/CD и покрытия](https://solopenko2005.github.io/searchengine_multi_source_LLM/)
- [Все прогоны CI/CD](https://github.com/Solopenko2005/searchengine_multi_source_LLM/actions/workflows/ci.yml)
- [Периодические интеграционные прогоны](https://github.com/Solopenko2005/searchengine_multi_source_LLM/actions/workflows/integration-evidence.yml)
- [Ручной безопасный нагрузочный прогон](https://github.com/Solopenko2005/searchengine_multi_source_LLM/actions/workflows/load-evidence.yml)
- [Интерактивный HTML-отчёт](report.html)
- [Формальный протокол тестирования](TEST_PROTOCOL.md)
- [Карточка production-базы тестирования](database/DATASET_CARD.md)
- [Машиночитаемая карточка БД](database/dataset-card.json)
- [Итоговая презентация PowerPoint](Комплексное_тестирование_научной_поисковой_системы_итог.pptx)
- [Обзор презентации PNG](presentation-preview.png)
- [Отдельные изображения слайдов](presentation-slides/)
- [Сводные метрики JSON](summary.json)
- [Результаты интеграционных проверок](integration-results.json)
- [Сводка производительности CSV](performance-summary.csv)
- [LLM smoke-тест](llm-smoke.json)
- [Снимок production-контура](server-probe.txt)
- [Raw pgbench](database-load/)
- [Графики](charts/)
- [JUnit XML и JaCoCo CSV](raw/)
- [Протокол структурной проверки презентации](presentation-validation.json)
