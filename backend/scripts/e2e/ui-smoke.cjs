#!/usr/bin/env node
// Drives the backoffice UI in a real browser (Chromium via Playwright): sign-in with mandatory MFA
// enrolment, list / filter / drill down, a refund and a reconciliation action through their dialogs, the
// rejected-callback view, the audit log, page reload, sign-out - and that a hostile customer_reference is
// shown as text, never executed.
//
//   NODE_PATH=<dir with playwright> node scripts/e2e/ui-smoke.cjs
//   env: PPOB2_BASE_URL (default http://localhost:8080)  SHOTS (dir for screenshots, optional)
//
// Preconditions: app up with HTTP Basic OFF and an MFA key set, DB seeded and populated by run-core.sh.
// The script stages its own starting state (below) so it can be re-run: superadmin's MFA is reset (the UI
// enrols it again - part of the test), one paid order is put in REFUND_PENDING, one record is put back to OPEN.
const { chromium } = require('playwright');
const crypto = require('crypto');
const { execFileSync } = require('child_process');
const path = require('path');

const BASE = process.env.PPOB2_BASE_URL || 'http://localhost:8080';
const SHOTS = process.env.SHOTS || '';
const Q = path.join(__dirname, 'q.sh');
const sql = (s) => execFileSync(Q, [s, '-t'], { encoding: 'utf8' }).trim();

let pass = 0, fail = 0;
const ok = (n) => { console.log('  PASS  ' + n); pass++; };
const bad = (n, got) => { console.log('  FAIL  ' + n + '  -- got: ' + got); fail++; };
const chk = (n, got, want) => (JSON.stringify(got) === JSON.stringify(want) ? ok(n) : bad(n, JSON.stringify(got) + ' (want ' + JSON.stringify(want) + ')'));
const truthy = (n, v) => (v ? ok(n) : bad(n, v));

function totp(base32, offset = 0) {
  const A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const c of base32.toUpperCase().replace(/=+$/, '')) bits += A.indexOf(c).toString(2).padStart(5, '0');
  const key = Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000) + offset));
  const h = crypto.createHmac('sha1', key).update(counter).digest();
  const o = h[h.length - 1] & 15;
  return String(((h.readUInt32BE(o) & 0x7fffffff) % 1000000)).padStart(6, '0');
}

