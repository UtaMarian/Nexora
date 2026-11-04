// Prints the HTML from documents.py to PDF with a local Chrome.
//   node pdfs.js <output folder>
// A file named scan-*.html becomes a PDF holding only a picture of the page, as a scanner makes, which only OCR can read.
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright-core');

(async () => {
  const out = process.argv[2];
  const html = path.join(out, 'html');
  const pdfs = path.join(out, 'pdf');
  fs.mkdirSync(pdfs, { recursive: true });
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1240, height: 1754 } });
  for (const file of fs.readdirSync(html)) {
    const name = file.replace(/\.html$/, '');
    await page.goto('file:///' + path.join(html, file).replace(/\\/g, '/'), { waitUntil: 'load' });
    if (name.startsWith('scan-')) {
      const jpg = await page.screenshot({ type: 'jpeg', quality: 70 });
      await page.setContent(`<!doctype html><style>@page{size:A4;margin:0}body{margin:0}img{width:210mm;height:297mm;display:block}</style><img src="data:image/jpeg;base64,${jpg.toString('base64')}">`);
      await page.waitForTimeout(300);
      await page.pdf({ path: path.join(pdfs, name.slice(5) + '.pdf'), printBackground: true, preferCSSPageSize: true });
    } else {
      await page.pdf({ path: path.join(pdfs, name + '.pdf'), printBackground: true, preferCSSPageSize: true });
    }
  }
  await browser.close();
  console.log(fs.readdirSync(pdfs).join(', '));
})();
