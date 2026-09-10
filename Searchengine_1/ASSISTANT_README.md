# LLM-ассистент и анализ тематик

## Как это работает

1. Новые страницы разбиваются на перекрывающиеся смысловые фрагменты. Nomic Embeddings строит векторы в фоне, поэтому обычный поиск доступен ещё до завершения этой операции. Пустые и технические страницы отмечаются как `SKIPPED`, чтобы очередь не выбирала их повторно и гарантированно доходила до 100%.
2. Запрос одновременно проходит через поиск по леммам и локальный HNSW-векторный индекс Lucene.
3. Результаты объединяются методом Reciprocal Rank Fusion и фильтруются по выбранным источникам и правам пользователя.
4. Контекст, профиль пользователя и ограниченная история отправляются в OpenAI-совместимый Responses API выбранного провайдера.
5. Ответ поступает в интерфейс частями. Пользователь видит этап обработки и может остановить генерацию.
6. Тематики вычисляются в фоне по репрезентативным фрагментам коллекции и сохраняются в кэше. Ранжирование предпочитает цели, методы, результаты и выводы исследования и понижает библиографические, регистрационные и издательские блоки. Повторное открытие вкладки не запускает дорогой анализ заново.
7. При недоступности embeddings или LLM система автоматически продолжает работать по леммам и формирует локальный ответ.

Для локальной модели с контекстом 8192 токена приложение автоматически сокращает каждый фрагмент и распределяет бюджет между всеми найденными источниками. Анализ тем использует до 14 лучших исследовательских фрагментов из разных источников. Названия сайтов и файлов не подменяют собой темы, а устаревший кэш прежнего алгоритма отбрасывается и пересчитывается в фоне.

Это RAG-настройка под источники пользователя, а не fine-tuning модели: материалы не меняют веса модели и добавляются только в контекст конкретного запроса.

## Настройка

В производственной среде используется Yandex AI Studio:

```text
LLM_PROVIDER=Yandex AI Studio
OPENAI_ENABLED=true
LLM_BASE_URL=https://ai.api.cloud.yandex.net/v1
LLM_API_KEY=<секретный API-ключ>
YANDEX_FOLDER_ID=<идентификатор каталога>
LLM_MODEL=aliceai-llm
LLM_BACKGROUND_PROVIDER=Yandex AI Studio
LLM_BACKGROUND_BASE_URL=https://ai.api.cloud.yandex.net/v1
LLM_BACKGROUND_MODEL=yandexgpt-5.1
```

Для API-ключа сервисному аккаунту нужна роль `ai.languageModels.user`. Клиент сам
использует `Authorization: Api-Key` и формирует URI модели
`gpt://<YANDEX_FOLDER_ID>/<LLM_MODEL>`.

Для автоматического перехода на локальную Qwen при сбое облака задайте:

```text
LLM_FALLBACK_ENABLED=true
LLM_FALLBACK_PROVIDER=local
LLM_FALLBACK_BASE_URL=http://localhost:1235/v1
LLM_FALLBACK_API_KEY=lm-studio
LLM_FALLBACK_MODEL=local-qwen3-4b
```

В полностью локальном режиме LM Studio укажите:

```text
LLM_PROVIDER=LM Studio
OPENAI_ENABLED=true
LLM_BASE_URL=http://localhost:1234/v1
LLM_API_KEY=lm-studio
LLM_MODEL=local-qwen3-8b
EMBEDDING_BASE_URL=http://localhost:1234/v1
EMBEDDING_API_KEY=lm-studio
EMBEDDING_MODEL=text-embedding-nomic-embed-text-v1.5
```

Дополнительные параметры:

```text
OPENAI_ENABLED=true
LLM_BASE_URL=https://api.openai.com/v1
LLM_MODEL=gpt-5.6-terra
OPENAI_REASONING_EFFORT=low
OPENAI_MAX_OUTPUT_TOKENS=1600
ASSISTANT_RATE_LIMIT_PER_MINUTE=30
OPENAI_TOPIC_MIN_CONFIDENCE=0.65
ASSISTANT_EMBEDDING_ENABLED=true
ASSISTANT_CHUNK_CHARS=2600
ASSISTANT_CHUNK_OVERLAP_CHARS=320
ASSISTANT_RAG_MAX_DOCUMENTS=15
ASSISTANT_RAG_CANDIDATES=64
```

