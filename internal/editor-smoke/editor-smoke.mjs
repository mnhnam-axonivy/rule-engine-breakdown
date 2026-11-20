/**
 * Browser check of the DMN editor page of the decision runtime (/editor/).
 *
 *   npm run smoke            starts a throwaway runtime container from axonivy/rule-engine-runtime:dev
 *                            (build it first: docker compose build), protected by an API key, runs the
 *                            checks in headless Chrome/Edge and removes the container again
 *   npm run smoke -- --url=http://localhost:8090/ [--key=<api key>]
 *                            the same against a runtime that runs already. It adds versions to it!
 *
 * The page is used like a person would: API key prompt, open a model, change a rule, save and activate,
 * activate the old version, roll back, a model the runtime rejects.
 * The first change is made in the KIE editor itself, like a person does it: select the decision, "Edit", type
 * into a cell. Later changes use the editor's API (setContent). Every request outside the runtime is blocked
 * and counted as a failure.
 * Screenshots land in .preview/. Set CHROME_PATH if no browser is found.
 */
import puppeteer from 'puppeteer-core';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const shots = resolve(here, '.preview');
// a small model of one table: the fixture of the connector tests, not the demo
const discountFile = resolve(here, '../../rule-engine-test/dmn/discount.dmn');
const IMAGE = process.env.RULE_ENGINE_IMAGE || 'axonivy/rule-engine-runtime:dev';
const arg = name => (process.argv.find(a => a.startsWith('--' + name + '=')) || '').slice(name.length + 3);

const browserPath = process.env.CHROME_PATH || [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser'
].find(existsSync);
if (!browserPath) {
  console.error('No Chrome or Edge found. Set CHROME_PATH to a Chromium-based browser.');
  process.exit(2);
}

rmSync(shots, { recursive: true, force: true });
mkdirSync(shots, { recursive: true });
const failures = [];
let checks = 0;
const check = (ok, what) => {
  checks++;
  console.log((ok ? '  ok    ' : '  FAIL  ') + what);
  if (!ok) failures.push(what);
};
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ── the runtime ──────────────────────────────────────────────────────────────

let base = arg('url');
let key = arg('key');
let container = null;
if (!base) {
  key = 'smoke-test-key';
  container = execFileSync('docker', ['run', '-d', '--rm', '-e', 'RULE_ENGINE_API_KEY=' + key, '-p', '127.0.0.1::8080', IMAGE]).toString().trim();
  const port = execFileSync('docker', ['port', container, '8080/tcp']).toString().trim().split(/\r?\n/)[0].split(':').pop();
  base = 'http://127.0.0.1:' + port + '/';
  console.log('Throwaway runtime ' + container.slice(0, 12) + ' on ' + base);
}
if (!base.endsWith('/')) base += '/';
const origin = new URL(base).origin;

async function api(path, { method = 'GET', body, type } = {}) {
  const headers = {};
  if (key) headers['X-API-Key'] = key;
  if (type) headers['Content-Type'] = type;
  const response = await fetch(new URL('api/' + path, base), { method, headers, body });
  const text = await response.text();
  if (!response.ok) throw new Error(method + ' ' + path + ': ' + response.status + ' ' + text);
  return text ? JSON.parse(text) : null;
}

async function ready() {
  for (let i = 0; i < 90; i++) {
    try {
      if ((await fetch(new URL('q/health/ready', base))).ok) return;
    } catch (e) { /* still starting */ }
    await sleep(1000);
  }
  throw new Error('The runtime at ' + base + ' did not get ready.');
}

const evaluate = async () => (await api('models/discount/evaluate', {
  method: 'POST', type: 'application/json',
  body: JSON.stringify({ decision: 'discount', input: { purchaseAmount: 1500, type: 'Gold', country: 'CH' } })
})).result;

const original = readFileSync(discountFile, 'utf8');
const withDiscount = value => original.replace(/(<outputEntry id="_rule_1_output_discount">\s*<text>)15(<\/text>)/, '$1' + value + '$2');

// ── the browser ──────────────────────────────────────────────────────────────

let browser;
const problems = [];
const external = [];

