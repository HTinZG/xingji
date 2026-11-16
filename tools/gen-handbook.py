#!/usr/bin/env python3
"""
重新生成手册：把源码附录追加到 docs/handbook.md，再渲染成 docs/handbook.html。

用法（在仓库根目录）：
    python tools/gen-handbook.py

什么时候要跑：改了源码、或者改了 handbook.md 的正文之后。
正文里 `## 附录 A：源码` 之后的内容每次都会被重新生成，手改那里会被覆盖。
"""
import os
import re
import html

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HANDBOOK_MD = os.path.join(ROOT, 'docs', 'handbook.md')
HANDBOOK_HTML = os.path.join(ROOT, 'docs', 'handbook.html')

KOTLIN_DIRS = [
    ('app/src/main/java', '主程序'),
    ('app/src/test/java', '单元测试'),
]

CONFIG_FILES = [
    ('app/build.gradle.kts', 'kotlin', '模块构建脚本：依赖、签名、R8'),
    ('build.gradle.kts', 'kotlin', '根构建脚本'),
    ('settings.gradle.kts', 'kotlin', '工程与仓库配置'),
    ('gradle.properties', 'properties', 'Gradle 与 Kotlin 编译参数'),
    ('app/proguard-rules.pro', 'proguard', 'R8 混淆/保留规则'),
    ('app/src/main/AndroidManifest.xml', 'xml', '权限、组件、高德 Key 占位符'),
    ('app/src/main/res/xml/network_security_config.xml', 'xml', '允许用户配置的 http 接口'),
    ('app/src/main/res/xml/file_paths.xml', 'xml', 'FileProvider 暴露的目录'),
    ('tools/Check-16Kb.ps1', 'powershell', '16KB 页对齐检查脚本'),
]

MARKER = '\n## 附录 A：源码'


def first_doc(text):
    m = re.search(r'/\*\*(.*?)\*/', text, re.S)
    if not m:
        return ''
    lines = [re.sub(r'^\s*\*\s?', '', ln).strip() for ln in m.group(1).strip().split('\n')]
    lines = [ln for ln in lines if ln and not ln.startswith('@')]
    return lines[0][:60] if lines else ''


def build_appendix():
    entries = []
    for rel_root, group in KOTLIN_DIRS:
        base = os.path.join(ROOT, rel_root.replace('/', os.sep))
        for dirpath, _, files in os.walk(base):
            for name in sorted(files):
                if not name.endswith('.kt'):
                    continue
                full = os.path.join(dirpath, name)
                rel = os.path.relpath(full, ROOT).replace(os.sep, '/')
                text = open(full, encoding='utf-8').read()
                entries.append((rel, 'kotlin', first_doc(text), text, group))

    for rel, lang, desc in CONFIG_FILES:
        full = os.path.join(ROOT, rel.replace('/', os.sep))
        if os.path.exists(full):
            entries.append((rel, lang, desc, open(full, encoding='utf-8').read(), '构建与配置'))

    def sort_key(e):
        order = {'core': 0, 'data': 1, 'map': 2, 'service': 3, 'feature': 4}
        parts = e[0].split('/')
        try:
            idx = parts.index('path') + 1
            pkg = parts[idx] if idx < len(parts) else ''
        except ValueError:
            pkg = ''
        return (e[4] != '主程序', e[4] != '单元测试', order.get(pkg, 5), e[0])

    entries.sort(key=sort_key)
    total_lines = sum(len(e[3].split('\n')) for e in entries)

    out = ['\n---\n', '## 附录 A：源码全文\n']
    out.append('> 为什么把源码也抄进手册：这个项目以后大概率是「隔几个月想起来改一点」，')
    out.append('> 那时翻 GitHub 不如在本地文档里直接搜关键词快。')
    out.append('> 源码本身以仓库为准，这里这份是**某一时刻的快照**（%d 个文件 / %d 行）。' % (len(entries), total_lines))
    out.append('> 改完代码后运行 `python tools/gen-handbook.py` 重新生成。\n')
    out.append('网页版里每个文件都是折叠的，点标题展开。\n')

    out.append('### 目录\n')
    out.append('| 分组 | 文件 | 行数 | 说明 |')
    out.append('|---|---|---|---|')
    for rel, lang, desc, text, group in entries:
        out.append('| %s | `%s` | %d | %s |' % (group, rel, len(text.split('\n')), desc or '—'))
    out.append('')

    current = None
    for rel, lang, desc, text, group in entries:
        if group != current:
            current = group
            out.append('\n### %s\n' % group)
        out.append('<!-- FILE: %s -->\n' % rel)
        out.append('```%s' % lang)
        out.append(text.rstrip('\n'))
        out.append('```\n')

    return '\n'.join(out)


