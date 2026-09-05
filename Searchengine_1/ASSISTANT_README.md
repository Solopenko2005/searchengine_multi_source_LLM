# LLM-ассистент и анализ тематик

## Как это работает

1. Новые страницы разбиваются на перекрывающиеся смысловые фрагменты. Nomic Embeddings строит векторы в фоне, поэтому обычный поиск доступен ещё до завершения этой операции. Пустые и технические страницы отмечаются как `SKIPPED`, чтобы очередь не выбирала их повторно и гарантированно доходила до 100%.
2. Запрос одновременно проходит через поиск по леммам и локальный HNSW-векторный индекс Lucene.
3. Результаты объединяются методом Reciprocal Rank Fusion и фильтруются по выбранным источникам и правам пользователя.
4. Контекст, профиль пользователя и ограниченная история отправляются в OpenAI-совместимый Responses API.
5. Ответ поступает в интерфейс частями. Пользователь видит этап обработки и может остановить генерацию.
6. Тематики вычисляются в фоне по репрезентативным фрагментам коллекции и сохраняются в кэше. Ранжирование предпочитает цели, методы, результаты и выводы исследования и понижает библиографические, регистрационные и издательские блоки. Повторное открытие вкладки не запускает дорогой анализ заново.
7. При недоступности embeddings или LLM система автоматически продолжает работать по леммам и формирует локальный ответ.

Это RAG-настройка под источники пользователя, а не fine-tuning модели: материалы не меняют веса модели и добавляются только в контекст конкретного запроса.

## Настройка

OpenAI API можно использовать, но он не обязателен. В локальном режиме LM Studio укажите:

```text
LLM_PROVIDER=LM Studio
OPENAI_ENABLED=true
OPENAI_BASE_URL=http://localhost:1234/v1
OPENAI_API_KEY=lm-studio
OPENAI_MODEL=local-qwen3-8b
EMBEDDING_BASE_URL=http://localhost:1234/v1
EMBEDDING_API_KEY=lm-studio
EMBEDDING_MODEL=text-embedding-nomic-embed-text-v1.5
```

Дополнительные параметры:

```text
OPENAI_ENABLED=true
OPENAI_BASE_URL=https://api.openai.com/v1
OPENAI_MODEL=gpt-5.6-terra
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

Код использует `POST /v1/responses`, повторы с учётом `Retry-After` при временных ошибках и Structured Outputs (JSON Schema) для анализа тематик. API-ключ не логируется и не возвращается клиенту.

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

`gpt-5.6-terra` выбран как практичный баланс качества, задержки и стоимости. Для наиболее сложного анализа можно задать `OPENAI_MODEL=gpt-5.6-sol`. Модель должна быть доступна API-проекту владельца ключа.

Официальные материалы: [Models](https://developers.openai.com/api/docs/models), [Latest model guide](https://developers.openai.com/api/docs/guides/latest-model), [API quickstart](https://platform.openai.com/docs/quickstart/make-your-first-api-request).

## Масштабирование

Текущая реализация рассчитана на локальную или пилотную одноузловую коллекцию и хранит HNSW-индекс на диске в
`Searchengine_1/data/assistant-vectors` (каталог исключён из Git). Векторизация выполняется пакетами в фоне, состояние страниц сохраняется в PostgreSQL и после перезапуска продолжается с незавершённого места. Для нескольких серверов или
миллионов фрагментов этот слой можно заменить на pgvector/Qdrant без изменения контроллера и UI, а задания индексации вынести в RabbitMQ или Kafka.
Fine-tuning имеет смысл только после накопления проверенного набора примеров «запрос → эталонный
ответ»; знания из меняющихся документов по-прежнему должны поступать через RAG.