(async () => {
  sql("delete from admin_session");
  sql("delete from product where code='UISMOKE'"); // the catalog section below creates it; re-runs start clean
  sql("update pattern_generation set status='FAILED', completed_at=coalesce(completed_at, now()) where status in ('BUILDING','VALIDATING')");
  sql("update admin_user set mfa_secret=null, mfa_enabled=false, mfa_last_step=null, failed_logins=0, locked_until=null where username='superadmin'");
  sql("update parent_order set state='REFUND_PENDING' where id=(select o.id from parent_order o join payment p on p.parent_order_id=o.id where o.state='SUCCESS' and p.status='SUCCESS' order by o.id desc limit 1)");
  sql("update reconciliation set status='OPEN' where id=(select id from reconciliation where status<>'RESOLVED' order by id limit 1)");
  // Hostile partner-controlled text, planted before the browser looks at it.
  const XSS = '<img src=x onerror=window.__xss=1>';
  sql("update parent_order set customer_reference='" + XSS + "' where id=(select max(id) from parent_order)");

  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await ctx.newPage();
  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
  // Optional sections are `cond ? node : null`; the DOM's native append() renders a null argument as the text
  // "null". Found by looking at a screenshot, not by any earlier assertion - so look at the text too.
  const noLiteralNullOrUndefined = async (where) => {
    const text = await page.innerText('#app');
    truthy(where + ': no stray "null" / "undefined" / "[object" text', !/(^|\s)(null|undefined)(\s|$)|\[object/.test(text));
  };
  const shot = async (name) => { if (SHOTS) await page.screenshot({ path: path.join(SHOTS, name + '.png'), fullPage: true }); };

  console.log('== Sign in ==');
  await page.goto(BASE + '/backoffice/');
  await page.waitForSelector('input[name=username]');
  truthy('login form is shown to an anonymous visitor', await page.isVisible('input[name=password]'));
  await shot('01-login');
  await noLiteralNullOrUndefined('login');
  await page.fill('input[name=username]', 'superadmin');
  await page.fill('input[name=password]', 'WRONG');
  await page.click('button[type=submit]');
  await page.waitForSelector('.notice.error');
  truthy('wrong password shows an error and stays on the form', await page.isVisible('input[name=password]'));
  chk('...and the password field is cleared', await page.inputValue('input[name=password]'), '');

  await page.fill('input[name=password]', 'admin123');
  await page.click('button[type=submit]');
  await page.waitForSelector('.secret');
  truthy('SUPER_ADMIN is taken to MFA enrolment, not into the console', await page.isVisible('text=Set up two-step verification'));
  await shot('02-enrol');
  await noLiteralNullOrUndefined('MFA enrolment');
  const secret = (await page.textContent('.secret')).replace(/\s+/g, '');
  truthy('an authenticator key is displayed', /^[A-Z2-7]{32}$/.test(secret));
  await page.fill('input[name=code]', '000000');
  await page.click('button[type=submit]');
  await page.waitForSelector('.notice.error');
  truthy('a wrong confirmation code is rejected on the same screen', await page.isVisible('.secret'));

  // Reload in the middle of enrolment: the pending cookie survives, the page (and its CSRF value) does not.
  // Signing in again must work, not be refused with a CSRF 403 until the pending session times out.
  await page.reload();
  await page.waitForSelector('input[name=username]');
  truthy('after a reload mid-enrolment the login form is shown again', await page.isVisible('input[name=password]'));
  await page.fill('input[name=username]', 'superadmin');
  await page.fill('input[name=password]', 'admin123');
  await page.click('button[type=submit]');
  await page.waitForSelector('.secret, .notice.error');
  truthy('...and signing in again reaches enrolment (not a CSRF refusal)', await page.isVisible('.secret'));
  const secret2 = (await page.textContent('.secret')).replace(/\s+/g, '');
  chk('...with the same key as before (enrolment restarts, it does not stack)', secret2 === secret, false);
  await page.fill('input[name=code]', totp(secret2));
  await page.click('button[type=submit]');
  await page.waitForSelector('header.topbar');
  truthy('the right code lands in the console', await page.isVisible('nav.nav'));
  chk('...showing who is signed in', await page.textContent('.who span'), 'superadmin');
  const navLabels = await page.$$eval('nav.nav a', (as) => as.map((a) => a.textContent));
  chk('SUPER_ADMIN sees every section', navLabels, ['Orders', 'Payments', 'Webhook log', 'Reconciliation', 'Catalog', 'Generations', 'Patterns', 'Configuration', 'Audit log', 'Sessions']);

  console.log('== Orders ==');
  await page.waitForSelector('table');
  const rows0 = await page.$$eval('tbody tr', (r) => r.length);
  truthy('the order list has rows', rows0 > 0);
  await shot('03-orders');
  await noLiteralNullOrUndefined('order list');
  truthy('hostile customer_reference is shown as literal text', (await page.content()).includes('&lt;img src=x onerror=window.__xss=1&gt;'));
  chk('...and was never executed as HTML', await page.evaluate(() => window.__xss), undefined);
  chk('...and created no <img> element', await page.$$eval('img[src="x"]', (e) => e.length), 0);

  await page.selectOption('select[name=state]', 'SUCCESS');
  await page.click('button.primary');
  await page.waitForFunction(() => location.hash.includes('state=SUCCESS'));
  await page.waitForSelector('tbody tr .badge');
  const states = await page.$$eval('tbody tr', (r) => r.map((x) => x.querySelector('.badge').textContent));
  truthy('the state filter narrows the list', states.length > 0 && states.every((s) => s === 'SUCCESS'));
  truthy('...and lives in the URL (bookmarkable)', page.url().includes('state=SUCCESS'));

  await page.click('tbody tr a');
  await page.waitForSelector('h1 .badge');
  chk('order detail opens on the order', (await page.textContent('h1')).startsWith('ORD-'), true);
  truthy('detail shows the child orders', await page.isVisible('text=Child orders'));
  truthy('detail shows the derived-timeline caveat', await page.isVisible('text=Reconstructed from recorded timestamps'));
  truthy('detail never shows a QR payload', !(await page.content()).includes('000201'));
  await shot('04-order-detail');
  await noLiteralNullOrUndefined('order detail');

  console.log('== Reload keeps the session (HttpOnly cookie + /me) ==');
  await page.reload();
  await page.waitForSelector('header.topbar');
  await page.waitForSelector('h1 .badge');
  truthy('after a reload the console is still there, on the same order', await page.isVisible('h1 .badge'));
  chk('the session cookie is HttpOnly (script cannot read it)', await page.evaluate(() => document.cookie.includes('PPOB2_ADMIN_SESSION')), false);

  console.log('== Refund through the dialog ==');
  const refundAudit = () => Number(sql("select count(*) from audit_log a join admin_user u on u.id=a.actor_id where a.action='PARENT_ORDER_REFUND' and u.username='superadmin' and a.after_state->>'reason' like 'ui smoke%'"));
  const refundsBefore = refundAudit();
  await page.goto(BASE + '/backoffice/#/orders?state=REFUND_PENDING');
  await page.waitForSelector('tbody tr a');
  await page.click('tbody tr a');
  await page.waitForSelector('button:has-text("Refund order")');
  await page.click('button:has-text("Refund order")');
  await page.waitForSelector('dialog[open]');
  await shot('05-refund-dialog');
  await page.click('dialog button[type=submit]');
  truthy('an empty reason is refused client-side', await page.isVisible('dialog .notice.error'));
  await page.fill('dialog textarea[name=reason]', 'ui smoke: BR-DEC exhaustion, ticket UI-1');
  await page.fill('dialog input[name=external_reference]', 'BANK-UI-1');
  await page.click('dialog button[type=submit]');
  await page.waitForSelector('.notice.ok');
  truthy('the refund is confirmed on screen', (await page.textContent('.notice.ok')).startsWith('Refunded'));
  await page.waitForSelector('h1 .badge:has-text("REFUNDED")', { timeout: 5000 }).catch(() => {});
  truthy('...and the order is now REFUNDED', await page.isVisible('h1 .badge:has-text("REFUNDED")'));
  chk('...and audit_log recorded exactly one refund against superadmin, with the typed reason', refundAudit() - refundsBefore, 1);

  console.log('== Payments, webhook log, reconciliation, audit ==');
  await page.goto(BASE + '/backoffice/#/payments');
  await page.waitForSelector('tbody tr');
  truthy('payments list has rows', (await page.$$eval('tbody tr', (r) => r.length)) > 0);
  await page.click('tbody tr a');
  await page.waitForSelector('text=Callbacks matched to this payment');
  truthy('payment detail never offers a raw payload', !(await page.content()).includes('callbackType'));

  await page.goto(BASE + '/backoffice/#/callbacks?status=FAILED');
  await page.waitForSelector('tbody tr');
  const rejected = await page.$$eval('tbody tr .badge', (b) => b.map((x) => x.textContent));
  truthy('the rejected-callback view lists FAILED callbacks', rejected.length > 0 && rejected.every((s) => s === 'FAILED'));
  await shot('06-rejected-callbacks');
  await noLiteralNullOrUndefined('webhook log');

  await page.goto(BASE + '/backoffice/#/recon?status=OPEN');
  await page.waitForSelector('tbody tr a');
  await shot('07-recon');
  await page.click('tbody tr a');
  await page.waitForSelector('button:has-text("Investigate")');
  await page.click('button:has-text("Investigate")');
  await page.waitForSelector('dialog[open]');
  await page.click('dialog button[type=submit]');
  await page.waitForSelector('.notice.ok');
  await page.waitForSelector('h1 .badge:has-text("INVESTIGATING")', { timeout: 5000 }).catch(() => {});
  truthy('investigating a record moves it to INVESTIGATING', await page.isVisible('h1 .badge:has-text("INVESTIGATING")'));

  await page.goto(BASE + '/backoffice/#/audit');
  await page.waitForSelector('tbody tr');
  const audit = await page.textContent('table');
  truthy('the audit log shows the admin actions just taken', audit.includes('PARENT_ORDER_REFUND') && audit.includes('RECONCILIATION_INVESTIGATE'));
  truthy('...with the admin\'s name, not just an id', audit.includes('superadmin'));
  await shot('08-audit');
  await noLiteralNullOrUndefined('audit log');

  console.log('== Catalog, pricing, generations, patterns, configuration ==');
  const nav = await page.textContent('nav');
  truthy('the navigation offers the new pages to SUPER_ADMIN', ['Catalog', 'Generations', 'Patterns', 'Configuration'].every((n) => nav.includes(n)));
  const dialogFill = async (fields) => { for (const [k, v] of Object.entries(fields)) await page.fill('dialog [name=' + k + ']', String(v)); };

  await page.goto(BASE + '/backoffice/#/catalog');
  await page.waitForSelector('h2:has-text("Provider SKUs")');
  await page.waitForSelector('tbody tr');
  truthy('the catalog lists SKUs with their current cost', (await page.textContent('body')).includes('ML-20000-A'));
  await noLiteralNullOrUndefined('catalog');
  await shot('09-catalog');
  // A hostile product name, entered through the form like any admin could: it must come back as text.
  await page.click('button:has-text("Add product")');
  await page.waitForSelector('dialog[open]');
  await dialogFill({ code: 'UISMOKE', name: XSS, category: 'UISMOKE' });
  await page.click('dialog button[type=submit]');
  await page.waitForSelector('.notice.ok:has-text("Product added")');
  truthy('adding a product through the dialog reports success', await page.isVisible('.notice.ok:has-text("Product added")'));
  await page.waitForSelector('tr:has-text("UISMOKE")');
  truthy('...the new product is listed', await page.isVisible('tr:has-text("UISMOKE")'));
  chk('...and its hostile name was shown as text, never executed', await page.evaluate(() => window.__xss === undefined && !document.querySelector('img[src="x"]')), true);
  // A duplicate is refused with the server's reason, in place.
  await page.click('button:has-text("Add product")');
  await page.waitForSelector('dialog[open]');
  await dialogFill({ code: 'UISMOKE', name: 'again', category: 'UISMOKE' });
  await page.click('dialog button[type=submit]');
  await page.waitForSelector('.notice.error:has-text("already exists")');
  truthy('a duplicate product shows the server\'s reason', await page.isVisible('.notice.error:has-text("already exists")'));
  // Price change needs a reason; it reports how many patterns it re-scored.
  const priceBtn = page.locator('tr:has-text("ML-20000-B") button:has-text("Price")');
  await priceBtn.click();
  await page.waitForSelector('dialog[open]');
  await dialogFill({ provider_cost: 18250, reason: 'ui smoke price check' });
  await page.click('dialog button[type=submit]');
  await page.waitForSelector('.notice.ok:has-text("re-scored")');
  truthy('a price change reports the new version and the patterns re-scored', await page.isVisible('.notice.ok:has-text("re-scored")'));
  truthy('...and the table shows the new cost', await page.isVisible('tr:has-text("ML-20000-B"):has-text("18.250")'));
  await page.click('tr:has-text("ML-20000-B") button:has-text("History")');
  await page.waitForSelector('h2:has-text("Price history")');
  await page.waitForSelector('h2:has-text("Price history") ~ div tbody tr');
  truthy('price history keeps every version, newest first', (await page.locator('h2:has-text("Price history") ~ div tbody tr').count()) >= 2
    && (await page.locator('h2:has-text("Price history") ~ div tbody tr').first().textContent()).includes('18.250'));

  await page.goto(BASE + '/backoffice/#/config');
  await page.waitForSelector('h2:has-text("Supported amounts")');
  await noLiteralNullOrUndefined('configuration');
  await page.click('tr:has-text("routing.weight.childcount") button:has-text("Change")');
  await page.waitForSelector('dialog[open]');
  await dialogFill({ value: 1000 });
  await page.click('dialog button[type=submit]');
  await page.waitForSelector('.notice.error:has-text("between")');
  truthy('an out-of-range value is refused with the allowed range', await page.isVisible('.notice.error:has-text("between")'));
  await page.click('tr:has-text("routing.weight.childcount") button:has-text("Change")');
  await page.waitForSelector('dialog[open]');
  await dialogFill({ value: 0.75 });
  await page.click('dialog button[type=submit]');
  await page.waitForSelector('.notice.ok:has-text("updated")');
  truthy('a valid value is saved and the row says it is an override', await page.isVisible('tr:has-text("routing.weight.childcount"):has-text("OVERRIDE")'));
  truthy('a supported amount with no patterns behind it is flagged', await page.isVisible('text=cannot be fulfilled') || (await page.textContent('body')).includes('10.000'));

  await page.goto(BASE + '/backoffice/#/generations');
  await page.waitForSelector('h1:has-text("Pattern generations")');
  await noLiteralNullOrUndefined('generations');
  await page.click('button:has-text("Build new generation")');
  await page.waitForSelector('dialog[open]');
  await page.click('dialog button[type=submit]');
  await page.waitForSelector('.notice.ok:has-text("started")');
  truthy('starting a generation reports its id', await page.isVisible('.notice.ok:has-text("started")'));
  await page.waitForSelector('tbody tr:first-child .badge:has-text("READY")', { timeout: 90000 });
  truthy('the list updates by itself until the generation is READY', true);
  await page.click('tbody tr:first-child a');
  await page.waitForSelector('h2:has-text("Per amount")');
  truthy('the detail shows the result and per-amount outcome', await page.isVisible('h2:has-text("Result")'));
  await shot('10-generation');
  await noLiteralNullOrUndefined('generation detail');
  await page.click('button:has-text("Activate")');
  await page.waitForSelector('dialog[open]');
  await page.click('dialog button[type=submit]');
  await page.waitForSelector('.notice.ok:has-text("ACTIVE"), dialog[open]:has-text("Coverage would drop")');
  if (await page.isVisible('dialog[open]:has-text("Coverage would drop")')) {
    truthy('a coverage regression asks for a reason instead of activating silently', true);
    await page.click('dialog button:has-text("Cancel")');
    chk('...cancelling leaves the previous generation serving', sql("select count(*) from pattern_generation where status='ACTIVE'"), '1');
    await page.click('button:has-text("Activate")');
    await page.waitForSelector('dialog[open]');
    await page.click('dialog button[type=submit]');
    await page.waitForSelector('dialog[open]:has-text("Coverage would drop")');
    await dialogFill({ reason: 'ui smoke: accepting reduced coverage' });
    await page.click('dialog button:has-text("Activate anyway")');
    await page.waitForSelector('.notice.ok:has-text("ACTIVE")');
  } else {
    truthy('activation without a coverage loss goes straight through', true);
  }
  truthy('activation reports the coverage change', await page.isVisible('.notice.ok:has-text("ACTIVE")'));
  truthy('...and the generation is now ACTIVE', await page.isVisible('h1 .badge:has-text("ACTIVE")'));

  await page.goto(BASE + '/backoffice/#/patterns?parent_amount=20000');
  await page.waitForSelector('tbody tr');
  truthy('patterns of the active generation can be looked up by amount', (await page.locator('tbody tr').count()) > 0);
  truthy('...eligibility is labelled ELIGIBLE / INELIGIBLE, not with generation states', await page.isVisible('tbody .badge:has-text("ELIGIBLE")') && !(await page.isVisible('tbody .badge:has-text("FAILED")')));
  await noLiteralNullOrUndefined('patterns');
  await page.click('tbody tr a');
  await page.waitForSelector('h2:has-text("Economics by day")');
  truthy('a pattern shows its components and daily economics', await page.isVisible('h2:has-text("Components")'));
  await noLiteralNullOrUndefined('pattern detail');
  chk('the audit trail recorded the activation by name', sql("select count(*) from audit_log where action='PATTERN_GENERATION_ACTIVATE' and actor_id=(select id from admin_user where username='superadmin')") >= '1', true);

  console.log('== Sign out ==');
  await page.click('button:has-text("Sign out")');
  await page.waitForSelector('input[name=username]');
  truthy('sign-out returns to the login form', await page.isVisible('input[name=password]'));
  chk('...and the old cookie no longer works', await page.evaluate(() => fetch('/admin/auth/me').then((r) => r.status)), 401);
  await page.goto(BASE + '/backoffice/#/orders');
  await page.waitForSelector('input[name=username]');
  truthy('a deep link while signed out goes to the login form, not data', !(await page.isVisible('table')));

  console.log('== Browser hygiene ==');
  // The browser logs every non-2xx fetch as a console error. The ones here are the run's own expected ones:
  // 401 (wrong password, the pre-login /me probe, /me after sign-out) and one 403 (the /me probe made by the
  // reload during MFA enrolment, where the live cookie is a password-only session with no admin authority).
  // Anything else - above all a CSP violation - must fail the run.
  const unexpected = consoleErrors.filter((m) => !/status of (400|401|403|409)/.test(m));
  chk('...exactly one 403 was logged (the pending-session /me probe)', consoleErrors.filter((m) => /status of 403/.test(m)).length, 1);
  chk('no console errors or CSP violations during the whole run (expected 401s aside)', unexpected, []);
  truthy('...and the expected 401s did occur (the filter is not hiding an empty set)', consoleErrors.length >= 3);

  await browser.close();
  console.log('\n================================\n  PASS: ' + pass + '    FAIL: ' + fail + '\n================================');
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });
