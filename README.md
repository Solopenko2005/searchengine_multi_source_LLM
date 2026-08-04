# Search Engine + LLM

Поисковая система на Java 17 и Spring Boot для индексации сайтов, PDF/DOCX-документов, лемматизированного поиска и ответов по найденным материалам с помощью OpenAI.

## Возможности

- многопоточная индексация сайтов и пакетная индексация PDF/DOCX;
- единый запуск, остановка и наглядный прогресс индексации;
- полнотекстовый поиск по леммам с ранжированием;
- просмотр найденного документа с цветовой подсветкой всех словоформ искомой леммы;
- RAG-ассистент: модель отвечает только с контекстом найденных документов и возвращает ссылки на источники;
- LLM-анализ тематик со структурированным JSON-ответом и локальным резервным алгоритмом;
- пользовательский профиль ассистента и выбор конкретных документов для ответа;
- изоляция загруженных документов по пользователю (`username` в basic-режиме или `sub` JWT);
- Spring Security: локальная форма входа либо OAuth2 Resource Server с JWT;
- CORS allowlist, CSRF для сессий, роли USER/ADMIN и ограничение частоты LLM-запросов.

## Стек

Java 17, Spring Boot 2.7.18, Spring Security, Spring Data JPA, PostgreSQL, Liquibase, Jsoup, Lucene Morphology, Apache POI, PDFBox, OpenAI Responses API.

Основное приложение находится в каталоге `Searchengine_1`.

## Быстрый запуск

1. Создайте PostgreSQL-базу, например `search_engine`.
2. Задайте переменные окружения. Пример полного набора находится в `.env.example`.

PowerShell:

```powershell
$env:DB_URL="jdbc:postgresql://localhost:5432/search_engine"
$env:DB_USERNAME="postgres"
$env:DB_PASSWORD="change-me"
$env:APP_ADMIN_PASSWORD="change-me-too"
$env:OPENAI_API_KEY="your-api-key"
```

Bash:

```bash
export DB_URL='jdbc:postgresql://localhost:5432/search_engine'
export DB_USERNAME='postgres'
export DB_PASSWORD='change-me'
export APP_ADMIN_PASSWORD='change-me-too'
export OPENAI_API_KEY='your-api-key'
```

3. Запустите приложение:

```bash
cd Searchengine_1
mvn clean test
mvn spring-boot:run
```

Откройте [http://localhost:8080](http://localhost:8080) и войдите как `admin` (либо именем из `APP_ADMIN_USERNAME`). Если пароль не задан, одноразовый временный пароль выводится в лог при запуске.

## OpenAI

По умолчанию используется `gpt-5.6-terra` через Responses API; модель можно заменить переменной `OPENAI_MODEL`. Для более сложных задач можно выбрать `gpt-5.6-sol`, для более дешёвых массовых запросов — подходящую меньшую модель из доступных вашему API-проекту.

Подписка ChatGPT Plus/Pro и использование OpenAI API оплачиваются отдельно. Нужен API-ключ платформы OpenAI и активный API billing. Ключ передаётся только через `OPENAI_API_KEY` и никогда не должен попадать в Git.

Если ключ отсутствует или API временно недоступен, поиск продолжает работать, а ассистент формирует локальный ответ из найденных фрагментов.

Подробнее: [ASSISTANT_README.md](Searchengine_1/ASSISTANT_README.md).

## Индексация и документы

- `POST /api/startIndexing` — начать полную индексацию сайтов (ADMIN);
- `POST /api/stopIndexing` — остановить активные задачи (ADMIN);
- `GET /api/indexing/status` — состояние, прогресс и статусы источников;
- `POST /api/addSite` — добавить и индексировать сайт (ADMIN);
- `POST /api/indexPage` — переиндексировать страницу (ADMIN);
- `POST /api/uploadDocument` — загрузить PDF/DOCX (любой аутентифицированный пользователь);
- `GET /api/documents/indexing/status` — прогресс фоновой индексации документов текущего пользователя;
- `POST /api/documents/indexing/stop` — остановить только свою документную задачу;
- `GET /api/documents` — документы текущего пользователя;
- `GET /documents/{id}?query=...` — безопасный HTML-просмотр с подсветкой лемм.

Статусы источника: `INDEXING`, `INDEXED`, `FAILED`, `STOPPED`. Индикатор в интерфейсе показывает готовность, процент выполнения и причину неполной индексации.

## Интеграция с сервисом авторизации

Для production включите JWT-режим:

```text
SECURITY_MODE=jwt
AUTH_ISSUER_URI=https://auth.example.com/realms/search
AUTH_AUDIENCE=search-engine-api
CORS_ALLOWED_ORIGINS=https://search.example.com
```

Сервис авторизации должен выдавать JWT с корректными `iss` и `aud`, стабильным `sub` и массивом ролей в claim `roles`, например `["USER"]` или `["ADMIN"]`. Имена claims можно изменить через `AUTH_PRINCIPAL_CLAIM` и `AUTH_ROLES_CLAIM`. Если discovery недоступен, задайте `AUTH_JWK_SET_URI`. TLS должен завершаться на reverse proxy; наружу не следует публиковать PostgreSQL и внутренние административные порты.

## Проверка перед публикацией

```bash
cd Searchengine_1
mvn test
```

GitHub Actions запускает те же тесты из `.github/workflows/ci.yml`. Файлы `.env`, логи, IDE-настройки, crash dumps, Maven cache и сборочные артефакты исключены из Git.

## Ограничения и дальнейшие улучшения

- текущий RAG использует лемматизированный индекс; для больших коллекций стоит добавить embeddings и hybrid search с reranker;
- загрузка документа выполняется в HTTP-задаче с общим прогрессом и cooperative stop; для кластерного production лучше вынести её в очередь задач;
- Spring Boot 2.7.18 оставлен ради совместимости с существующим кодом `javax.*`; плановая миграция на актуальную ветку Spring Boot 3 потребует перехода на `jakarta.*` и обновления Spring Security DSL.
