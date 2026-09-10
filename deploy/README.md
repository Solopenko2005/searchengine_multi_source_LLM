# Публичное размещение

Комплект запускает PostgreSQL, Redis, сервис отправки писем, сервис авторизации,
поисковое приложение и Caddy в одной закрытой Docker-сети. Наружу публикуются
только порты 80/443. HTTPS-сертификат выпускается и обновляется автоматически.

## Рекомендуемый пилотный сервер

- Ubuntu 24.04 LTS;
- 8 vCPU, 12 ГБ RAM, 6 ГБ swap и не менее 100 ГБ NVMe;
- публичный IPv4 и домен с A-записью на этот адрес;
- открыты только SSH, 80/tcp и 443/tcp+udp;
- ежедневный снимок диска либо выгрузка PostgreSQL в отдельное хранилище.

Этого достаточно для текущей коллекции и небольшого числа одновременных
пользователей. Qwen3 4B в Q4-квантизации работает на CPU заметно быстрее версии
8B, но всё равно уступает GPU по скорости. При росте числа одновременных пользователей LLM лучше
вынести на отдельную GPU-машину, оставив тот же `LLM_BASE_URL`.

## 1. Настройка сервера

Первичную настройку можно выполнить подготовленным сценарием от `root`:

```bash
sudo bash deploy/scripts/bootstrap-server.sh
```

