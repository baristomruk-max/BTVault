/**
 * BTVault oynatma denetimi.
 * Siteyi acar, bir icerik sayfasina girer, oynat butonuna basar ve
 * yakalanan medya/iframe isteklerini raporlar.
 *
 * Kullanim:
 *   NODE_PATH=<playwright node_modules> node scripts/play-audit.cjs [sadece-provider] [cikti-dizini]
 */
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const ROOT = path.resolve(__dirname, '..');
const ONLY = process.argv[2] && !process.argv[2].includes(path.sep) ? process.argv[2] : '';
const OUT = path.resolve(process.argv[3] || process.argv[2] || path.join(__dirname, 'play-audit'));
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

function providers() {
  const rows = [];
  for (const dir of fs.readdirSync(ROOT, { withFileTypes: true })) {
    if (!dir.isDirectory() || dir.name.startsWith('.') || dir.name === 'build') continue;
    if (!fs.existsSync(path.join(ROOT, dir.name, 'build.gradle.kts'))) continue;
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
    rows.push({ provider: dir.name, mainUrl: url });
  }
  return rows.filter((r) => r.mainUrl && (!ONLY || r.provider === ONLY));
}

const BLOCK = /erisime_engellenmis|ihbarweb\.org\.tr/i;

function scoreLink(href, text) {
  const h = href.toLowerCase();
  if (!/^https?:/.test(h)) return -1;
  if (/\.(jpg|jpeg|png|gif|svg|webp|css|js|ico|woff2?)(\?|$)/.test(h)) return -1;
  if (/(kategori|category|etiket|tags?\/|sayfa\/|page\/|arama|search|giris|login|uye|register|iletisim|contact|hakkimizda|about|dmca|gizlilik|privacy)/.test(h))
    return -1;
  let s = 0;
  if (/\/(film|filmler|movie|dizi|diziler|series|watch|video|izle|bolum|episode|anime)\//.test(h)) s += 3;
  if (/-(izle|film-izle|full-izle)/.test(h)) s += 2;
  if (text && text.trim().length > 2) s += 1;
  if (h.split('/').length <= 4) s -= 1;
  return s;
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const list = providers();
  const browser = await chromium.launch({ headless: true, channel: 'chrome', args: ['--mute-audio'] });
  const results = [];
  let idx = 0;

  async function audit(entry) {
    const ctx = await browser.newContext({
      userAgent: UA,
      locale: 'tr-TR',
      viewport: { width: 1366, height: 900 },
      ignoreHTTPSErrors: true,
    });
    const row = {
      provider: entry.provider,
      mainUrl: entry.mainUrl,
      homeStatus: '',
      blocked: false,
      contentUrl: '',
      candidates: [],
      iframes: [],
      media: [],
      playerHints: [],
      note: '',
    };
    const media = new Set();
    const watch = (page) => {
      page.on('request', (req) => {
        const u = req.url();
        if (/\.(m3u8|mp4|m4v|webm|txt)(\?|$)/i.test(u) || /\/hls\//i.test(u)) media.add(req.resourceType() + ' ' + u);
      });
    };
    try {
      const page = await ctx.newPage();
      watch(page);
      const resp = await page.goto(entry.mainUrl, { waitUntil: 'domcontentloaded', timeout: 40000 });
      row.homeStatus = resp ? resp.status() : '';
      await page.waitForTimeout(2500);
      const html = await page.content();
      if (BLOCK.test(html)) {
        row.blocked = true;
        row.note = 'ISP-BLOCK';
        await ctx.close();
        return row;
      }
      const links = await page.evaluate(() => {
        return [...document.querySelectorAll('a[href]')].map((a) => ({
          href: a.href,
          text: (a.textContent || '').trim().slice(0, 80),
        }));
      });
      const scored = links
        .map((l) => ({ ...l, score: scoreLink(l.href, l.text) }))
        .filter((l) => l.score > 0)
        .sort((a, b) => b.score - a.score);
      const seen = new Set();
      row.candidates = scored
        .filter((l) => {
          const k = l.href.replace(/\/$/, '');
          if (seen.has(k)) return false;
          seen.add(k);
          return true;
        })
        .slice(0, 4)
        .map((l) => l.href);

      for (const url of row.candidates.slice(0, 3)) {
        const cp = await ctx.newPage();
        watch(cp);
        try {
          const cr = await cp.goto(url, { waitUntil: 'domcontentloaded', timeout: 35000 });
          await cp.waitForTimeout(3000);
          const ch = await cp.content();
          if (BLOCK.test(ch)) continue;
          const info = await cp.evaluate(() => {
            const ifr = [...document.querySelectorAll('iframe')].map((f) => ({
              src: f.src || f.getAttribute('data-src') || '',
              id: f.id || '',
              cls: f.className || '',
            }));
            const hints = [];
            document.querySelectorAll('[class*=play],[id*=play],[data-video],[data-src],[data-url],[data-file]').forEach((el) => {
              hints.push({
                tag: el.tagName,
                cls: (el.className || '').toString().slice(0, 70),
                id: el.id || '',
                attrs: ['data-video', 'data-src', 'data-url', 'data-file', 'data-video_url']
                  .map((a) => (el.getAttribute(a) ? a + '=' + el.getAttribute(a).slice(0, 160) : ''))
                  .filter(Boolean)
                  .join(' '),
              });
            });
            const vids = [...document.querySelectorAll('video')].map((v) => v.currentSrc || v.src || '');
            return { ifr, hints: hints.slice(0, 25), vids };
          });
          row.contentUrl = url;
          row.iframes.push(...info.ifr.map((x) => x.src).filter(Boolean));
          row.playerHints.push(...info.hints.map((h) => `${h.tag}.${h.cls}#${h.id} ${h.attrs}`.trim()));
          info.vids.filter(Boolean).forEach((v) => media.add('video ' + v));

          // Oynat butonlarina bas
          const clicked = new Set();
          const clickCandidates = [
            ...info.hints.filter((h) => /play|oynat/i.test(h.cls + h.id)).map((h) => '.' + h.cls.split(' ')[0]).filter((s) => s !== '.'),
            'button:has-text("Oynat")',
            'text=Oynat',
            '.play',
            '[class*=play]',
            '[id*=player]',
          ].slice(0, 6);
          for (const sel of clickCandidates) {
            if (clicked.has(sel)) continue;
            clicked.add(sel);
            try {
              const loc = cp.locator(sel).first();
              if (await loc.isVisible({ timeout: 1200 }).catch(() => false)) {
                await loc.click({ timeout: 2500, force: true }).catch(() => {});
                await cp.waitForTimeout(2500);
              }
            } catch {}
          }
          await cp.waitForTimeout(4000);
          // iframe'lerin src'lerini tekrar topla
          const extra = await cp.evaluate(() =>
            [...document.querySelectorAll('iframe')].map((f) => f.src).filter(Boolean)
          );
          row.iframes.push(...extra);
          if (row.iframes.length || media.size || info.vids.some(Boolean)) break;
        } catch (e) {
          row.note = (row.note ? row.note + ';' : '') + 'content:' + String(e.message).split('\n')[0].slice(0, 90);
        } finally {
          await cp.close().catch(() => {});
        }
      }
      row.media = [...media];
      row.iframes = [...new Set(row.iframes)];
      if (!row.contentUrl) row.note = (row.note ? row.note + ';' : '') + 'NO-CONTENT-LINK';
      else if (!row.media.length && !row.iframes.length) row.note = (row.note ? row.note + ';' : '') + 'NO-PLAYER';
      await ctx.close();
    } catch (e) {
      row.note = 'ERROR:' + String(e.message).split('\n')[0].slice(0, 120);
      await ctx.close().catch(() => {});
    }
    return row;
  }

  const queue = list.slice();
  const workers = Array.from({ length: 3 }, async () => {
    while (queue.length) {
      const e = queue.shift();
      const r = await audit(e);
      results.push(r);
      idx++;
      console.log(`[${idx}/${list.length}] ${r.provider} ${r.blocked ? 'BLOCK' : r.note || 'OK'} media=${r.media.length} iframe=${r.iframes.length}`);
    }
  });
  await Promise.all(workers);
  await browser.close();
  results.sort((a, b) => a.provider.localeCompare(b.provider));
  fs.writeFileSync(path.join(OUT, 'results.json'), JSON.stringify(results, null, 2), 'utf8');
  const lines = ['Provider\tNote\tContent\tMedia\tIframes'];
  for (const r of results)
    lines.push([r.provider, r.note, r.contentUrl, r.media.join(' | '), r.iframes.join(' | ')].join('\t'));
  fs.writeFileSync(path.join(OUT, 'results.tsv'), lines.join('\n'), 'utf8');
  console.log('\n=== OZET ===');
  for (const r of results)
    console.log(`${r.provider}: ${r.blocked ? 'ISP-BLOCK' : r.note || 'OK'} media=${r.media.length} iframe=${r.iframes.length} ${r.contentUrl}`);
})().catch((e) => {
  console.error('FATAL', e);
  process.exit(1);
});
