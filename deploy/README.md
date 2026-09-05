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
пользователей. Qwen3 8B в Q4-квантизации сможет работать на CPU, но ответы будут
заметно медленнее, чем на GPU. Для интерактивного публичного сервиса LLM лучше
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
sudo ufw enable
```

Порт 1234, PostgreSQL, Redis, 5555, 8771 и 8080 открывать в интернет нельзя.

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

До запуска создайте A-запись домена на IP сервера и дождитесь её обновления.

## 3. Локальная LLM без оплаты токенов

На сервере установите официальный headless-демон LM Studio под отдельным
непривилегированным пользователем:

```bash
curl -fsSL https://lmstudio.ai/install.sh | bash
lms daemon up
lms get qwen/qwen3-8b@q4_k_m --gguf
lms get nomic-ai/nomic-embed-text-v1.5@q4_k_m --gguf
lms load qwen/qwen3-8b --identifier local-qwen3-8b --parallel 2 --yes
lms load nomic-ai/nomic-embed-text-v1.5 --identifier text-embedding-nomic-embed-text-v1.5 --yes
lms server start --port 1234 --bind 0.0.0.0
```

Командой `lms ls` проверьте фактические идентификаторы загруженных моделей и при
необходимости скорректируйте две команды `load`. Сервер слушает сетевой интерфейс,
чтобы к нему мог обратиться контейнер приложения, но firewall обязан блокировать
публичный доступ к 1234. Для постоянной работы настройте llmster как systemd-службу
по официальной инструкции LM Studio.

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
curl -I "https://${APP_HOST}/"
```

После первого запуска проверьте регистрацию посетителя, подтверждение
администратора, восстановление пароля, поиск, индексацию тестовой страницы и ответ
ассистента. Затем меняйте `BOOTSTRAP_ADMIN_EMAILS` только осознанно: адрес из этого
списка получает права администратора без письма.

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
