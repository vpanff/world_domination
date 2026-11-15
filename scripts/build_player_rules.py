#!/usr/bin/env python3
"""Build HTML + PDF from docs/PLAYER_RULES.md (requires Markdown and LibreOffice)."""
from pathlib import Path
import subprocess
import tempfile
import markdown

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs/PLAYER_RULES.md"
OUTPUT = ROOT / "miniapp"
STYLE = """
:root { color-scheme: light; }
* { box-sizing: border-box; }
body { margin: 0 auto; max-width: 900px; padding: 24px 18px 48px;
       color: #20283a; background: #fff; font: 16px/1.65 'Liberation Sans', Arial, sans-serif; }
h1 { color: #263753; font-size: 32px; line-height: 1.2; }
h2 { color: #263753; font-size: 23px; margin-top: 34px; border-bottom: 2px solid #d9e3ee; padding-bottom: 6px; }
h3 { color: #263753; font-size: 18px; margin-top: 24px; }
a { color: #245c91; }
nav { padding: 12px 0; }
blockquote { margin: 18px 0; padding: 10px 16px; border-left: 4px solid #52779e; background: #edf3f8; }
blockquote p { margin: 4px 0; }
table { width: 100%; border-collapse: collapse; font-size: 14px; margin: 18px 0; }
th, td { text-align: left; vertical-align: top; border: 1px solid #bccbd9; padding: 8px; }
th { color: #263753; background: #e7eef5; }
tr { break-inside: avoid; }
.toc { background: #f3f6fa; padding: 10px 20px; border-radius: 8px; }
.toc ul { padding-left: 20px; }
@page { size: A4; margin: 17mm; }
@media print {
 body { max-width: none; padding: 0; font-size: 10.5pt; line-height: 1.4; }
 h1 { font-size: 24pt; } h2 { font-size: 16pt; } h3 { font-size: 12pt; }
 h1, h2, h3 { break-after: avoid; }
 table { font-size: 9pt; } th, td { padding: 5px; }
 nav, .toc { display: none; }
}
"""

def document(body, navigation=""):
    return ('<!doctype html>\n<html lang="ru"><head><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width, initial-scale=1">'
            '<title>Мировое господство — правила для игроков</title>'
            f'<style>{STYLE}</style></head><body>{navigation}<main>{body}</main></body></html>\n')

def build(source_path, stem):
    source = source_path.read_text(encoding="utf-8")
    renderer = markdown.Markdown(extensions=["tables", "toc"], extension_configs={"toc": {"toc_depth": "2"}})
    body = renderer.convert(source)
    nav = '<nav><a href="' + stem + '.pdf" target="_blank" rel="noopener" download>Скачать правила в PDF</a></nav>'
    html = document(body, nav + '<details><summary>Содержание</summary>' + renderer.toc + '</details>')
    OUTPUT.mkdir(exist_ok=True)
    (OUTPUT / (stem + ".html")).write_text(html, encoding="utf-8")
    # PDF uses the same content; exclude web navigation and keep print sizes explicit for Writer.
    print_style = """
    @page { size: A4; margin: 16mm; }
    body { font-family: 'Liberation Sans', Arial, sans-serif; color: #20283a; font-size: 10pt; }
    p { margin: 0 0 7pt; } li { margin-bottom: 3pt; }
    h1 { font-size: 22pt; color: #263753; margin-bottom: 10pt; }
    h2 { font-size: 14pt; color: #263753; margin: 14pt 0 7pt; page-break-after: avoid; }
    h3 { font-size: 11pt; color: #263753; margin: 10pt 0 6pt; page-break-after: avoid; }
    table { border-collapse: collapse; font-size: 9pt; width: 100%; margin: 7pt 0; }
    td, th { border: 0.5pt solid #aab9cc; padding: 5pt; text-align: left; }
    th { background: #e7eef5; } tr { page-break-inside: avoid; }
    blockquote { margin: 10pt; padding: 7pt; background: #edf3f8; }
    """
    print_html = document(body).replace(STYLE, print_style)
    print_html = print_html.replace('<table>', '<table border="1" cellspacing="0" cellpadding="5" width="100%">')
    with tempfile.TemporaryDirectory(prefix="wd-rules-") as tmp:
        work = Path(tmp)
        (work / "rules.html").write_text(print_html, encoding="utf-8")
        subprocess.run(["libreoffice", "-env:UserInstallation=" + (work / "profile").as_uri(),
                        "--headless", "--convert-to", "pdf:writer_web_pdf_Export", "--outdir", str(work),
                        str(work / "rules.html")], check=True, timeout=90)
        pdf = (work / "rules.pdf").read_bytes()
        if not pdf.startswith(b"%PDF-"):
            raise RuntimeError("LibreOffice did not create a PDF")
        (OUTPUT / (stem + ".pdf")).write_bytes(pdf)
    print(f"Created miniapp/{stem}.html and miniapp/{stem}.pdf")

def main():
    build(SOURCE, "rules")
    build(ROOT / "docs/HARD_RULES.md", "hard-rules")

if __name__ == "__main__":
    main()