Он создаёт пользователя `scientific`, устанавливает Docker Engine с Compose
Plugin, добавляет 6 ГБ swap и настраивает firewall. Эквивалентные правила firewall:

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 443/udp
sudo ufw allow from 172.28.0.0/24 to any port 1234 proto tcp comment 'Scientific Search LLM bridge'
sudo ufw allow from 172.28.0.0/24 to any port 1235 proto tcp comment 'Scientific Search Qwen bridge'
sudo ufw enable
```

Правила для 1234 и 1235 разрешают доступ только из закреплённой внутренней
Docker-подсети. Эти порты, PostgreSQL, Redis, 5555, 8771 и 8080 открывать в
интернет нельзя.

## 2. Конфигурация

```bash
git clone https://github.com/Solopenko2005/searchengine_multi_source_LLM.git
cd searchengine_multi_source_LLM/deploy
cp .env.production.example .env.production
chmod 600 .env.production
```

В `.env.production` укажите домен, SMTP-данные и уникальные секреты. Сгенерировать
их можно командами `openssl rand -base64 48`. Реальный файл с секретами не
добавляется в Git.

На Windows безопасный файл можно сформировать из локального `.env`, не выводя
секреты в терминал:

```powershell
.\deploy\scripts\New-ProductionEnv.ps1 -PublicHost search.example.ru
```

Результат создаётся в игнорируемом каталоге `deploy/backups` и предназначен только
для защищённой передачи на сервер.

До запуска создайте A-запись домена на IP сервера и дождитесь её обновления.

## 3. Локальная LLM без оплаты токенов

На сервере установите официальный headless-демон LM Studio под отдельным
непривилегированным пользователем:

```bash
curl -fsSL https://lmstudio.ai/install.sh | bash
lms daemon up
lms get qwen/qwen3-4b@q4_k_m --gguf
lms get nomic-ai/nomic-embed-text-v1.5@q4_k_m --gguf
lms load nomic-ai/nomic-embed-text-v1.5 --identifier text-embedding-nomic-embed-text-v1.5 --yes
lms server start --port 1234 --bind 0.0.0.0
```

Командой `lms ls` проверьте фактические идентификаторы загруженных моделей и при
необходимости скорректируйте команду `load`. LM Studio обслуживает только
embedding-модель на 1234. Интерактивная Qwen3-4B запускается отдельной службой на
1235 с восемью CPU-потоками, а второй экземпляр той же модели на 1236 выполняет
только анализ тематик с двумя потоками и пониженным приоритетом ОС. Такое разделение
не позволяет ни смысловой индексации, ни анализу тематик блокировать ответы
ассистента. Серверы слушают сетевой интерфейс,
чтобы к ним мог обратиться контейнер приложения, но firewall обязан блокировать
публичный доступ.

Для CPU-сервера производственная конфигурация ограничивает один запрос ассистента
шестью наиболее релевантными фрагментами. Для локальной модели сервис дополнительно
сокращает выдержки примерно до 720 символов, весь подготовленный промпт до 3 200
символов, историю до одной короткой реплики, а обычный ответ до 128 токенов. LM Studio
обслуживает один интерактивный запрос за раз, чтобы отдельный ответ не замедлялся
из-за конкурирующих генераций. Лимиты и тайм-аут можно изменить через
`ASSISTANT_RAG_*`, `OPENAI_MAX_OUTPUT_TOKENS` и `OPENAI_TIMEOUT_SECONDS`, не
пересобирая приложение.

Анализ тематик запускается только кнопкой пользователя и использует
`LLM_BACKGROUND_BASE_URL`, `OPENAI_BACKGROUND_TIMEOUT_SECONDS` и
`OPENAI_BACKGROUND_MAX_OUTPUT_TOKENS`. Отдельный низкоприоритетный процесс не
занимает слот интерактивного чата.

Сохранённый Lucene-векторный индекс переиспользуется после перезапуска, если число
готовых фрагментов совпадает с PostgreSQL. Во время интерактивного ответа фоновая
смысловая индексация автоматически уступает процессор LLM и затем продолжается.

```bash
sudo install -m 0755 deploy/scripts/run-llm-inference.sh /home/scientific/app/deploy/scripts/run-llm-inference.sh
sudo install -m 0755 deploy/scripts/run-llm-background.sh /home/scientific/app/deploy/scripts/run-llm-background.sh
sudo install -m 0644 deploy/systemd/lmstudio.service /etc/systemd/system/lmstudio.service
sudo install -m 0644 deploy/systemd/llm-inference.service /etc/systemd/system/llm-inference.service
sudo install -m 0644 deploy/systemd/llm-background.service /etc/systemd/system/llm-background.service
sudo systemctl daemon-reload
sudo systemctl enable --now lmstudio
sudo systemctl enable --now llm-inference
sudo systemctl enable --now llm-background
```

## 4. Запуск и проверка

```bash
docker compose --env-file .env.production build
docker compose --env-file .env.production up -d
docker compose --env-file .env.production ps
docker compose --env-file .env.production logs --tail=200 search authorization email caddy
```

Проверка с сервера:

```bash
curl -fsS http://localhost:1234/v1/models
curl -fsS -H "Authorization: Bearer ${LLM_API_KEY}" http://localhost:1235/v1/models
curl -I "https://${APP_HOST}/"
deploy/scripts/verify-production.sh
```

После первого запуска проверьте регистрацию посетителя, подтверждение
администратора, восстановление пароля, поиск, индексацию тестовой страницы и ответ
ассистента. Затем меняйте `BOOTSTRAP_ADMIN_EMAILS` только осознанно: адрес из этого
списка получает права администратора без письма.

## Документация API

Единый Swagger UI доступен администратору после входа:

```text
https://${APP_HOST}/api-docs
```

В правой верхней части интерфейса Swagger можно переключаться между тремя
спецификациями:

- поисковая система: все маршруты `/api/**` основного приложения;
- авторизация: регистрация, вход, JWT, подтверждение администратора и
  восстановление доступа;
- отправка e-mail: внутренний API SMTP-сервиса.

Swagger UI и JSON-спецификации основного приложения доступны только пользователю
с ролью `ADMIN`. Методы авторизации исполняются через публичный префикс
`/auth-api`; защищённые операции требуют Bearer JWT. Почтовый API показан для
полноты документации, но намеренно не имеет публичного маршрута: письма отправляют
только доверенные сервисы внутри Docker-сети.

## Обновление

```bash
git pull --ff-only
cd deploy
docker compose --env-file .env.production build
docker compose --env-file .env.production up -d
```

## Резервная копия

Создавайте дамп в каталоге с правами только для администратора:

```bash
mkdir -p backups
chmod 700 backups
docker compose --env-file .env.production exec -T database \
  pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc > "backups/search-$(date +%F-%H%M).dump"
```

Кроме БД сохраняйте Docker volume `scientific-search_assistant_vectors`. После
восстановления одной БД смысловой индекс можно безопасно построить повторно.

## Перенос текущей локальной коллекции

Текущие источники не находятся в GitHub. Перед окончательным переключением нужно
отдельно создать `pg_dump` локальной базы, безопасно передать его на VPS и
восстановить в volume PostgreSQL. Реальный дамп и `.env` нельзя публиковать в Git.
