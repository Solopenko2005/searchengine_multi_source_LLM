(function () {
    'use strict';

    var reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    function wait(milliseconds) {
        return new Promise(function (resolve) {
            window.setTimeout(resolve, milliseconds);
        });
    }

    function initSearchDemo() {
        var demo = document.querySelector('[data-search-demo]');
        if (!demo) return;

        var field = demo.querySelector('.SearchPreview-field');
        var query = demo.querySelector('[data-demo-query]');
        var caret = demo.querySelector('[data-demo-caret]');
        var submit = demo.querySelector('[data-demo-submit]');
        var state = demo.querySelector('[data-demo-state]');
        var progress = demo.querySelector('[data-demo-progress]');
        var results = demo.querySelector('[data-demo-results]');
        var resultCards = Array.prototype.slice.call(results.querySelectorAll('article'));
        var cursor = demo.querySelector('[data-demo-cursor]');
        var queryText = 'морфологический анализ';
        var runNumber = 0;

        function isCurrent(number) {
            return number === runNumber;
        }

        function setState(text, searching) {
            state.textContent = text;
            state.classList.toggle('is-searching', Boolean(searching));
        }

        function moveCursor(target, xRatio, yRatio) {
            var demoRect = demo.getBoundingClientRect();
            var targetRect = target.getBoundingClientRect();
            var x = targetRect.left - demoRect.left + targetRect.width * (xRatio || .5);
            var y = targetRect.top - demoRect.top + targetRect.height * (yRatio || .5);
            cursor.classList.add('is-visible');
            cursor.style.transform = 'translate3d(' + x + 'px,' + y + 'px,0)';
        }

        function clickCursor() {
            cursor.classList.remove('is-clicking');
            void cursor.offsetWidth;
            cursor.classList.add('is-clicking');
        }

        function resetDemo() {
            query.value = '';
            field.classList.remove('is-focused');
            caret.classList.remove('is-visible');
            submit.classList.remove('is-pressed');
            progress.classList.remove('is-visible');
            results.classList.remove('is-visible');
            resultCards.forEach(function (card) { card.classList.remove('is-visible'); });
        }

        function showCompletedState() {
            query.value = queryText;
            setState('2 результата', false);
            results.classList.add('is-visible');
            resultCards.forEach(function (card) { card.classList.add('is-visible'); });
        }

        async function runDemo() {
            var currentRun = ++runNumber;
            resetDemo();
            setState('Ввод запроса', false);
            cursor.classList.remove('is-visible');
            await wait(500);
            if (!isCurrent(currentRun)) return;

            moveCursor(field, .34, .52);
            await wait(760);
            if (!isCurrent(currentRun)) return;
            clickCursor();
            field.classList.add('is-focused');
            caret.classList.add('is-visible');
            await wait(300);

            for (var index = 1; index <= queryText.length; index += 1) {
                if (!isCurrent(currentRun)) return;
                query.value = queryText.slice(0, index);
                await wait(48 + (index % 4) * 13);
            }

            setState('Запрос введён', false);
            await wait(420);
            if (!isCurrent(currentRun)) return;
            caret.classList.remove('is-visible');
            field.classList.remove('is-focused');
            moveCursor(submit, .52, .55);
            await wait(720);
            if (!isCurrent(currentRun)) return;
            clickCursor();
            submit.classList.add('is-pressed');
            setState('Идёт поиск', true);
            progress.classList.add('is-visible');
            await wait(330);
            submit.classList.remove('is-pressed');
            await wait(920);
            if (!isCurrent(currentRun)) return;

            progress.classList.remove('is-visible');
            results.classList.add('is-visible');
            setState('2 результата', false);
            for (var cardIndex = 0; cardIndex < resultCards.length; cardIndex += 1) {
                if (!isCurrent(currentRun)) return;
                resultCards[cardIndex].classList.add('is-visible');
                moveCursor(resultCards[cardIndex], .84, .45);
                await wait(430);
            }

            await wait(3600);
            if (!isCurrent(currentRun)) return;
            cursor.classList.remove('is-visible');
            await wait(550);
            if (isCurrent(currentRun)) runDemo();
        }

        demo.addEventListener('pointermove', function (event) {
            var rect = demo.getBoundingClientRect();
            demo.style.setProperty('--demo-x', ((event.clientX - rect.left) / rect.width * 100).toFixed(1) + '%');
            demo.style.setProperty('--demo-y', ((event.clientY - rect.top) / rect.height * 100).toFixed(1) + '%');
        });

        submit.addEventListener('click', function () {
            if (reducedMotion) {
                showCompletedState();
                return;
            }
            runDemo();
        });

        if (reducedMotion) showCompletedState();
        else runDemo();
    }

    function initInteractiveBackground() {
        var hero = document.querySelector('.Hero');
        var canvas = document.querySelector('[data-interactive-waves]');
        var button = document.querySelector('[data-magnetic-button]');
        if (!hero || !canvas || !button) return;

        var context = canvas.getContext('2d');
        var size = { width: 0, height: 0 };
        var pointer = { x: 0, y: 0, active: false };
        var animationFrame = 0;

        function resizeCanvas() {
            var rect = canvas.getBoundingClientRect();
            var ratio = Math.min(window.devicePixelRatio || 1, 1.5);
            size.width = rect.width;
            size.height = rect.height;
            canvas.width = Math.max(1, Math.round(rect.width * ratio));
            canvas.height = Math.max(1, Math.round(rect.height * ratio));
            context.setTransform(ratio, 0, 0, ratio, 0, 0);
        }

        function resetButton() {
            button.style.setProperty('--magnetic-x', '0px');
            button.style.setProperty('--magnetic-y', '0px');
            button.style.setProperty('--button-glow', '22px');
            button.style.setProperty('--button-shadow-alpha', '.16');
            button.style.setProperty('--wave-opacity', '.18');
            button.style.setProperty('--wave-scale', '.94');
            button.style.setProperty('--wave-border-alpha', '.25');
        }

        function reactButton(event) {
            var rect = button.getBoundingClientRect();
            var centerX = rect.left + rect.width / 2;
            var centerY = rect.top + rect.height / 2;
            var deltaX = event.clientX - centerX;
            var deltaY = event.clientY - centerY;
            var distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY);
            var proximity = Math.max(0, 1 - distance / 240);

            button.style.setProperty('--magnetic-x', (deltaX * proximity * .08).toFixed(2) + 'px');
            button.style.setProperty('--magnetic-y', (deltaY * proximity * .08).toFixed(2) + 'px');
            button.style.setProperty('--button-glow', (22 + proximity * 28).toFixed(1) + 'px');
            button.style.setProperty('--button-shadow-alpha', (.16 + proximity * .18).toFixed(2));
            button.style.setProperty('--wave-opacity', (.18 + proximity * .82).toFixed(2));
            button.style.setProperty('--wave-scale', (.94 + proximity * .16).toFixed(2));
            button.style.setProperty('--wave-border-alpha', (.25 + proximity * .55).toFixed(2));
        }

        function updatePointer(event) {
            var canvasRect = canvas.getBoundingClientRect();
            pointer.x = event.clientX - canvasRect.left;
            pointer.y = event.clientY - canvasRect.top;
            pointer.active = true;
            reactButton(event);
        }

        function drawWaves(time) {
            context.clearRect(0, 0, size.width, size.height);
            var colors = [
                'rgba(61,86,143,.62)',
                'rgba(159,186,241,.76)',
                'rgba(61,86,143,.42)',
                'rgba(234,243,178,.92)'
            ];

            for (var layer = 0; layer < 10; layer += 1) {
                var baseY = size.height * (.28 + layer * .062);
                var amplitude = 16 + layer * 2.4;
                var frequency = .007 + layer * .00018;
                var phase = time * (.00072 + layer * .000035) + layer * .63;
                context.beginPath();

                for (var x = -20; x <= size.width + 20; x += 7) {
                    var y = baseY
                        + Math.sin(x * frequency + phase) * amplitude
                        + Math.sin(x * .0028 - phase * .62) * 11;

                    if (pointer.active) {
                        var distanceX = x - pointer.x;
                        var influence = Math.exp(-(distanceX * distanceX) / 38000);
                        var desiredY = Math.max(-20, Math.min(size.height + 20, pointer.y));
                        y += (desiredY - y) * influence * .16;
                    }

                    if (x === -20) context.moveTo(x, y);
                    else context.lineTo(x, y);
                }

                context.strokeStyle = colors[layer % colors.length];
                context.lineWidth = layer % 3 === 0 ? 1.5 : 1;
                context.stroke();
            }

            if (!reducedMotion) animationFrame = window.requestAnimationFrame(drawWaves);
        }

        hero.addEventListener('pointermove', updatePointer);
        hero.addEventListener('pointerleave', function () {
            pointer.active = false;
            resetButton();
        });
        window.addEventListener('resize', resizeCanvas);
        document.addEventListener('visibilitychange', function () {
            if (document.hidden) {
                window.cancelAnimationFrame(animationFrame);
            } else if (!reducedMotion) {
                animationFrame = window.requestAnimationFrame(drawWaves);
            }
        });

        resizeCanvas();
        resetButton();
        drawWaves(0);
    }

    function initFeatureExplorer() {
        var explorer = document.querySelector('[data-feature-explorer]');
        if (!explorer) return;

        var tabs = Array.prototype.slice.call(explorer.querySelectorAll('[data-feature-tab]'));
        var panels = Array.prototype.slice.call(explorer.querySelectorAll('[data-feature-panel]'));

        function activate(tab, focusTab) {
            var name = tab.getAttribute('data-feature-tab');
            tabs.forEach(function (item) {
                var selected = item === tab;
                item.setAttribute('aria-selected', selected ? 'true' : 'false');
                item.tabIndex = selected ? 0 : -1;
            });
            panels.forEach(function (panel) {
                panel.hidden = panel.getAttribute('data-feature-panel') !== name;
            });
            if (focusTab) tab.focus();
        }

        tabs.forEach(function (tab, index) {
            tab.addEventListener('click', function () { activate(tab, false); });
            tab.addEventListener('keydown', function (event) {
                var direction = event.key === 'ArrowRight' || event.key === 'ArrowDown' ? 1 :
                    event.key === 'ArrowLeft' || event.key === 'ArrowUp' ? -1 : 0;
                if (!direction) return;
                event.preventDefault();
                activate(tabs[(index + direction + tabs.length) % tabs.length], true);
            });
        });
    }

    function initReveal() {
        var items = Array.prototype.slice.call(document.querySelectorAll('.Reveal'));
        if (!items.length) return;
        if (reducedMotion || !('IntersectionObserver' in window)) {
            items.forEach(function (item) { item.classList.add('is-visible'); });
            return;
        }
        var observer = new IntersectionObserver(function (entries) {
            entries.forEach(function (entry) {
                if (!entry.isIntersecting) return;
                entry.target.classList.add('is-visible');
                observer.unobserve(entry.target);
            });
        }, { threshold: .12, rootMargin: '0px 0px -40px' });
        items.forEach(function (item) { observer.observe(item); });
    }

    function initNavigation() {
        var toggle = document.querySelector('[data-nav-toggle]');
        var navigation = document.querySelector('[data-nav]');
        if (!toggle || !navigation) return;

        function closeNavigation() {
            toggle.setAttribute('aria-expanded', 'false');
            navigation.classList.remove('is-open');
        }

        toggle.addEventListener('click', function () {
            var willOpen = toggle.getAttribute('aria-expanded') !== 'true';
            toggle.setAttribute('aria-expanded', willOpen ? 'true' : 'false');
            navigation.classList.toggle('is-open', willOpen);
        });
        navigation.addEventListener('click', closeNavigation);
        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') closeNavigation();
        });
    }

    function initCookieNotice() {
        var notice = document.querySelector('[data-cookie-notice]');
        var accept = document.querySelector('[data-cookie-accept]');
        if (!notice || !accept) return;
        var accepted = false;
        try { accepted = window.localStorage.getItem('scientific-search-cookie-notice') === 'accepted'; } catch (error) { accepted = false; }
        notice.hidden = accepted;
        accept.addEventListener('click', function () {
            try { window.localStorage.setItem('scientific-search-cookie-notice', 'accepted'); } catch (error) { /* Storage may be disabled. */ }
            notice.hidden = true;
        });
    }

    function initFinalWaves() {
        var canvas = document.querySelector('[data-final-waves]');
        if (!canvas) return;
        var context = canvas.getContext('2d');
        var width = 0;
        var height = 0;
        var frame = 0;

        function resize() {
            var rect = canvas.getBoundingClientRect();
            var ratio = Math.min(window.devicePixelRatio || 1, 1.5);
            width = rect.width;
            height = rect.height;
            canvas.width = Math.max(1, Math.round(width * ratio));
            canvas.height = Math.max(1, Math.round(height * ratio));
            context.setTransform(ratio, 0, 0, ratio, 0, 0);
        }

        function draw(time) {
            context.clearRect(0, 0, width, height);
            for (var line = 0; line < 18; line += 1) {
                context.beginPath();
                for (var x = -20; x <= width + 20; x += 8) {
                    var base = height * (.16 + line * .045);
                    var y = base + Math.sin(x * .006 + time * .0004 + line * .35) * (24 + line * 1.3);
                    if (x === -20) context.moveTo(x, y);
                    else context.lineTo(x, y);
                }
                context.strokeStyle = line % 3 === 0 ? 'rgba(234,243,178,.45)' : 'rgba(159,186,241,.38)';
                context.lineWidth = 1;
                context.stroke();
            }
            if (!reducedMotion) frame = window.requestAnimationFrame(draw);
        }

        window.addEventListener('resize', resize);
        document.addEventListener('visibilitychange', function () {
            if (document.hidden) window.cancelAnimationFrame(frame);
            else if (!reducedMotion) frame = window.requestAnimationFrame(draw);
        });
        resize();
        draw(0);
    }

    window.addEventListener('DOMContentLoaded', function () {
        initSearchDemo();
        initInteractiveBackground();
        initFeatureExplorer();
        initReveal();
        initNavigation();
        initCookieNotice();
        initFinalWaves();
    });
}());
