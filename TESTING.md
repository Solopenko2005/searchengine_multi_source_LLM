# Доказательная база тестирования

Актуальный повторный прогон выполнен 9 сентября 2026 года на production-версии `80ffe1c`.

Статус: **полностью готово к эксплуатации в зафиксированном пилотном профиле** — 8 vCPU, 12 ГБ RAM и один одновременно генерируемый локальный LLM-ответ.

## Публичные ссылки

| Что показать | Ссылка |
|---|---|
| История JUnit, JaCoCo и Docker build | [CI and Quality Evidence](https://github.com/Solopenko2005/searchengine_multi_source_LLM/actions/workflows/ci.yml) |
| Периодические production-проверки | [Production Integration Evidence](https://github.com/Solopenko2005/searchengine_multi_source_LLM/actions/workflows/integration-evidence.yml) |
| Ограниченный ручной нагрузочный тест | [Manual Load Test Evidence](https://github.com/Solopenko2005/searchengine_multi_source_LLM/actions/workflows/load-evidence.yml) |
| Повторный отчёт и raw-доказательства | [docs/testing/2026-09-09](docs/testing/2026-09-09/README.md) |
| Формальный протокол | [TEST_PROTOCOL.md](docs/testing/2026-09-09/TEST_PROTOCOL.md) |
| Интерактивная панель для скриншота | [report.html](docs/testing/2026-09-09/report.html) |
| Карточка фактической БД | [DATASET_CARD.md](docs/testing/2026-09-09/database/DATASET_CARD.md) |
| Предыдущая итоговая презентация | [PowerPoint](docs/testing/2026-09-07/Комплексное_тестирование_научной_поисковой_системы_итог.pptx) |

## Зафиксированный результат

- JUnit: **119 из 119**, failures 0, errors 0.
- Критический пакет LLM/RAG: **65,3%** строк; LlmClient 76,0%; EmbeddingClient 84,6%; тематический LLM-анализ 92,8%.
- Production integration/security: **9 из 9**, TLS 1.3.
- HTTP при 20 клиентах: **167,25 RPS**, p95 **141,91 мс**, ошибок 0.
- Поисковый SQL при 10 клиентах: **101,43 TPS**, средняя задержка **98,59 мс**, ошибок 0.
- Авторизованный RAG: **5 из 5** через Qwen3-4B, гибридный поиск, валидные цитаты **100%**.
- После оптимизации p95 первого токена уменьшился с 50,45 до **15,63 с**, полного ответа — с 86,88 до **28,66 с**.

Общее line coverage 30,9% учитывает большой объём legacy-кода и не используется как единственный критерий выпуска. Для фактически исполняемого критического LLM-контура действуют отдельные обязательные пороги CI, все они выполнены.

## Код тестирования

- `Searchengine_1/src/test` — поиск, леммы, сниппеты, индексация, RAG, потоковый LLM, кэш, circuit breaker, темы, метрики и цитаты.
- `authorization/src/test` — регистрация, роли, JWT, captcha и восстановление пароля.
- `emailsender/src/test` — контроллер, SMTP-сервис и запуск Spring-контекста.
- `scripts/testing/production-check.mjs` — read-only integration/security/TLS.
- `scripts/testing/load-test.mjs` — HTTP p50/p90/p95/p99 и частота ошибок.
- `scripts/testing/rag-load-test.mjs` — полный авторизованный RAG/SSE с временным посетителем и проверкой цитат.
- `scripts/testing/database-load-test.sh` — read-only `pgbench` по поисковому SQL.
- `scripts/testing/prepare-rag-evidence.sh` и `cleanup-rag-evidence.sh` — безопасная подготовка и очистка тестовой учётной записи.

## База тестирования

Production-снимок содержит 65 источников, 10 974 страницы, 230 955 лемм, 2 447 508 строк обратного индекса и 44 972 RAG-фрагмента. Полный дамп не публикуется, потому что содержит пользовательские тексты и данные авторизации. Схема доступна в Liquibase, агрегаты и методика — в [карточке набора](docs/testing/2026-09-09/database/DATASET_CARD.md).

## Важная граница

Локальная Qwen3-4B остаётся бесплатной и обеспечивает качество с цитатами, но CPU-сервер обслуживает одну генерацию одновременно. Для двух и более параллельных ответов с TTFT менее 5 секунд нужно подключить GPU endpoint или внешний OpenAI-совместимый API. RAG, безопасность, поиск, индексацию и пользовательские данные переделывать для этого не потребуется.
