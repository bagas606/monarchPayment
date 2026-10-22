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
  await page.fill('input[name=code]', totp(secret));
  await page.click('button[type=submit]');
  await page.waitForSelector('header.topbar');
  truthy('the right code lands in the console', await page.isVisible('nav.nav'));
  chk('...showing who is signed in', await page.textContent('.who span'), 'superadmin');
  const navLabels = await page.$$eval('nav.nav a', (as) => as.map((a) => a.textContent));
  chk('SUPER_ADMIN sees every section', navLabels, ['Orders', 'Payments', 'Webhook log', 'Reconciliation', 'Audit log', 'Sessions']);

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

  console.log('== Sign out ==');
  await page.click('button:has-text("Sign out")');
  await page.waitForSelector('input[name=username]');
  truthy('sign-out returns to the login form', await page.isVisible('input[name=password]'));
  chk('...and the old cookie no longer works', await page.evaluate(() => fetch('/admin/auth/me').then((r) => r.status)), 401);
  await page.goto(BASE + '/backoffice/#/orders');
  await page.waitForSelector('input[name=username]');
  truthy('a deep link while signed out goes to the login form, not data', !(await page.isVisible('table')));

  console.log('== Browser hygiene ==');
  // The browser logs every non-2xx fetch as a console error. The 401s here are the run's own expected ones
  // (wrong password, the pre-login /me probe, /me after sign-out); anything else - above all a CSP
  // violation - must fail the run.
  const unexpected = consoleErrors.filter((m) => !/status of 401/.test(m));
  chk('no console errors or CSP violations during the whole run (expected 401s aside)', unexpected, []);
  truthy('...and the expected 401s did occur (the filter is not hiding an empty set)', consoleErrors.length >= 3);

  await browser.close();
  console.log('\n================================\n  PASS: ' + pass + '    FAIL: ' + fail + '\n================================');
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });
