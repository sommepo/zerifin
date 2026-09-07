(() => {
    if (window.zerifinShellReady) return;
    window.zerifinShellReady = true;

    const labels = JSON.parse(window.NativeInterface.getLearningMenuLabels());
    const drawerSelector = '.mainDrawer, .MuiDrawer-paper';
    const observedDrawers = new WeakSet();
    const menuClass = 'zerifin-learning-menu';

    const style = document.createElement('link');
    style.rel = 'stylesheet';
    style.href = '/native/zerifin-shell.css';
    document.head.appendChild(style);

    function addMenu(drawer) {
        if (drawer.querySelector(`.${menuClass}`)) return;
        const host = drawer.querySelector('.mainDrawer-scrollContainer, .scrollContainer, .MuiList-root') || drawer;
        const menu = document.createElement('section');
        menu.className = menuClass;
        menu.setAttribute('aria-label', labels.title);
        const heading = document.createElement('h3');
        heading.textContent = labels.title;
        menu.appendChild(heading);

        for (const [destination, icon] of [['youtube', '▶'], ['dictionary', '字'], ['anki', '+'], ['general', '⚙']]) {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'zerifin-learning-button';
            const symbol = document.createElement('span');
            symbol.className = 'zerifin-learning-icon';
            symbol.setAttribute('aria-hidden', 'true');
            symbol.textContent = icon;
            const text = document.createElement('span');
            text.textContent = labels[destination];
            button.append(symbol, text);
            button.addEventListener('click', event => {
                event.preventDefault();
                event.stopPropagation();
                window.NativeInterface.openLearningSettings(destination);
            });
            menu.appendChild(button);
        }
        host.appendChild(menu);
    }

    function observeDrawer(drawer) {
        if (observedDrawers.has(drawer)) return;
        if (!drawer.matches('.mainDrawer') &&
            !drawer.querySelector('.navMenuOption, a[href="#/home"], a[href="#/home.html"]')) return;
        observedDrawers.add(drawer);
        addMenu(drawer);
        // The server can rebuild the drawer after sign-in, navigation, or a locale change.
        new MutationObserver(() => addMenu(drawer)).observe(drawer, { childList: true, subtree: true });
    }

    function inspect(node) {
        if (!(node instanceof Element)) return;
        const enclosingDrawer = node.closest(drawerSelector);
        if (enclosingDrawer) observeDrawer(enclosingDrawer);
        if (node.matches(drawerSelector)) observeDrawer(node);
        node.querySelectorAll(drawerSelector).forEach(observeDrawer);
        const logoSelector = '.pageTitleWithDefaultLogo, .imgLogoIcon, .splashLogo';
        const logos = [...node.querySelectorAll(logoSelector)];
        if (node.matches(logoSelector)) logos.push(node);
        for (const logo of logos) {
            logo.setAttribute('aria-label', 'Zerifin');
            if (logo.hasAttribute('alt')) logo.setAttribute('alt', 'Zerifin');
        }
    }

    inspect(document.documentElement);
    new MutationObserver(records => {
        for (const record of records) record.addedNodes.forEach(inspect);
    }).observe(document.body, { childList: true, subtree: true });

    function updateTitle() {
        const branded = document.title.replace(/(^|[|–—-]\s*)Jellyfin$/, '$1Zerifin');
        if (document.title !== branded) document.title = branded;
    }
    updateTitle();
    new MutationObserver(updateTitle).observe(document.head, { childList: true, subtree: true, characterData: true });
})();
