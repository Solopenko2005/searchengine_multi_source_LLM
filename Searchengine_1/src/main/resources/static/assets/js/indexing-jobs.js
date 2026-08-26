(function () {
    'use strict';
    var body = document.getElementById('indexingJobsBody');
    if (!body) return;
    var api = window.backendApiUrl || 'api';
    var stopAll = document.getElementById('indexingStopAll');
    var lastData = null;

    function t(ru, en) { return window.AppI18n ? window.AppI18n.t(ru, en) : ru; }

    function esc(value) {
        return String(value == null ? '' : value).replace(/[&<>"']/g, function (symbol) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[symbol];
        });
    }

    function csrfHeaders() {
        var token = document.querySelector('meta[name="_csrf"]');
        var header = document.querySelector('meta[name="_csrf_header"]');
        var result = {};
        if (token && header) result[header.content] = token.content;
        return result;
    }

    function stateLabel(state) {
        return {
            QUEUED: t('В очереди', 'Queued'), RUNNING: t('Индексируется', 'Indexing'),
            STOPPING: t('Останавливается', 'Stopping'), STOPPED: t('Остановлено', 'Stopped'),
            COMPLETED: t('Завершено', 'Completed'), FAILED: t('Ошибка', 'Error')
        }[state] || state;
    }

    function stageLabel(stage) {
        var stages = {
            'Готово': 'Ready', 'Ожидает запуска': 'Waiting to start', 'Обход страниц': 'Crawling pages',
            'Чтение документа': 'Reading document', 'Обработка документа': 'Processing document',
            'Останавливается': 'Stopping', 'Остановлено': 'Stopped', 'Ошибка': 'Error'
        };
        return window.AppI18n && window.AppI18n.language() === 'en' ? (stages[stage] || stage) : stage;
    }

    function render(data) {
        lastData = data;
        var jobs = data.jobs || [];
        var summary = data.summary || {};
        document.getElementById('indexingTotal').textContent = summary.total || 0;
        document.getElementById('indexingActive').textContent = summary.active || 0;
        document.getElementById('indexingCompleted').textContent = summary.completed || 0;
        document.getElementById('indexingProblems').textContent = summary.problems || 0;
        stopAll.disabled = !(summary.active > 0);

        var active = jobs.filter(function (job) { return job.canStop; });
        var overall = active.length ? Math.round(active.reduce(function (sum, job) {
            return sum + Number(job.progress || 0);
        }, 0) / active.length) : (jobs.length && jobs.every(function (job) { return job.state === 'COMPLETED'; }) ? 100 : 0);
        document.getElementById('indexingOverallText').textContent = overall + '%';
        document.getElementById('indexingOverallBar').style.width = overall + '%';
        document.querySelector('.IndexingQueue-progress').setAttribute('aria-valuenow', overall);

        if (!jobs.length) {
            body.innerHTML = '<tr><td class="IndexingQueue-empty" colspan="5">' + t('Задания индексации пока не запускались', 'No indexing jobs have been started yet') + '</td></tr>';
            return;
        }
        body.innerHTML = jobs.map(function (job) {
            var progress = Math.max(0, Math.min(100, Number(job.progress || 0)));
            var ready = Number(job.searchable || 0);
            var availability = ready > 0 ? (t('Да, ', 'Yes, ') + ready + ' ' + t('стр.', 'pages')) : t('Пока нет', 'Not yet');
            var sourceType = job.sourceType === 'DOCUMENT' ? t('ФАЙЛ', 'FILE')
                : (job.sourceType === 'SCIENTIFIC_ARTICLE' ? t('СТАТЬЯ', 'ARTICLE') : t('САЙТ', 'WEBSITE'));
            var stop = job.canStop ? '<button class="IndexingQueue-stop" type="button" data-stop-job="' + esc(job.id) + '">' + t('Остановить', 'Stop') + '</button>' : '';
            var error = job.error ? '<small title="' + esc(job.error) + '">' + esc(job.error) + '</small>' : '';
            return '<tr>' +
                '<td class="IndexingQueue-source" data-label="' + t('Источник', 'Source') + '"><strong><span class="IndexingQueue-type">' + sourceType + '</span>' + esc(job.sourceName) + '</strong><small>' + esc(job.sourceUrl || '') + '</small></td>' +
                '<td data-label="' + t('Состояние', 'Status') + '"><div class="IndexingQueue-stateCell"><span class="IndexingQueue-state IndexingQueue-state_' + String(job.state).toLowerCase() + '">' + esc(stateLabel(job.state)) + '</span><small>' + esc(stageLabel(job.stage || '')) + '</small>' + error + '</div></td>' +
                '<td data-label="' + t('Прогресс', 'Progress') + '"><div class="IndexingQueue-miniProgress"><div><span style="width:' + progress + '%"></span></div><small>' + progress + '% · ' + Number(job.processed || 0) + '/' + Number(job.discovered || 0) + '</small></div></td>' +
                '<td data-label="' + t('Доступно', 'Searchable') + '"><span class="IndexingQueue-searchReady ' + (ready > 0 ? 'IndexingQueue-searchReady_yes' : '') + '">' + availability + '</span></td>' +
                '<td data-label="' + t('Действия', 'Actions') + '">' + stop + '</td></tr>';
        }).join('');
        document.getElementById('indexingUpdated').textContent = t('Обновлено: ', 'Updated: ') + new Date().toLocaleTimeString(window.AppI18n && window.AppI18n.language() === 'en' ? 'en-GB' : 'ru-RU');
    }

    function refresh() {
        fetch(api + '/indexing/jobs', { credentials: 'same-origin' })
            .then(function (response) { if (!response.ok) throw new Error('HTTP ' + response.status); return response.json(); })
            .then(render)
            .catch(function () { document.getElementById('indexingUpdated').textContent = t('Не удалось обновить состояние', 'Could not refresh status'); });
    }

    function stop(url, button) {
        if (button) button.disabled = true;
        fetch(url, { method: 'POST', credentials: 'same-origin', headers: csrfHeaders() })
            .then(function () { refresh(); })
            .catch(function () { if (button) button.disabled = false; });
    }

    body.addEventListener('click', function (event) {
        var button = event.target.closest('[data-stop-job]');
        if (button) stop(api + '/indexing/jobs/' + encodeURIComponent(button.dataset.stopJob) + '/stop', button);
    });
    stopAll.addEventListener('click', function () { stop(api + '/indexing/jobs/stop-all', stopAll); });
    window.refreshIndexingJobs = refresh;
    window.addEventListener('app-language-change', function () { if (lastData) render(lastData); });
    refresh();
    window.setInterval(refresh, 3000);
})();