async function openPage(hash = '') {
  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 1 });
  page.on('pageerror', e => problems.push('pageerror: ' + e.message));
  page.on('console', m => {
    // the 401 before the API key is entered is part of the flow
    if (m.type() === 'error' && !(key && /status of 401/.test(m.text()))) problems.push('console: ' + m.text() + ' ' + (m.location().url || ''));
  });
  page.on('dialog', d => d.accept()); // "discard the unsaved changes?"
  await page.setRequestInterception(true);
  page.on('request', request => {
    const url = request.url();
    if (url.startsWith(origin) || /^(data|blob|about):/.test(url)) request.continue();
    else { external.push(url); request.abort(); }
  });
  await page.goto(base + 'editor/' + hash, { waitUntil: 'load' });
  return page;
}

async function shot(page, name) {
  await page.screenshot({ path: join(shots, name + '.png') });
}

/** Enters the API key when the page asks for it, and waits until the models are listed. */
async function enterKey(page) {
  await page.waitForFunction(() => document.querySelector('#key-dialog').open
    || document.querySelector('#runtime-hint').textContent !== 'Loading the models…', { timeout: 30000, polling: 100 });
  if (await page.$eval('#key-dialog', d => d.open)) {
    check(true, 'the protected runtime asks for its API key');
    await shot(page, '01-api-key');
    await page.type('#key-input', key);
    await page.click('#key-dialog button.primary');
  }
  await page.waitForFunction(() => document.querySelector('#runtime-hint').textContent !== 'Loading the models…', { timeout: 30000, polling: 100 });
}

/** The content of the open editor, or null while the editor is still starting. */
const content = page => page.evaluate(() => {
  const editor = window.ruleEditor && window.ruleEditor.editor;
  if (!editor) return null;
  // while the editor starts, getContent() waits or fails: both mean "not yet"
  return Promise.race([editor.getContent().catch(() => null), new Promise(r => setTimeout(() => r(null), 3000))]);
});

async function waitForContent(page, needle, timeout = 90000) {
  const end = Date.now() + timeout;
  // the page says when the editor has started; asking it earlier only produces errors in its frame
  await page.waitForFunction(() => window.ruleEditor && window.ruleEditor.ready, { timeout, polling: 200 });
  while (Date.now() < end) {
    const text = await content(page);
    if (text && text.includes(needle)) return text;
    await sleep(500);
  }
  return null;
}

/**
 * Changes a cell of the decision table in the KIE editor: select the decision in the diagram, "Edit", click the
 * cell, type, Enter. The selectors are those of the Apache KIE DMN editor 10.2.0.
 */
async function typeIntoTable(page, cellIndex, value) {
  const frame = page.frames().find(f => f.url() === 'about:srcdoc');
  const node = await frame.waitForSelector('.react-flow__node-node_decision', { timeout: 30000 });
  let box = await node.boundingBox();
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  const edit = await frame.waitForFunction(() => [...document.querySelectorAll('*')]
    .find(e => e.children.length === 0 && e.textContent.trim() === 'Edit'), { timeout: 15000 });
  await edit.asElement().click();
  await frame.waitForFunction(i => document.querySelectorAll('td').length > i, { timeout: 15000 }, cellIndex);
  await sleep(500);
  const cell = await frame.evaluateHandle(i => document.querySelectorAll('td')[i], cellIndex);
  box = await cell.asElement().boundingBox();
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  await sleep(300);
  await page.keyboard.type(String(value));
  await sleep(300);
  await page.keyboard.press('Enter');
}

/** Replaces the model in the editor, the way an edit in the table would change it. */
const setModel = (page, xml, path = 'discount.dmn') => page.evaluate((p, x) => window.ruleEditor.editor.setContent(p, x), path, xml);

const message = page => page.$eval('#messages', box => (box.hidden ? '' : box.textContent.replace(/^Close/, '').replace(/\s+/g, ' ').trim()));

/** Clicks a button of the page and waits for the message it shows. */
async function act(page, selector, kind) {
  await page.$eval('#messages', box => { box.hidden = true; });
  await page.click(selector);
  await page.waitForFunction(k => { const box = document.querySelector('#messages'); return !box.hidden && box.classList.contains(k); },
    { timeout: 30000, polling: 100 }, kind);
  return message(page);
}

const versions = async () => (await api('models/discount/versions'))
  .sort((a, b) => a.version - b.version).map(v => v.version + ':' + v.status + ':' + v.source).join(',');

// ── checks ───────────────────────────────────────────────────────────────────