def md_to_html(md_text):
    lines = md_text.split('\n')
    out, i = [], 0
    in_code, code_buf, list_type, pending_file, heading_n = False, [], None, None, 0

    def inline(t):
        t = html.escape(t)
        t = re.sub(r'`([^`]+)`', r'<code>\1</code>', t)
        t = re.sub(r'\*\*([^*]+)\*\*', r'<strong>\1</strong>', t)
        return t

    while i < len(lines):
        line = lines[i]

        fm = re.match(r'^\s*<!--\s*FILE:\s*(.+?)\s*-->\s*$', line)
        if fm and not in_code:
            pending_file = fm.group(1)
            i += 1
            continue

        if line.strip().startswith('```'):
            if in_code:
                code_html = '<pre><code>' + html.escape('\n'.join(code_buf)) + '</code></pre>'
                if pending_file:
                    out.append(f'<details class="src"><summary><span class="fpath">{inline(pending_file)}</span></summary>'
                               + code_html + '</details>')
                    pending_file = None
                else:
                    out.append(code_html)
                code_buf, in_code = [], False
            else:
                in_code = True
            i += 1
            continue
        if in_code:
            code_buf.append(line)
            i += 1
            continue

        s = line.strip()

        if s.startswith('|') and i + 1 < len(lines) and set(lines[i + 1].strip()) <= set('|-: '):
            header = [c.strip() for c in s.strip('|').split('|')]
            rows = []
            i += 2
            while i < len(lines) and lines[i].strip().startswith('|'):
                rows.append([c.strip() for c in lines[i].strip().strip('|').split('|')])
                i += 1
            cls = ' class="srctable"' if header[:2] == ['分组', '文件'] else ''
            out.append(f'<table{cls}><thead><tr>' + ''.join(f'<th>{inline(h)}</th>' for h in header) + '</tr></thead><tbody>')
            for r in rows:
                out.append('<tr>' + ''.join(f'<td>{inline(c)}</td>' for c in r) + '</tr>')
            out.append('</tbody></table>')
            continue

        m = re.match(r'^(#{1,4})\s+(.*)$', s)
        if m:
            level = len(m.group(1))
            heading_n += 1
            out.append(f'<h{level} id="s{heading_n}">{inline(m.group(2))}</h{level}>')
            i += 1
            continue

        if s == '---':
            out.append('<hr>')
            i += 1
            continue

        if s.startswith('>'):
            buf = []
            while i < len(lines) and lines[i].strip().startswith('>'):
                buf.append(lines[i].strip().lstrip('>').strip())
                i += 1
            out.append('<blockquote>' + inline(' '.join(buf)) + '</blockquote>')
            continue

        if re.match(r'^[-*]\s+', s):
            if list_type != 'ul':
                if list_type == 'ol':
                    out.append('</ol>')
                out.append('<ul>')
                list_type = 'ul'
            out.append('<li>' + inline(re.sub(r'^[-*]\s+', '', s)) + '</li>')
            i += 1
            continue
        if re.match(r'^\d+\.\s+', s):
            if list_type != 'ol':
                if list_type == 'ul':
                    out.append('</ul>')
                out.append('<ol>')
                list_type = 'ol'
            out.append('<li>' + inline(re.sub(r'^\d+\.\s+', '', s)) + '</li>')
            i += 1
            continue

        if list_type:
            out.append('</ul>' if list_type == 'ul' else '</ol>')
            list_type = None

        if s:
            out.append('<p>' + inline(s) + '</p>')
        i += 1

    if list_type:
        out.append('</ul>' if list_type == 'ul' else '</ol>')
    return '\n'.join(out)


