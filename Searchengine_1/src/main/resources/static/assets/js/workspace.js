(function () {
    'use strict';
    var api = window.backendApiUrl || 'api';
    var state = { user: null, sources: [], groups: [], russianResources: [], scientificCatalogInitialized: false, libraryRequested: location.hash === '#library' };

    function t(ru, en) {
        return window.AppI18n ? window.AppI18n.t(ru, en) : ru;
    }

    function groupDisplayName(group) {
        var name = group && group.name ? String(group.name) : '';
        return window.AppI18n && window.AppI18n.language() === 'en' && name.toLowerCase() === 'основная группа'
            ? 'Main group'
            : name;
    }

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

    function request(path, options) {
        options = options || {};
        options.credentials = 'same-origin';
        options.headers = Object.assign({}, csrfHeaders(), options.headers || {});
        return fetch(api + path, options).then(function (response) {
            return response.json().catch(function () { return {}; }).then(function (body) {
                if (!response.ok || body.result === false) throw new Error(body.error || ('HTTP ' + response.status));
                return body;
            });
        });
    }

    function refreshProfile() {
        return request('/me').then(function (user) {
            state.user = user;
            setText('userInitials', user.initials || 'U');
            setText('drawerInitials', user.initials || 'U');
            setText('userDisplayName', user.displayName || t('Пользователь', 'User'));
            setText('userLogin', user.username || '');
            setText('userRole', user.admin ? t('Администратор', 'Administrator') : t('Посетитель', 'Visitor'));
            setText('userSources', Number(user.sources || 0));
            setText('userPages', Number(user.pages || 0));
            setText('userGroups', Number(user.groups || 0));
            document.body.classList.toggle('VisitorMode', !user.admin);
            if (!user.admin && (location.hash === '#management' || location.hash === '#library')) location.hash = '#dashboard';
            state.sources = Array.isArray(user.sourceData) ? user.sourceData : [];
            renderSources();
            publishSources();
            maybeInitScientificCatalog();
            window.dispatchEvent(new CustomEvent('workspace-user-ready', { detail: user }));
            return loadGroups();
        }).catch(function (error) {
            setText('userLogin', t('Профиль недоступен', 'Profile unavailable'));
            throw error;
        });
    }

    function setText(id, value) {
        var element = document.getElementById(id);
        if (element) element.textContent = value;
    }

    function initProfileDrawer() {
        var trigger = document.getElementById('profileTrigger');
        var close = document.getElementById('profileClose');
        var overlay = document.getElementById('profileOverlay');
        if (!trigger || !close || !overlay) return;
        function toggle(open) {
            var drawer = document.getElementById('profileDrawer');
            drawer.classList.toggle('is-open', open);
            drawer.setAttribute('aria-hidden', String(!open));
            trigger.setAttribute('aria-expanded', String(open));
            overlay.hidden = !open;
            document.body.classList.toggle('ProfileOpen', open);
            if (open) loadSources(false);
        }
        trigger.addEventListener('click', function () { toggle(true); });
        close.addEventListener('click', function () { toggle(false); });
        overlay.addEventListener('click', function () { toggle(false); });
        document.addEventListener('keydown', function (event) { if (event.key === 'Escape') toggle(false); });
    }

    function publishSources() {
        window.workspaceSources = state.sources.slice();
        window.dispatchEvent(new CustomEvent('workspace-sources-ready', { detail: window.workspaceSources }));
    }

    function loadSources(force) {
        if (!force && state.sources.length) {
            renderSources();
            publishSources();
            return Promise.resolve(state.sources);
        }
        return request('/sources').then(function (data) {
            state.sources = data.data || [];
            renderSources();
            publishSources();
            setText('userSources', state.sources.length);
            setText('userPages', state.sources.reduce(function (sum, source) { return sum + Number(source.pages || 0); }, 0));
            return state.sources;
        });
    }

    function renderSources() {
        var list = document.getElementById('mySourcesList');
        if (!list) return;
        if (!state.sources.length) {
            list.innerHTML = '<p>' + t('Источники пока не добавлены.', 'No sources have been added yet.') + '</p>';
            updateDeleteButton();
            return;
        }
        list.innerHTML = state.sources.map(function (source) {
            var working = source.status === 'INDEXING';
            var statusClass = source.indexed ? 'ready' : (working ? 'working' : (source.status === 'FAILED' ? 'error' : ''));
            var type = { WEBSITE: t('Сайт', 'Website'), DOCUMENT: t('Файл', 'File'), SCIENTIFIC_ARTICLE: t('Статья', 'Article') }[source.type] || source.type;
            return '<label class="MySource">' + (state.user && state.user.admin
                    ? '<input type="checkbox" data-source-select value="' + Number(source.id) + '">' : '<span></span>') +
                '<span class="MySource-main"><strong>' + esc(source.name) + '</strong><small>' + esc(type) +
                ' · ' + Number(source.readyPages || 0) + '/' + Number(source.pages || 0) + ' ' + t('стр.', 'pages') + '</small></span>' +
                '<span class="MySource-status MySource-status_' + statusClass + '" title="' + esc(source.status) + '"></span></label>';
        }).join('');
        list.querySelectorAll('[data-source-select]').forEach(function (checkbox) {
            checkbox.addEventListener('change', updateDeleteButton);
        });
        updateDeleteButton();
    }

    function selectedSourceIds() {
        return Array.from(document.querySelectorAll('[data-source-select]:checked')).map(function (box) { return box.value; });
    }

    function updateDeleteButton() {
        var button = document.getElementById('sourcesDeleteSelected');
        if (button) button.disabled = selectedSourceIds().length === 0;
    }

    function initSourceActions() {
        var selectAll = document.getElementById('sourcesSelectAll');
        var deleteButton = document.getElementById('sourcesDeleteSelected');
        if (selectAll) selectAll.addEventListener('click', function () {
            var boxes = Array.from(document.querySelectorAll('[data-source-select]'));
            var shouldCheck = boxes.some(function (box) { return !box.checked; });
            boxes.forEach(function (box) { box.checked = shouldCheck; });
            selectAll.textContent = shouldCheck ? t('Снять выбор', 'Clear selection') : t('Выбрать все', 'Select all');
            updateDeleteButton();
        });
        if (deleteButton) deleteButton.addEventListener('click', function () {
            var ids = selectedSourceIds();
            if (!ids.length || !window.confirm(t('Удалить выбранные источники и весь их поисковый индекс?', 'Delete the selected sources and their entire search index?'))) return;
            deleteButton.disabled = true;
            var params = new URLSearchParams(); ids.forEach(function (id) { params.append('ids', id); });
            request('/sources?' + params.toString(), { method: 'DELETE' }).then(function () {
                return refreshProfile();
            }).catch(function (error) { window.alert(error.message); }).finally(updateDeleteButton);
        });
    }

    function initAssistantSelection() {
        function setAll(checked) {
            document.querySelectorAll('#assistantDocuments input[type="checkbox"]').forEach(function (box) { box.checked = checked; });
        }
        var select = document.getElementById('assistantSelectAll');
        var clear = document.getElementById('assistantClearAll');
        if (select) select.addEventListener('click', function () { setAll(true); });
        if (clear) clear.addEventListener('click', function () { setAll(false); });
    }

    function loadGroups() {
        return request('/groups').then(function (data) {
            state.groups = data.groups || [];
            renderGroups(); renderProfileGroups(); setText('userGroups', state.groups.length);
        });
    }

    function renderProfileGroups() {
        var target = document.getElementById('profileGroupsList');
        if (!target) return;
        target.innerHTML = state.groups.length ? state.groups.map(function (group) {
            return '<div class="ProfileGroupItem"><strong>' + esc(groupDisplayName(group)) + '</strong><small>' +
                (state.user && state.user.admin ? (t('Участников: ', 'Members: ') + Number(group.memberCount || 0)) : (t('Администратор: ', 'Administrator: ') + esc(group.owner))) + '</small></div>';
        }).join('') : t('Нет подключённых групп', 'No connected groups');
    }

    function renderGroups() {
        var target = document.getElementById('groupList');
        if (!target || !state.user || !state.user.admin) return;
        if (!state.groups.length) { target.textContent = t('Создайте первую группу.', 'Create your first group.'); return; }
        target.innerHTML = state.groups.map(function (group) {
            var invite = location.origin + '/app?join=' + encodeURIComponent(group.inviteCode || '');
            var members = (group.members || []).map(function (member) {
                return '<span class="GroupMember">' + esc(member) + '<button type="button" data-remove-member="' + esc(member) + '" data-group="' + group.id + '" title="' + t('Исключить', 'Remove') + '">×</button></span>';
            }).join('');
            var onlyGroup = state.groups.length <= 1;
            var deleteTitle = onlyGroup ? t('У администратора должна остаться хотя бы одна группа', 'An administrator must keep at least one group') : t('Удалить группу', 'Delete group');
            return '<article class="GroupCard" data-group-card="' + group.id + '"><div class="GroupCard-head"><h4>' + esc(groupDisplayName(group)) + '</h4><div class="GroupCard-headActions"><small>' + t('Участников: ', 'Members: ') + Number(group.memberCount || 0) + '</small><button class="GroupCard-delete" type="button" data-delete-group title="' + deleteTitle + '"' + (onlyGroup ? ' disabled' : '') + '>' + t('Удалить', 'Delete') + '</button></div></div>' +
                '<div class="GroupCard-invite"><input readonly value="' + esc(invite) + '"><button type="button" data-copy-invite>' + t('Копировать ссылку', 'Copy link') + '</button></div>' +
                '<small class="GroupCard-inviteHelp">' + t('Отправьте ссылку посетителю. После входа он сразу попадёт в приложение и подключится к вашей группе. Для проверки откройте ссылку в режиме инкогнито.', 'Send this link to a visitor. After signing in, they will open the application and join your group. To test it, open the link in an incognito window.') + '</small>' +
                '<form class="GroupCard-memberForm"><input name="userId" type="email" placeholder="' + t('E-mail зарегистрированного пользователя', 'Registered user e-mail') + '" required><button type="submit">' + t('Добавить', 'Add') + '</button></form>' +
                '<div class="GroupMembers">' + (members || '<small>' + t('В группе пока нет посетителей', 'There are no visitors in this group yet') + '</small>') + '</div></article>';
        }).join('');
    }

    function initGroups() {
        var create = document.getElementById('groupCreateForm');
        var toggle = document.getElementById('groupCreateToggle');
        var list = document.getElementById('groupList');
        if (toggle && create) toggle.addEventListener('click', function () {
            var open = create.hidden;
            create.hidden = !open;
            toggle.setAttribute('aria-expanded', String(open));
            if (open) document.getElementById('groupName').focus();
        });
        if (create) create.addEventListener('submit', function (event) {
            event.preventDefault(); var input = document.getElementById('groupName');
            request('/groups', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: input.value }) })
                .then(function () { input.value = ''; create.hidden = true; if (toggle) toggle.setAttribute('aria-expanded', 'false'); return loadGroups(); }).catch(function (error) { window.alert(error.message); });
        });
        if (list) {
            list.addEventListener('submit', function (event) {
                var form = event.target.closest('.GroupCard-memberForm'); if (!form) return;
                event.preventDefault(); var card = form.closest('[data-group-card]'); var input = form.querySelector('[name="userId"]');
                request('/groups/' + card.getAttribute('data-group-card') + '/members', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId: input.value }) })
                    .then(function () { return loadGroups(); }).catch(function (error) { window.alert(error.message); });
            });
            list.addEventListener('click', function (event) {
                var copy = event.target.closest('[data-copy-invite]');
                if (copy) { var input = copy.parentElement.querySelector('input'); navigator.clipboard.writeText(input.value).then(function () { copy.textContent = t('Скопировано', 'Copied'); }); return; }
                var deleteGroup = event.target.closest('[data-delete-group]');
                if (deleteGroup) {
                    var deleteCard = deleteGroup.closest('[data-group-card]');
                    if (!window.confirm(t('Удалить эту группу? Участники потеряют доступ к источникам администратора.', 'Delete this group? Its members will lose access to the administrator’s sources.'))) return;
                    request('/groups/' + deleteCard.getAttribute('data-group-card'), { method: 'DELETE' })
                        .then(loadGroups).catch(function (error) { window.alert(error.message); });
                    return;
                }
                var remove = event.target.closest('[data-remove-member]'); if (!remove) return;
                if (!window.confirm(t('Исключить пользователя из группы?', 'Remove this user from the group?'))) return;
                request('/groups/' + remove.getAttribute('data-group') + '/members?userId=' + encodeURIComponent(remove.getAttribute('data-remove-member')), { method: 'DELETE' })
                    .then(loadGroups).catch(function (error) { window.alert(error.message); });
            });
        }
    }

    function acceptInvitationFromUrl() {
        var code = new URLSearchParams(location.search).get('join');
        if (!code) return;
        request('/groups/invitations/' + encodeURIComponent(code)).then(function (invite) {
            if (state.user && String(state.user.username || '').toLowerCase() === String(invite.owner || '').toLowerCase()) {
                history.replaceState({}, '', location.pathname + location.hash);
                window.alert(t('Вы открыли собственную ссылку приглашения, поэтому остались в аккаунте администратора. Отправьте ссылку посетителю или откройте её в режиме инкогнито.', 'You opened your own invitation link, so you remained signed in as the administrator. Send it to a visitor or open it in an incognito window.'));
                return null;
            }
            if (!window.confirm(t('Присоединиться к группе «' + invite.name + '» администратора ' + invite.owner + '?', 'Join “' + groupDisplayName(invite) + '”, owned by ' + invite.owner + '?'))) return null;
            return request('/groups/invitations/' + encodeURIComponent(code) + '/accept', { method: 'POST' });
        }).then(function (accepted) {
            if (!accepted) return;
            history.replaceState({}, '', location.pathname + location.hash);
            window.alert(t('Вы подключены к группе. Источники администратора уже доступны.', 'You joined the group. The administrator’s sources are now available.'));
            return refreshProfile();
        }).catch(function (error) { window.alert(error.message); });
    }

    function initSourceImport() {
        var form = document.getElementById('sourceImportForm'); var result = document.getElementById('sourceImportResult');
        if (!form || !result) return;
        form.addEventListener('submit', function (event) {
            event.preventDefault(); var input = document.getElementById('sourceListFile');
            if (!input.files || !input.files.length) { result.className = 'SourceImport-result SourceImport-result_error'; result.textContent = t('Выберите файл CSV или XLSX.', 'Choose a CSV or XLSX file.'); return; }
            var button = form.querySelector('button[type="submit"]'); button.disabled = true;
            var xhr = new XMLHttpRequest(); xhr.open('POST', api + '/importSources');
            var headers = csrfHeaders(); Object.keys(headers).forEach(function (name) { xhr.setRequestHeader(name, headers[name]); });
            xhr.upload.addEventListener('progress', function (progress) { if (progress.lengthComputable) result.textContent = t('Загрузка списка: ', 'Uploading list: ') + Math.round(progress.loaded * 100 / progress.total) + '%'; });
            xhr.addEventListener('load', function () { var data; try { data = JSON.parse(xhr.responseText || '{}'); } catch (ignored) { data = {}; } var ok = xhr.status >= 200 && xhr.status < 300 && data.result; result.className = 'SourceImport-result' + (ok ? '' : ' SourceImport-result_error'); result.textContent = ok ? data.message : (data.error || t('Не удалось импортировать список.', 'Could not import the list.')); if (ok) { input.value = ''; updateFilePicker(input, 'sourceListFileLabel'); if (window.refreshIndexingJobs) window.refreshIndexingJobs(); window.setTimeout(refreshProfile, 1500); } });
            xhr.addEventListener('error', function () { result.className = 'SourceImport-result SourceImport-result_error'; result.textContent = t('Ошибка соединения при импорте.', 'Connection error while importing.'); });
            xhr.addEventListener('loadend', function () { button.disabled = false; }); var data = new FormData(); data.append('file', input.files[0]); xhr.send(data);
        });
    }

    function initScientificCatalog() {
        var form = document.getElementById('scientificSearchForm'); if (!form) return;
        var provider = document.getElementById('scientificProvider'); var category = document.getElementById('scientificCategory'); var topicSuggestion = document.getElementById('scientificTopicSuggestion'); var query = document.getElementById('scientificQuery'); var status = document.getElementById('scientificStatus'); var results = document.getElementById('scientificResults'); var articles = [];
        var recommendationStatus = document.getElementById('scientificRecommendationStatus'); var recommendationTopic = document.getElementById('scientificRecommendationTopic'); var recommendationResults = document.getElementById('scientificRecommendations'); var recommendations = [];
        function articleMarkup(list, attribute) { return list.map(function (article, index) { var metadata = [article.provider, article.year, article.venue, article.citationCount == null ? '' : (t('цитирований: ', 'citations: ') + article.citationCount)].filter(Boolean).join(' · '); var authors = (article.authors || []).join(', '); return '<article class="ScientificArticle"><h4>' + esc(article.title) + '</h4><div class="ScientificArticle-meta">' + esc(authors) + (authors && metadata ? ' · ' : '') + esc(metadata) + '</div>' + (article.abstractText ? '<p>' + esc(article.abstractText) + '</p>' : '') + '<div class="ScientificArticle-actions"><a href="' + esc(article.url) + '" target="_blank" rel="noopener">' + t('Открыть оригинал', 'Open original') + '</a><button type="button" ' + attribute + '="' + index + '">' + t('Добавить в источники', 'Add to sources') + '</button></div></article>'; }).join(''); }
        function bindArticleAdd(container, attribute, list, targetStatus) { if (!container) return; container.addEventListener('click', function (event) { var button = event.target.closest('[' + attribute + ']'); if (!button) return; var article = list[Number(button.getAttribute(attribute))]; if (!article) return; button.disabled = true; button.textContent = t('Добавляется…', 'Adding…'); request('/scientific/articles/add', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(article) }).then(function () { button.textContent = t('Добавлено', 'Added'); if (window.refreshIndexingJobs) window.refreshIndexingJobs(); window.setTimeout(refreshProfile, 1500); }).catch(function (error) { button.disabled = false; button.textContent = t('Повторить', 'Retry'); targetStatus.textContent = error.message; }); }); }
        function applyAssistantTopics(topics) { topics = topics || []; topicSuggestion.innerHTML = '<option value="">' + t('Выберите тему Ассистента', 'Choose an Assistant topic') + '</option>' + topics.map(function (topic) { return '<option value="' + esc(topic) + '">' + esc(topic) + '</option>'; }).join(''); if (topics.length) { recommendationTopic.textContent = t('Основные темы: ', 'Main topics: ') + topics.join(' · '); recommendationTopic.classList.add('is-visible'); if (!query.value.trim()) query.value = topics[0]; } }
        request('/scientific/catalog').then(function (catalog) { provider.innerHTML = (catalog.providers || []).map(function (item) { return '<option value="' + esc(item.id) + '">' + esc(item.name) + ' · ' + esc(window.AppI18n && window.AppI18n.language() === 'en' ? (item.scopeEn || item.scope) : item.scope) + '</option>'; }).join(''); category.innerHTML = (catalog.categories || []).map(function (item) { return '<option value="' + esc(item.id) + '">' + esc(window.AppI18n && window.AppI18n.language() === 'en' ? (item.nameEn || item.name) : item.name) + '</option>'; }).join(''); state.russianResources = catalog.russianResources || []; renderRussianCatalogs(state.russianResources); }).catch(function () { status.textContent = t('Не удалось загрузить список научных каталогов.', 'Could not load the scientific catalog list.'); });
        request('/scientific/recommendation-topics').then(function (data) { applyAssistantTopics(data.topics || []); }).catch(function () {});
        request('/scientific/recommendations?limit=6').then(function (data) { recommendations.splice(0, recommendations.length); Array.prototype.push.apply(recommendations, data.data || []); var topics = data.topics || (data.topic ? [data.topic] : []); applyAssistantTopics(topics); recommendationStatus.textContent = recommendations.length ? t('Подобрано публикаций: ', 'Recommended publications: ') + recommendations.length : (topics.length ? t('По основным темам пока нет результатов. Можно уточнить ключевые слова ниже.', 'No matches for the main topics yet. Refine the keywords below.') : t('Сначала определите тематики на вкладке «Ассистент».', 'Detect topics on the Assistant tab first.')); recommendationResults.innerHTML = articleMarkup(recommendations, 'data-add-recommendation'); }).catch(function () { recommendationStatus.textContent = t('Не удалось загрузить рекомендации. Ручной поиск ниже продолжает работать.', 'Recommendations could not be loaded. Manual search below is still available.'); });
        topicSuggestion.addEventListener('change', function () { if (topicSuggestion.value) { query.value = topicSuggestion.value; query.focus(); } });
        form.addEventListener('submit', function (event) { event.preventDefault(); var button = form.querySelector('button[type="submit"]'); button.disabled = true; status.textContent = t('Ищу публикации…', 'Searching publications…'); results.innerHTML = ''; var params = new URLSearchParams({ provider: provider.value, category: category.value, query: query.value.trim(), limit: '10' }); request('/scientific/articles?' + params.toString()).then(function (data) { articles = data.data || []; status.textContent = articles.length ? (t('Найдено публикаций: ', 'Publications found: ') + articles.length) : t('Публикации не найдены. Измените запрос или каталог.', 'No publications found. Try another query or catalog.'); results.innerHTML = articles.map(function (article, index) { var metadata = [article.provider, article.year, article.venue, article.citationCount == null ? '' : (t('цитирований: ', 'citations: ') + article.citationCount)].filter(Boolean).join(' · '); var authors = (article.authors || []).join(', '); return '<article class="ScientificArticle"><h4>' + esc(article.title) + '</h4><div class="ScientificArticle-meta">' + esc(authors) + (authors && metadata ? ' · ' : '') + esc(metadata) + '</div>' + (article.abstractText ? '<p>' + esc(article.abstractText) + '</p>' : '') + '<div class="ScientificArticle-actions"><a href="' + esc(article.url) + '" target="_blank" rel="noopener">' + t('Открыть оригинал', 'Open original') + '</a><button type="button" data-add-article="' + index + '">' + t('Добавить в источники', 'Add to sources') + '</button></div></article>'; }).join(''); }).catch(function (error) { status.textContent = error.message; }).finally(function () { button.disabled = false; }); });
        results.addEventListener('click', function (event) { var button = event.target.closest('[data-add-article]'); if (!button) return; var article = articles[Number(button.getAttribute('data-add-article'))]; if (!article) return; button.disabled = true; button.textContent = t('Добавляется…', 'Adding…'); request('/scientific/articles/add', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(article) }).then(function () { button.textContent = t('Добавлено', 'Added'); if (window.refreshIndexingJobs) window.refreshIndexingJobs(); window.setTimeout(refreshProfile, 1500); }).catch(function (error) { button.disabled = false; button.textContent = t('Повторить', 'Retry'); status.textContent = error.message; }); });
        bindArticleAdd(recommendationResults, 'data-add-recommendation', recommendations, recommendationStatus);
    }

    function maybeInitScientificCatalog(force) {
        if (force === true) state.libraryRequested = true;
        if (!state.user || !state.user.admin || (!state.libraryRequested && location.hash !== '#library')
                || state.scientificCatalogInitialized) return;
        state.scientificCatalogInitialized = true;
        initScientificCatalog();
    }

    function renderRussianCatalogs(resources) {
        var target = document.getElementById('russianCatalogs');
        if (!target) return;
        target.innerHTML = resources.map(function (item) {
            var english = window.AppI18n && window.AppI18n.language() === 'en';
            return '<a class="RussianCatalog" href="' + esc(item.url) + '" target="_blank" rel="noopener"><strong>' + esc(item.name) + '</strong><small>' + esc(english ? (item.descriptionEn || item.description) : item.description) + '</small></a>';
        }).join('');
    }

    function updateFilePicker(input, labelId) {
        var label = document.getElementById(labelId);
        if (!label) return;
        if (!input.files || !input.files.length) {
            label.textContent = input.multiple ? t('Файлы не выбраны', 'No files selected') : t('Файл не выбран', 'No file selected');
        } else if (input.files.length === 1) label.textContent = input.files[0].name;
        else label.textContent = t('Выбрано файлов: ', 'Files selected: ') + input.files.length;
    }

    function initFilePickers() {
        [['documentFile', 'documentFileLabel'], ['sourceListFile', 'sourceListFileLabel']].forEach(function (pair) {
            var input = document.getElementById(pair[0]);
            if (!input) return;
            input.addEventListener('change', function () { updateFilePicker(input, pair[1]); });
            updateFilePicker(input, pair[1]);
        });
    }

    initProfileDrawer(); initSourceActions(); initAssistantSelection(); initGroups(); initSourceImport(); initFilePickers();
    var libraryLink = document.querySelector('.Tabs-link[href="#library"]');
    if (libraryLink) libraryLink.addEventListener('click', function () { maybeInitScientificCatalog(true); });
    refreshProfile().then(acceptInvitationFromUrl).catch(function () {});
    window.addEventListener('hashchange', maybeInitScientificCatalog);
    window.addEventListener('app-language-change', function () {
        if (state.user) {
            setText('userRole', state.user.admin ? t('Администратор', 'Administrator') : t('Посетитель', 'Visitor'));
            renderSources(); renderGroups(); renderProfileGroups(); renderRussianCatalogs(state.russianResources);
            [['documentFile', 'documentFileLabel'], ['sourceListFile', 'sourceListFileLabel']].forEach(function (pair) {
                var input = document.getElementById(pair[0]); if (input) updateFilePicker(input, pair[1]);
            });
        }
    });
    window.refreshWorkspace = refreshProfile;
}());