try {
  await ready();
  const project = await api('models/discount/versions?source=PROJECT&author=rule-engine-test', { method: 'POST', type: 'application/xml', body: original });
  console.log('Model discount registered as version ' + project.version + ' (' + project.status + '), as a project would');

  browser = await puppeteer.launch({ executablePath: browserPath, headless: true, args: ['--hide-scrollbars', '--force-color-profile=srgb'] });

  console.log('\nOpen a model of the runtime');
  let page = await openPage('#discount');
  await enterKey(page);
  check(Boolean(await waitForContent(page, '_rule_1_output_discount')), 'the deep link #discount opens the active version in the KIE DMN editor');
  await sleep(2500); // let the editor draw
  const toolbar = await page.$eval('#doc-state', n => n.textContent);
  check(toolbar === 'version 1 · active · from project', 'the toolbar says which version is open: ' + toolbar);
  check(await page.$$eval('#versions tr', rows => rows.length) === 1, 'the versions of the model are listed');
  await shot(page, '02-opened');

  console.log('\nChange a rule and save it');
  check((await act(page, '#save-activate', 'warn')).includes('Enter your name'), 'saving needs the name of the person');
  await page.type('#author', 'rule.owner');
  await page.type('#description', 'Gold discount raised to 20');
  await typeIntoTable(page, 4, 20); // rule 1, column discount
  check(Boolean(await waitForContent(page, '>20</', 20000)), 'a rule changed in the decision table of the KIE editor: 15 → 20');
  check(await page.$eval('#doc-dirty', n => !n.hidden), 'the page shows that there are unsaved changes');
  await shot(page, '03-table-edited');
  let text = await act(page, '#save-activate', 'ok');
  check(text.includes('Saved as version 2') && text.includes('activated') && !text.includes('null'), 'Save and activate: ' + text);
  check(await versions() === '1:RETIRED:PROJECT,2:ACTIVE:PORTAL', 'version 2 comes from a person (portal) and is active: ' + await versions());
  check(await evaluate() === 20, 'the runtime decides with the new rule at once: 20');
  const saved = (await api('models/discount/versions/2'));
  check(saved.author === 'rule.owner' && saved.description === 'Gold discount raised to 20', 'author and description are stored with the version');
  check(await page.$eval('#doc-state', n => n.textContent) === 'version 2 · active · from portal', 'the editor now shows version 2');
  check(await page.evaluate(() => location.hash) === '#discount/2', 'the address points to version 2');
  check(await page.$eval('#doc-dirty', n => n.hidden), 'after saving, nothing is unsaved');
  await shot(page, '03-saved');

  console.log('\nActivate and roll back');
  await act(page, '#versions tr[data-version="1"] button[data-activate]', 'ok');
  check(await evaluate() === 15, 'Activate on version 1: 15 again');
  text = await act(page, '#rollback', 'ok');
  check(text.includes('version 2') && await evaluate() === 20, 'Roll back: version 2 is active again, 20');
  await shot(page, '04-rolled-back');

  console.log('\nThe same content again, and a model the runtime rejects');
  text = await act(page, '#save-draft', 'ok');
  check(text.includes('Nothing new') && await versions() === '1:RETIRED:PROJECT,2:ACTIVE:PORTAL', 'unchanged content creates no version');
  check((await act(page, '#check', 'ok')).includes('accepts this model'), 'Check: the runtime accepts the model');
  await setModel(page, withDiscount('15 +'));
  await waitForContent(page, '15 +', 20000);
  text = await act(page, '#save-activate', 'error');
  check(text.includes('does not accept') && await versions() === '1:RETIRED:PROJECT,2:ACTIVE:PORTAL', 'an invalid rule is not saved, the findings are shown');
  await shot(page, '05-rejected');
  await page.close();

  check(problems.length === 0, 'no errors in the browser' + (problems.length ? ': ' + problems.slice(0, 3).join(' | ') : ''));
  check(external.length === 0, 'nothing is loaded from outside the runtime' + (external.length ? ': ' + external.slice(0, 3).join(', ') : ''));
} catch (e) {
  failures.push(String(e && e.stack || e));
  console.error(e);
} finally {
  if (browser) await browser.close();
  if (container) execFileSync('docker', ['stop', container]);
}

console.log('\n' + (checks - failures.length) + ' of ' + checks + ' checks passed. Screenshots: ' + shots);
if (failures.length) {
  console.log('Failed:\n' + failures.map(f => '  - ' + f).join('\n'));
  process.exit(1);
}
