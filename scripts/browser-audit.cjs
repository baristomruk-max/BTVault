/**
 * BTVault canli tarayici denetimi.
 * Her eklentinin mainUrl'sini gercek Chrome ile acar; durum, son URL, baslik,
 * Cloudflare/bot kontrolu ve kaydedilen HTML'i raporlar.
 *
 * Kullanim:
 *   NODE_PATH=<playwright node_modules> node scripts/browser-audit.cjs [cikti-dizini]
 */
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const ROOT = path.resolve(__dirname, '..');
const OUT = path.resolve(process.argv[2] || path.join(__dirname, 'audit-run'));
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

function providers() {
  const rows = [];
  for (const dir of fs.readdirSync(ROOT, { withFileTypes: true })) {
    if (!dir.isDirectory() || dir.name.startsWith('.') || dir.name === 'build') continue;
    const gradle = path.join(ROOT, dir.name, 'build.gradle.kts');
    if (!fs.existsSync(gradle)) continue;
    // mainUrl once <Provider>.kt (asil eklenti dosyasi) tercih edilir,
    // boylece extractor dosyalarindaki mainUrl degerleri yaniltmaz.
    const files = [];
    const walk = (d) => {
      for (const e of fs.readdirSync(d, { withFileTypes: true })) {
        const p = path.join(d, e.name);
        if (e.isDirectory()) walk(p);
        else if (e.name.endsWith('.kt')) files.push(p);
      }
    };
    walk(path.join(ROOT, dir.name, 'src'));
    const ordered = files
      .filter((f) => path.basename(f) === dir.name + '.kt')
      .concat(files.filter((f) => path.basename(f) !== dir.name + '.kt'));
    let url = '';
    for (const f of ordered) {
      const m = fs.readFileSync(f, 'utf8').match(/override\s+var\s+mainUrl\s*=\s*"([^"]+)"/);
      if (m) {
        url = m[1];
        break;
      }
    }
    const statusM = fs.readFileSync(gradle, 'utf8').match(/status\s*=\s*(\d)/);
    rows.push({ provider: dir.name, mainUrl: url, status: statusM ? +statusM[1] : null });
  }
  return rows;
}

function cfReason(title, body) {
  const t = (title || '') + ' ' + (body || '').slice(0, 20000);
  if (/Just a moment|Attention Required|Checking your browser|cf-browser-verification|__cf_chl_/i.test(t))
    return 'CF-CHALLENGE';
  if (/Making sure you're not a bot|Access denied|Forbidden|403 Forbidden/i.test(t)) return 'BOT-WALL';
  if (/Domain (is )?for sale|domain is parked|Buy this domain|sat[ıi]l[ıi]kt[ıi]r/i.test(t)) return 'PARKED';
  return '';
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const list = providers();
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const results = [];
  let idx = 0;

  async function check(entry) {
    const ctx = await browser.newContext({
      userAgent: UA,
      locale: 'tr-TR',
      viewport: { width: 1366, height: 900 },
      ignoreHTTPSErrors: true,
    });
    const page = await ctx.newPage();
    const row = { ...entry, http: '', finalUrl: '', title: '', htmlLen: 0, note: '', error: '' };
    try {
      if (!entry.mainUrl) throw new Error('mainUrl yok');
      const resp = await page.goto(entry.mainUrl, { waitUntil: 'domcontentloaded', timeout: 40000 });
      row.http = resp ? resp.status() : '';
      await page.waitForTimeout(3500);
      row.finalUrl = page.url();
      row.title = (await page.title()).trim();
      let html = '';
      try {
        html = await page.content();
      } catch {}
      row.htmlLen = html.length;
      row.note = cfReason(row.title, html);
      if (!row.note && row.htmlLen < 1200 && row.http === 200) row.note = 'EMPTY';
      fs.writeFileSync(path.join(OUT, entry.provider + '.html'), html);
      const orig = (() => { try { return new URL(entry.mainUrl).host; } catch { return ''; } })();
      const fin = (() => { try { return new URL(row.finalUrl).host; } catch { return ''; } })();
      if (orig && fin && orig !== fin) row.note = (row.note ? row.note + ';' : '') + 'REDIRECT:' + fin;
    } catch (e) {
      row.error = String(e.message || e).split('\n')[0].slice(0, 160);
      row.note = 'ERROR';
    }
    await ctx.close().catch(() => {});
    idx++;
    console.log(
      `[${idx}/${list.length}] ${entry.provider} ${row.http} ${row.note} ${row.title.slice(0, 60)}`
    );
    return row;
  }

  const queue = list.slice();
  const workers = Array.from({ length: 4 }, async () => {
    while (queue.length) {
      const e = queue.shift();
      results.push(await check(e));
    }
  });
  await Promise.all(workers);
  await browser.close();

  results.sort((a, b) => a.provider.localeCompare(b.provider));
  const header = ['Provider', 'Status', 'Http', 'MainUrl', 'FinalUrl', 'Title', 'HtmlLen', 'Note', 'Error'];
  const csv = [header.join(',')]
    .concat(
      results.map((r) =>
        [r.provider, r.status, r.http, r.mainUrl, r.finalUrl, r.title, r.htmlLen, r.note, r.error]
          .map((v) => '"' + String(v ?? '').replace(/"/g, '""') + '"')
          .join(',')
      )
    )
    .join('\r\n');
  fs.writeFileSync(path.join(OUT, 'results.csv'), '\ufeff' + csv, 'utf8');
  fs.writeFileSync(path.join(OUT, 'results.json'), JSON.stringify(results, null, 2), 'utf8');

  const bad = results.filter((r) => r.note || r.error || r.http !== 200);
  console.log('\n=== SORUNLU (' + bad.length + '/' + results.length + ') ===');
  for (const r of bad) console.log(`${r.provider}: ${r.http} ${r.note} ${r.error} ${r.finalUrl}`);
  console.log('\nCSV: ' + path.join(OUT, 'results.csv'));
})().catch((e) => {
  console.error('FATAL', e);
  process.exit(1);
});