CSS = """
:root { --fg:#1a1a1a; --muted:#666; --line:#e2e2e2; --accent:#5b4bc4; --bg:#faf9f7; }
* { box-sizing: border-box; }
body { margin:0; padding:0; background:var(--bg); color:var(--fg);
  font-family: -apple-system, "PingFang SC", "Microsoft YaHei", "Segoe UI", sans-serif;
  font-size:16px; line-height:1.75; }
.wrap { max-width: 860px; margin: 0 auto; padding: 56px 28px 120px; }
h1 { font-size:30px; margin:0 0 8px; letter-spacing:-0.5px; }
h2 { font-size:22px; margin:48px 0 14px; padding-bottom:8px; border-bottom:2px solid var(--line); }
h3 { font-size:17px; margin:30px 0 10px; color:var(--accent); }
h4 { font-size:15px; margin:22px 0 8px; }
p { margin:10px 0; }
a { color:var(--accent); }
code { background:#efeef4; padding:2px 6px; border-radius:4px;
  font-family:"Cascadia Code",Consolas,monospace; font-size:13.5px; }
pre { background:#26232e; color:#e8e6f0; padding:16px 18px; border-radius:10px;
  overflow-x:auto; line-height:1.6; }
pre code { background:none; padding:0; color:inherit; font-size:13px; }
table { width:100%; border-collapse:collapse; margin:16px 0; font-size:14.5px;
  background:#fff; border-radius:8px; overflow:hidden; box-shadow:0 1px 2px rgba(0,0,0,.06); }
th, td { padding:10px 14px; text-align:left; border-bottom:1px solid var(--line); vertical-align:top; }
th { background:#f2f1f6; font-weight:600; }
tr:last-child td { border-bottom:none; }
blockquote { margin:18px 0; padding:12px 18px; border-left:4px solid var(--accent);
  background:#f3f1fb; border-radius:0 8px 8px 0; color:#3d3760; }
ul, ol { padding-left:24px; margin:10px 0; }
li { margin:5px 0; }
hr { border:none; border-top:1px solid var(--line); margin:40px 0; }
strong { color:#111; }
details.src { margin:8px 0; border:1px solid var(--line); border-radius:8px; background:#fff; overflow:hidden; }
details.src summary { cursor:pointer; padding:10px 14px; font-family:"Cascadia Code",Consolas,monospace;
  font-size:13px; color:#3d3760; background:#f7f6fb; user-select:none; }
details.src summary:hover { background:#efedf8; }
details.src[open] summary { border-bottom:1px solid var(--line); }
details.src pre { margin:0; border-radius:0; }
table.srctable { table-layout: fixed; }
table.srctable th:nth-child(1), table.srctable td:nth-child(1) { width:78px; white-space:nowrap; }
table.srctable th:nth-child(3), table.srctable td:nth-child(3) { width:56px; white-space:nowrap; text-align:right; }
table.srctable td:nth-child(2) { word-break: break-all; }
"""


def main():
    md = open(HANDBOOK_MD, encoding='utf-8').read()
    if MARKER in md:
        md = md[:md.index(MARKER)].rstrip() + '\n'
    md = md.rstrip() + '\n' + build_appendix()
    open(HANDBOOK_MD, 'w', encoding='utf-8').write(md)

    body = md_to_html(md)
    page = ('<!DOCTYPE html>\n<html lang="zh-CN">\n<head>\n<meta charset="utf-8">\n'
            '<meta name="viewport" content="width=device-width, initial-scale=1">\n'
            '<title>行迹 · 项目手册</title>\n<style>' + CSS + '</style>\n</head>\n<body><div class="wrap">\n'
            + body + '\n</div></body></html>\n')
    open(HANDBOOK_HTML, 'w', encoding='utf-8').write(page)

    files = page.count('<details class="src">')
    print('handbook.md  : %d 行' % len(md.split('\n')))
    print('handbook.html: %.0f KB, %d 个折叠源码块' % (len(page) / 1024, files))


if __name__ == '__main__':
    main()
