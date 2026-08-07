(function () {
    'use strict';
    var body = document.getElementById('indexingJobsBody');
    if (!body) return;
    var api = window.backendApiUrl || 'api';
    var stopAll = document.getElementById('indexingStopAll');

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

    var labels = {
        QUEUED: 'В очереди', RUNNING: 'Индексируется', STOPPING: 'Останавливается',
        STOPPED: 'Остановлено', COMPLETED: 'Завершено', FAILED: 'Ошибка'
    };

    function render(data) {
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
            body.innerHTML = '<tr><td class="IndexingQueue-empty" colspan="5">Задания индексации пока не запускались</td></tr>';
            return;
        }
        body.innerHTML = jobs.map(function (job) {
            var progress = Math.max(0, Math.min(100, Number(job.progress || 0)));
            var ready = Number(job.searchable || 0);
            var availability = ready > 0 ? ('Да, ' + ready + ' стр.') : 'Пока нет';
            var sourceType = job.sourceType === 'DOCUMENT' ? 'ФАЙЛ' : 'САЙТ';
            var stop = job.canStop ? '<button class="IndexingQueue-stop" type="button" data-stop-job="' + esc(job.id) + '">Остановить</button>' : '';
            var error = job.error ? '<small title="' + esc(job.error) + '">' + esc(job.error) + '</small>' : '';
            return '<tr>' +
                '<td class="IndexingQueue-source" data-label="Источник"><strong><span class="IndexingQueue-type">' + sourceType + '</span>' + esc(job.sourceName) + '</strong><small>' + esc(job.sourceUrl || '') + '</small></td>' +
                '<td data-label="Состояние"><span class="IndexingQueue-state IndexingQueue-state_' + String(job.state).toLowerCase() + '">' + esc(labels[job.state] || job.state) + '</span><small>' + esc(job.stage || '') + '</small>' + error + '</td>' +
                '<td data-label="Прогресс"><div class="IndexingQueue-miniProgress"><div><span style="width:' + progress + '%"></span></div><small>' + progress + '% · ' + Number(job.processed || 0) + '/' + Number(job.discovered || 0) + '</small></div></td>' +
                '<td data-label="Доступно"><span class="IndexingQueue-searchReady ' + (ready > 0 ? 'IndexingQueue-searchReady_yes' : '') + '">' + availability + '</span></td>' +
                '<td data-label="Действия">' + stop + '</td></tr>';
        }).join('');
        document.getElementById('indexingUpdated').textContent = 'Обновлено: ' + new Date().toLocaleTimeString('ru-RU');
    }

    function refresh() {
        fetch(api + '/indexing/jobs', { credentials: 'same-origin' })
            .then(function (response) { if (!response.ok) throw new Error('HTTP ' + response.status); return response.json(); })
            .then(render)
            .catch(function () { document.getElementById('indexingUpdated').textContent = 'Не удалось обновить состояние'; });
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
    refresh();
    window.setInterval(refresh, 3000);
})();