Код использует `POST /v1/responses`, повторы с учётом `Retry-After` при временных ошибках, автоматический резервный провайдер и Structured Outputs (JSON Schema) для анализа тематик. API-ключ не логируется и не возвращается клиенту.

ChatGPT Plus/Pro не предоставляет API-кредиты: OpenAI API требует отдельного ключа и billing в API-проекте.

## API

`POST /api/assistant/chat` возвращает готовый ответ целиком. Для интерфейса используется потоковый вариант `POST /api/assistant/chat/stream`:

```json
{
  "message": "Какие методы описаны в документах?",
  "history": [{"role": "user", "content": "Предыдущий вопрос"}],
  "site": "",
  "profileInstructions": "Отвечай как научный редактор, кратко и по-русски",
  "sourceIds": [3, 7, 12]
}
```

Ответ содержит `answer`, `sources`, `usedLlm` и признаки успешности. `sourceIds` ограничивает RAG выбранными источниками текущего пользователя. По умолчанию контекст содержит до 15 различных релевантных источников; промпт требует снабжать ссылкой каждое содержательное утверждение.

- `GET /api/assistant/topics` — мгновенно вернуть последний кэш тематик;
- `POST /api/assistant/topics/refresh` и `GET /api/assistant/topics/status` — запустить фоновое обновление и получить его состояние;
- `GET /api/assistant/status` — доступность провайдера и выбранная модель.
- `POST /api/assistant/chat/{requestId}/cancel` — остановить потоковый ответ;
- `GET /api/assistant/semantic/status` — прогресс фонового семантического индекса;
- `POST /api/assistant/semantic/pause|resume|retry` — управление индексом для администратора;
- `GET /api/assistant/metrics` — среднее время подбора контекста, генерации и полного ответа;
- `GET /api/assistant/profile` — сохранённые инструкции и область источников пользователя;
- `PUT /api/assistant/profile` — сохранить профиль RAG в PostgreSQL.
- `POST /api/assistant/export?format=docx|txt` — сформировать файл с вопросом, ответом и перечнем источников.

Темы возвращаются со значением `confidence`; элементы ниже `OPENAI_TOPIC_MIN_CONFIDENCE`
отбрасываются. Гибридный подбор не требует точного совпадения всех слов: это позволяет находить
смысловые совпадения и сохраняет лемматический поиск как надёжный резервный путь.

## Выбор модели

`aliceai-llm` выбрана как флагманская русскоязычная модель для сложных диалоговых задач и извлечения информации из всего контекста. `yandexgpt-5.1` предназначена для RAG, анализа документов и структурированных результатов, поэтому используется для тематик. Локальная Qwen остаётся резервом без оплаты токенов.

Официальные материалы: [модели Yandex AI Studio](https://aistudio.yandex.ru/ru/docs/ai-studio/concepts/generation/models), [быстрый старт](https://aistudio.yandex.ru/ru/docs/ai-studio/quickstart/), [аутентификация](https://aistudio.yandex.ru/ru/docs/ai-studio/api-ref/authentication).

## Масштабирование

Текущая реализация рассчитана на локальную или пилотную одноузловую коллекцию и хранит HNSW-индекс на диске в
`Searchengine_1/data/assistant-vectors` (каталог исключён из Git). Векторизация выполняется пакетами в фоне, состояние страниц сохраняется в PostgreSQL и после перезапуска продолжается с незавершённого места. Для нескольких серверов или
миллионов фрагментов этот слой можно заменить на pgvector/Qdrant без изменения контроллера и UI, а задания индексации вынести в RabbitMQ или Kafka.
Fine-tuning имеет смысл только после накопления проверенного набора примеров «запрос → эталонный
ответ»; знания из меняющихся документов по-прежнему должны поступать через RAG.
