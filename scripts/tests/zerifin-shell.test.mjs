// Run with: node scripts/tests/zerifin-shell.test.mjs /path/to/linkedom/worker.js
// Fixture dependency: https://unpkg.com/linkedom@0.18.12/worker.js
// SHA-256: 196efeb17c260e001979dbc54a3c30e701a881c6e8a3eedaddc5ad83c99ee5ff
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { runInNewContext } from 'node:vm';
import { test } from 'node:test';

const { parseHTML } = await import(pathToFileURL(process.argv[2]).href);
const source = await readFile(new URL('../../app/src/main/assets/native/zerifin-shell.js', import.meta.url), 'utf8');
const settle = () => new Promise(resolve => setTimeout(resolve, 0));

function fixture(body) {
    const { window } = parseHTML(`<html><head><title>Home — Jellyfin</title></head><body>${body}</body></html>`);
    window.zerifinShellReady = false;
    const calls = [];
    window.NativeInterface = {
        getLearningMenuLabels: () => JSON.stringify({ title: 'Japanese learning', general: 'General', dictionary: 'Dictionaries', anki: 'Anki mining', youtube: 'YouTube' }),
        openLearningSettings: destination => calls.push(destination),
    };
    const context = { window, document: window.document, Element: window.Element, MutationObserver: window.MutationObserver };
    runInNewContext(source, context);
    return { window, document: window.document, calls, context };
}

test('side menu routes dictionary and mining directly to native settings without server navigation', async () => {
    const { window, document, calls, context } = fixture('<div class="mainDrawer"><div class="scrollContainer"></div></div>');
    let serverClicks = 0;
    document.body.addEventListener('click', () => serverClicks++);
    runInNewContext(source, context);
    const buttons = document.querySelectorAll('.zerifin-learning-button');
    assert.equal(buttons.length, 4);
    for (const button of buttons) button.dispatchEvent(new window.Event('click', { bubbles: true, cancelable: true }));
    assert.deepEqual(calls, ['youtube', 'dictionary', 'anki', 'general']);
    assert.equal(serverClicks, 0);
    assert.equal(document.querySelectorAll('link[href="/native/zerifin-shell.css"]').length, 1);
    await settle();
});

test('menu survives late sign-in, drawer content refresh, and full drawer replacement once each', async () => {
    const { document } = fixture('');
    document.body.innerHTML = '<div class="mainDrawer"><div class="mainDrawer-scrollContainer"></div></div>';
    await settle();
    assert.equal(document.querySelectorAll('.zerifin-learning-menu').length, 1);
    document.querySelector('.mainDrawer-scrollContainer').innerHTML = '<a href="#/home">Home</a>';
    await settle();
    assert.equal(document.querySelectorAll('.zerifin-learning-menu').length, 1);
    document.body.innerHTML = '<div class="mainDrawer"><div class="scrollContainer"></div></div>';
    await settle();
    assert.equal(document.querySelectorAll('.zerifin-learning-menu').length, 1);
});

test('only navigation drawers receive the learning menu', async () => {
    const { document } = fixture('<aside class="MuiDrawer-paper"><button>Filter</button></aside>');
    assert.equal(document.querySelectorAll('.zerifin-learning-menu').length, 0);
    document.body.insertAdjacentHTML('beforeend', '<aside class="MuiDrawer-paper"><ul class="MuiList-root"><li><a href="#/home">Home</a></li></ul></aside>');
    await settle();
    assert.equal(document.querySelectorAll('.zerifin-learning-menu').length, 1);
});

test('page titles and logo accessibility follow navigation without altering media titles in the page', async () => {
    const { document } = fixture('<h1>Jellyfin documentary</h1><img class="imgLogoIcon" alt="Jellyfin">');
    assert.equal(document.title, 'Home — Zerifin');
    assert.equal(document.querySelector('img').getAttribute('alt'), 'Zerifin');
    assert.equal(document.querySelector('h1').textContent, 'Jellyfin documentary');
    document.title = 'Settings — Jellyfin';
    await settle();
    assert.equal(document.title, 'Settings — Zerifin');
    document.title = 'Jellyfin documentary — Jellyfin';
    await settle();
    assert.equal(document.title, 'Jellyfin documentary — Zerifin');
});
