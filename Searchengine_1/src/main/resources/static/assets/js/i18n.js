(function () {
    'use strict';

    var messages = {
        en: {
            'app.name': 'Research search',
            'nav.statistics': 'Statistics', 'nav.management': 'Management',
            'nav.library': 'Library', 'nav.search': 'Search', 'nav.assistant': 'Assistant',
            'language.label': 'Language:', 'language.ru': 'English',
            'auth.logo': 'Research search logo',
            'auth.login.title': 'Sign in to research search',
            'auth.register.title': 'Sign up for research search',
            'auth.welcome': 'Hello!',
            'auth.intro': 'We built this system to search scientific articles, learning materials and technical documentation, so that you can find accurate academic information without ads and information noise. Enjoy using it!',
            'auth.signup': 'Sign up', 'auth.signup.disabled': 'Registration is disabled in settings',
            'auth.signin.title': 'Sign in to your account', 'auth.signin': 'Sign in',
            'auth.username': 'Login or e-mail', 'auth.password': 'Password',
            'auth.password.show': 'Show password', 'auth.password.hide': 'Hide password',
            'auth.forgot': 'Forgot password?',
            'auth.redirect.note': 'After signing in, you will be redirected to the search system.',
            'auth.error.credentials': 'Incorrect login or password. Check your details and try again.',
            'auth.logout.success': 'You have signed out successfully.',
            'auth.signup.success': 'Registration is complete. Sign in with your e-mail and password.',
            'auth.password.changed': 'Password changed. Sign in with your new password.',
            'auth.create.title': 'Create an account',
            'auth.create.intro': 'Choose the appropriate access type. A visitor uses an administrator\'s sources; administrator rights are activated after e-mail confirmation.',
            'auth.already': 'Already have an account', 'auth.access': 'Access:',
            'auth.firstName': 'First name', 'auth.lastName': 'Last name',
            'auth.password.rule': 'Password: at least 8 characters',
            'auth.confirmPassword': 'Confirm password', 'auth.create': 'Create account',
            'auth.back': 'Back to sign in', 'role.visitor': 'Visitor',
            'role.select': 'your choice', 'role.admin': 'Administrator',
            'auth.access.choose': 'Choose access type',
            'role.visitor.help': 'Statistics, search and assistant using the administrator\'s sources',
            'role.admin.help': 'Manage sources and groups after e-mail confirmation',
            'role.admin.verify': 'For an administrator, we will send a confirmation link to the specified address.',
            'auth.admin.pending': 'Check your inbox and confirm your e-mail. You can then sign in as an administrator.',
            'auth.admin.verified': 'E-mail confirmed. Administrator rights are active.',
            'auth.admin.verifyError': 'The confirmation link is invalid or has expired.',
            'recovery.title': 'Password recovery', 'recovery.access': 'Restore access',
            'recovery.instructions': 'Enter the e-mail address for your account. We will send a one-time link valid for 10 minutes.',
            'recovery.sent': 'Message sent. Check your Inbox and Spam folders.',
            'recovery.send': 'Send recovery link',
            'reset.title': 'New password', 'reset.new': 'New password',
            'reset.instructions': 'Create a new password with at least eight characters.',
            'reset.change': 'Change password', 'reset.save': 'Save password',
            'groups.title': 'Access groups',
            'groups.lead': 'Give visitors access to the administrator’s shared source collection.',
            'library.title': 'Research library',
            'library.lead': 'Find publications, review abstracts and add selected articles to the shared collection.',
            'library.catalog': 'Scientific publications catalogue', 'library.find': 'Find articles',
            'library.recommendations': 'Recommended from your documents',
            'library.recommendations.lead': 'The system detects the dominant topic in uploaded documents and suggests related research.',
            'sources.mine': 'My sources', 'profile.api': 'API documentation (Swagger)',
            'profile.logout': 'Sign out'
        },
        ru: {}
    };

    var exact = {
        'Статистика': 'Statistics', 'Управление': 'Management', 'Библиотека': 'Library',
        'Поиск': 'Search', 'Ассистент': 'Assistant', 'Научный поиск': 'Research search',
        'Источники': 'Sources', 'источники': 'sources', 'страницы': 'pages', 'леммы': 'lemmas',
        'Сайты': 'Websites', 'Документы': 'Documents', 'Идёт индексация': 'Indexing in progress',
        'Показатели обновляются автоматически по мере обработки источников.': 'Statistics update automatically as sources are processed.',
        'Добавление и индексация источников': 'Add and index sources',
        'Начать индексацию': 'Start indexing', 'Обновить базовые сайты': 'Update default websites',
        'Проиндексировать базовые сайты': 'Index default websites',
        'Статус индекса проверяется…': 'Checking index status…',
        'Что делает эта кнопка?': 'What does this button do?',
        'Она запускает повторный обход сайтов из базового списка приложения. Сайты, документы и таблицы, которые вы добавляете ниже, начинают обрабатываться автоматически. Нажимать эту кнопку для них не нужно.': 'It starts a fresh crawl of the websites in the application’s default list. Websites, documents and spreadsheets added below start processing automatically. You do not need this button for them.',
        'Остановить': 'Stop', 'Добавить сайт': 'Add website', 'Добавить/обновить': 'Add/update',
        'Добавить новый сайт-источник:': 'Add a new website source:',
        'Добавить/обновить отдельную страницу уже добавленного источника:': 'Add or update a page from an existing source:',
        'Загрузить документы (DOCX или PDF) как источники:': 'Upload documents (DOCX or PDF) as sources:',
        'Импортировать список источников из CSV или XLSX:': 'Import a source list from CSV or XLSX:',
        'Загрузить и проиндексировать': 'Upload and index', 'Импортировать список': 'Import list',
        'Выбрать документы': 'Choose documents', 'Выбрать таблицу': 'Choose spreadsheet',
        'Файлы не выбраны': 'No files selected', 'Файл не выбран': 'No file selected',
        'Можно выбрать сразу несколько файлов размером до 200 МБ каждый': 'You can select several files up to 200 MB each',
        'Столбцы: URL/Ссылка, Название и Тип. Тип «site» запускает обход сайта, «page» добавляет только одну страницу.': 'Columns: URL/Link, Name and Type. Type “site” crawls a website; “page” adds only one page.',
        'Создать группу': 'Create group', 'Добавить группу': 'Add group', 'Удалить группу': 'Delete group',
        'Ход индексации': 'Indexing progress',
        'Уже обработанные страницы доступны в поиске, даже пока источник ещё индексируется.': 'Processed pages are searchable while the rest of a source is still being indexed.',
        'Остановить все': 'Stop all', 'Всего': 'Total', 'В работе': 'In progress',
        'Завершено': 'Completed', 'Требуют внимания': 'Needs attention',
        'Общий прогресс активных заданий': 'Overall active job progress',
        'Источник': 'Source', 'Состояние': 'Status', 'Прогресс': 'Progress',
        'Доступно в поиске': 'Searchable', 'В очереди': 'Queued', 'Индексируется': 'Indexing',
        'Останавливается': 'Stopping', 'Остановлено': 'Stopped', 'Ошибка': 'Error', 'Готово': 'Ready',
        'Все источники': 'All sources', 'Показать ещё': 'Show more',
        'Запрос': 'Query', 'Найти': 'Search', 'Найдено': 'Found', 'результатов': 'results',
        'LLM-ассистент': 'LLM assistant',
        'Настройка ассистента под мои источники': 'Configure assistant for my sources',
        'Роль и область внимания': 'Role and focus',
        'Источники в текущей рабочей области': 'Sources in this workspace',
        'Выбрать все': 'Select all', 'Снять выбор': 'Clear selection',
        'Сохранить профиль': 'Save profile', 'Популярные тематики в источниках': 'Popular topics across sources',
        'Проанализировать': 'Analyze', 'Анализирую источники...': 'Analyzing sources...',
        'Отправить': 'Send', 'Скачать последний ответ:': 'Download latest answer:',
        'Профиль': 'Profile', 'Пользователь': 'User', 'Администратор': 'Administrator',
        'Посетитель': 'Visitor', 'Мои группы': 'My groups',
        'Нет подключённых групп': 'No connected groups', 'Удалить': 'Delete', 'Закрыть': 'Close',
        'Загрузка…': 'Loading…', 'Загрузка списка источников…': 'Loading sources…',
        'Загрузка групп…': 'Loading groups…', 'Выйти из аккаунта': 'Sign out',
        'Научная библиотека': 'Research library',
        'Каталог научных публикаций': 'Scientific publications catalogue',
        'Ищите статьи через открытые научные API или переходите в проверенные российские каталоги. Если каталог передаёт число цитирований, первыми показываются наиболее цитируемые работы.': 'Search articles through open scientific APIs or browse trusted Russian catalogues. When a catalogue provides citation counts, the most cited papers are shown first.',
        'Российские научные ресурсы': 'Russian scientific resources',
        'Подборка каталогов, репозиториев и библиотек для самостоятельного поиска.': 'A curated list of catalogues, repositories and libraries for independent research.',
        'Найти статьи': 'Find articles', 'Добавить в источники': 'Add to sources',
        'Задайте вопрос по всем вашим источникам: сайтам, научным статьям и файлам. Ассистент найдёт релевантные материалы и даст ответ со ссылками.': 'Ask a question about all your sources: websites, scientific articles and files. The assistant will find relevant material and answer with links.',
        'Здравствуйте! Спросите меня о содержании сайтов, научных статей или файлов. Я отвечу и приведу использованные источники.': 'Hello! Ask me about your websites, scientific articles or files. I will answer and cite the sources used.',
        'источников': 'sources', 'страниц': 'pages', 'групп': 'groups',
        'Забыли пароль?': 'Forgot password?', 'Войти': 'Sign in', 'Регистрация': 'Sign up',
        'Войдите в аккаунт': 'Sign in to your account', 'Пароль': 'Password',
        'Логин или e-mail': 'Login or e-mail', 'Уже есть аккаунт': 'Already have an account',
        'Зарегистрироваться': 'Create account', 'Имя': 'First name', 'Фамилия': 'Last name',
        'Повторите пароль': 'Confirm password', 'Восстановление доступа': 'Restore access',
        'Вернуться ко входу': 'Back to sign in', 'Отправить ссылку': 'Send link',
        'Новый пароль': 'New password', 'Изменение пароля': 'Change password',
        'Сохранить пароль': 'Save password', 'Поисковая система': 'Research search',
        'Язык:': 'Language:', 'Русский': 'English', 'Доступ:': 'Access:'
    };

    var placeholders = {
        'Название источника (необязательно)': 'Source name (optional)',
        'Название новой группы': 'New group name',
        'Например: селекция засухоустойчивой пшеницы': 'Example: breeding drought-resistant wheat',
        'Введите вопрос по вашим источникам...': 'Ask a question about your sources...',
        'Например: анализируй источники как агроном; обращай особое внимание на технологии селекции и практические ограничения': 'Example: analyze the sources as an agronomist; focus on breeding methods and practical constraints',
        'E-mail зарегистрированного пользователя': 'Registered user e-mail',
        'Введите поисковый запрос': 'Enter a search query'
    };

    var originals = new WeakMap();
    var attributeOriginals = new WeakMap();
    var applying = false;

    function current() {
        return localStorage.getItem('search-language') === 'en' ? 'en' : 'ru';
    }

    function translatedAttribute(element, attribute, key, lang) {
        var values = attributeOriginals.get(element) || {};
        if (!Object.prototype.hasOwnProperty.call(values, attribute)) values[attribute] = element.getAttribute(attribute) || '';
        attributeOriginals.set(element, values);
        var original = values[attribute];
        element.setAttribute(attribute, lang === 'ru' ? original : (messages.en[key] || exact[original] || original));
    }

    function translateTextNodes(root, lang) {
        var walker = document.createTreeWalker(root || document.body, NodeFilter.SHOW_TEXT);
        var node;
        while ((node = walker.nextNode())) {
            if (!node.parentElement || node.parentElement.hasAttribute('data-i18n')
                    || /^(SCRIPT|STYLE|TEXTAREA|TITLE)$/.test(node.parentElement.tagName)) continue;
            if (!originals.has(node)) originals.set(node, node.nodeValue);
            var original = originals.get(node);
            var trimmed = original.trim();
            if (!trimmed) continue;
            node.nodeValue = lang === 'ru' ? original : (exact[trimmed] ? original.replace(trimmed, exact[trimmed]) : original);
        }
    }

    function translateTree(root, lang) {
        var scope = root && root.querySelectorAll ? root : document;
        scope.querySelectorAll('[data-i18n]').forEach(function (element) {
            if (!element.dataset.i18nRu) element.dataset.i18nRu = element.textContent.trim();
            element.textContent = lang === 'ru'
                ? element.dataset.i18nRu
                : (messages.en[element.dataset.i18n] || exact[element.dataset.i18nRu] || element.dataset.i18nRu);
        });
        scope.querySelectorAll('[data-i18n-title]').forEach(function (element) {
            translatedAttribute(element, 'title', element.dataset.i18nTitle, lang);
        });
        scope.querySelectorAll('[data-i18n-alt]').forEach(function (element) {
            translatedAttribute(element, 'alt', element.dataset.i18nAlt, lang);
        });
        scope.querySelectorAll('[data-i18n-aria-label]').forEach(function (element) {
            translatedAttribute(element, 'aria-label', element.dataset.i18nAriaLabel, lang);
        });
        scope.querySelectorAll('input[placeholder],textarea[placeholder]').forEach(function (input) {
            if (!input.dataset.placeholderRu) input.dataset.placeholderRu = input.placeholder;
            input.placeholder = lang === 'ru'
                ? input.dataset.placeholderRu
                : (placeholders[input.dataset.placeholderRu] || input.dataset.placeholderRu);
        });
        translateTextNodes(root && root.nodeType ? root : document.body, lang);
    }

    function apply(lang) {
        lang = lang === 'en' ? 'en' : 'ru';
        applying = true;
        document.documentElement.lang = lang;
        translateTree(document, lang);
        document.querySelectorAll('[data-language]').forEach(function (button) {
            button.classList.toggle('is-active', button.dataset.language === lang);
        });
        localStorage.setItem('search-language', lang);
        applying = false;
        window.dispatchEvent(new CustomEvent('app-language-change', { detail: { language: lang } }));
    }

    document.addEventListener('click', function (event) {
        var button = event.target.closest('[data-language]');
        if (button) apply(button.dataset.language);
    });

    document.addEventListener('DOMContentLoaded', function () {
        apply(current());
        new MutationObserver(function (mutations) {
            if (applying) return;
            mutations.forEach(function (mutation) {
                mutation.addedNodes.forEach(function (node) {
                    if (node.nodeType === Node.ELEMENT_NODE) translateTree(node, current());
                });
            });
        }).observe(document.body, { childList: true, subtree: true });
    });

    window.AppI18n = {
        language: current,
        apply: apply,
        t: function (ru, en) { return current() === 'en' ? en : ru; }
    };
}());
