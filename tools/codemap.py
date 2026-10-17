"""
Writes a code map of Guildmark's Kotlin: one small Markdown file per package (each folder of the source is its own) listing every class, object, interface,
function and property that isn't private, with its line number and the first sentence of its doc comment, plus an
INDEX.md over the packages. Reading the map (or grepping it) and then opening only the lines you need is far cheaper
than opening whole files.

    python3 tools/codemap.py <repo> <out_dir>

Rerun it after changing code; it takes a second or two.
"""
import os
import re
import sys

SRC = 'src/main/kotlin/io/github/vaspyyy/guildmark'

MODS = r'(?:public|private|internal|protected|open|abstract|final|override|data|sealed|enum|annotation|inner|inline|value|const|lateinit|suspend|operator|infix|tailrec|external|companion|actual|expect)'
DECL = re.compile(r'^\s*(?:@[\w.]+(?:\([^)]*\))?\s+)*((?:' + MODS + r'\s+)*)(fun\s+interface|class|object|interface|fun|val|var|typealias)\b(.*)$')
STRING = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])+\'')
DOC_MAX = 110
SIG_MAX = 190
# A class or object with more properties than this lists them as names only (registries, lang keys)
PROP_FOLD = 16


def first_sentence(doc):
    text = ' '.join(doc.split())
    m = re.search(r'(?<=[.!?])\s', text)
    if m: text = text[:m.start()]
    text = re.sub(r'\[([^\]]+)\]', r'\1', text)
    return text if len(text) <= DOC_MAX else text[:DOC_MAX - 1].rstrip() + '…'


def strip_defaults(sig):
    """Drops default values from parameter lists and the body or initializer after the declaration."""
    out = []
    depth = 0
    skipping = False
    i = 0
    while i < len(sig):
        ch = sig[i]
        if ch in '([<{':
            if depth == 0 and ch == '{': break
            depth += 1
            if not skipping: out.append(ch)
        elif ch in ')]>}':
            if ch == '>' and i > 0 and sig[i - 1] == '-':
                if not skipping: out.append(ch)
                i += 1
                continue
            depth -= 1
            if skipping and depth == 0: skipping = False
            if not skipping: out.append(ch)
        elif ch == '=' and not skipping and (i + 1 >= len(sig) or sig[i + 1] not in '=>') and (i == 0 or sig[i - 1] not in '!<>='):
            if depth == 0: break
            if depth == 1: skipping = True
            elif not skipping: out.append(ch)
        elif ch == ',' and skipping and depth == 1:
            skipping = False
            out.append(ch)
        elif not skipping:
            out.append(ch)
        i += 1
    text = ''.join(out)
    text = re.sub(r'\s+', ' ', text).strip()
    text = re.sub(r'\(\s+', '(', text)
    text = re.sub(r'\s+\)', ')', text)
    text = re.sub(r'\s+,', ',', text)
    text = re.sub(r',\s*\)', ')', text)
    return text


def code_of(line):
    """The line with strings, chars and comments blanked, for counting brackets."""
    line = STRING.sub('""', line)
    line = re.sub(r'/\*.*?\*/', '', line)
    cut = line.find('//')
    return line[:cut] if cut >= 0 else line


def uncomment(line):
    """The line without a trailing // comment (left alone when the line has strings, which might hold a //)."""
    cut = line.find('//')
    return line[:cut].rstrip() if cut >= 0 and '"' not in line[:cut] else line


def scan(path):
    lines = open(path, encoding='utf-8').read().split('\n')
    decls = []          # (line, level, kind, sig, doc, container_id)
    depth = 0
    containers = []     # stack of (depth of its body, id, level)
    pending = None      # (target depth, id, level) of a class whose body hasn't opened yet
    doc = None
    in_doc = False
    in_comment = False
    in_raw = False
    doc_buf = []
    i = 0
    while i < len(lines):
        raw = lines[i]
        s = raw.strip()
        # Raw strings: skip their insides
        if in_raw:
            if '"""' in raw: in_raw = False
            i += 1
            continue
        if in_comment:
            if in_doc: doc_buf.append(s.rstrip('*/').lstrip('*').strip())
            if '*/' in raw:
                in_comment = False
                if in_doc:
                    doc = ' '.join(x for x in doc_buf if x)
                    in_doc = False
            i += 1
            continue
        if s.startswith('/*'):
            is_doc = s.startswith('/**')
            body = s[3:] if is_doc else s[2:]
            if '*/' in body:
                if is_doc: doc = body[:body.index('*/')].strip()
            else:
                in_comment = True
                in_doc = is_doc
                doc_buf = [body.strip()]
            i += 1
            continue
        if raw.count('"""') % 2 == 1:
            in_raw = True
        level_depths = {c[0] for c in containers} | {0}
        m = DECL.match(raw) if depth in level_depths and not s.startswith('//') else None
        start_depth = depth
        consumed = 1
        if m and 'private' not in m.group(1).split() and 'override' not in m.group(1).split() and not s.startswith('return'):
            mods, kind, rest = m.group(1), m.group(2), m.group(3)
            if kind.startswith('fun') and kind != 'fun': kind = 'interface'
            # Gather a signature that runs over several lines
            text = uncomment(raw.strip())
            j = i
            def balance(t):
                c = code_of(t)
                return c.count('(') - c.count(')')
            while balance(text) > 0 and j + 1 < len(lines) and j - i < 60:
                j += 1
                text += ' ' + uncomment(lines[j].strip())
            # a class header that carries on with ") : Base(...) {"
            sig = re.sub(r'^\s*(?:@[\w.]+(?:\([^)]*\))?\s+)*', '', text)
            sig = re.sub(r'/\*.*?\*/', '', sig)
            sig = strip_defaults(sig)
            sig = re.sub(r'\s+get\(\)$', '', sig)
            if len(sig) > SIG_MAX: sig = sig[:SIG_MAX - 1].rstrip() + '…'
            level = len(containers) if depth else 0
            if depth: level = next((c[2] + 1 for c in reversed(containers) if c[0] == depth), 0)
            cid = next((c[1] for c in reversed(containers) if c[0] == depth), None)
            decls.append((i + 1, level, kind, sig, first_sentence(doc) if doc else '', cid))
            if kind in ('class', 'object', 'interface'):
                pending = (depth + 1, i, level)
            consumed = j - i + 1
        # Brackets over the lines taken
        for k in range(i, i + consumed):
            for ch in code_of(lines[k]):
                if ch == '{':
                    depth += 1
                    if pending and depth == pending[0]:
                        containers.append(pending)
                        pending = None
                elif ch == '}':
                    depth -= 1
                    while containers and containers[-1][0] > depth: containers.pop()
        if pending and m is None and depth < pending[0] - 1:
            pending = None
        # A doc comment belongs to the declaration right after it
        if m or (s and not s.startswith('@') and not s.startswith('//')):
            doc = None
        i += consumed
    return decls, len(lines)


def render(decls):
    out = []
    # Fold containers with many properties into one line of names
    props = {}
    for d in decls:
        if d[2] in ('val', 'var'): props.setdefault(d[5], []).append(d)
    folded = {cid for cid, ps in props.items() if len(ps) > PROP_FOLD}
    done = set()
    for (line, level, kind, sig, doc, cid) in decls:
        if kind in ('val', 'var') and cid in folded:
            if cid in done: continue
            done.add(cid)
            names = [re.match(r'.*?\b(?:val|var)\s+(?:<[^>]*>\s*)?(?:[\w.<>]+\.)?(\w+)', p[3]).group(1) for p in props[cid] if re.match(r'.*?\b(?:val|var)\s+', p[3])]
            out.append(f'{line:>5} {"  " * level}{len(names)} vals: {", ".join(names)}')
            continue
        tail = f'  — {doc}' if doc else ''
        out.append(f'{line:>5} {"  " * level}{sig}{tail}')
    return out


def main():
    repo, dest = sys.argv[1], sys.argv[2]
    root = os.path.join(repo, SRC)
    os.makedirs(dest, exist_ok=True)
    packages = {}
    for dirpath, _, files in os.walk(root):
        for f in sorted(files):
            if not f.endswith('.kt'): continue
            rel = os.path.relpath(dirpath, root)
            pkg = rel.replace(os.sep, '.') if rel != '.' else '(root)'
            packages.setdefault(pkg, []).append(os.path.join(dirpath, f))
    index = ['# Guildmark code map', '',
             f'Every non-private declaration in {SRC}, with line numbers, by package. Generated by tools/codemap.py;',
             'regenerate after code changes. Read a package file (or grep this folder) to find what you need,',
             'then open only those lines.', '',
             '| package | files | lines | map | main types |', '|---|---|---|---|---|']
    for pkg in sorted(packages):
        body = []
        total = 0
        types = []
        for path in sorted(packages[pkg]):
            decls, n = scan(path)
            total += n
            rel = os.path.relpath(path, os.path.join(repo, SRC))
            body.append(f'\n## {rel} ({n} lines)')
            body.extend(render(decls))
            types += [re.search(r'\b(?:class|object|interface)\s+(\w+)', d[3]).group(1) for d in decls
                      if d[1] == 0 and d[2] in ('class', 'object', 'interface') and re.search(r'\b(?:class|object|interface)\s+(\w+)', d[3])]
        name = pkg.replace('(root)', 'root')
        head = [f'# {pkg} ({len(packages[pkg])} files, {total} lines)', f'Paths are under {SRC}/. Each line: line number, declaration, first sentence of its doc.']
        with open(os.path.join(dest, name + '.md'), 'w', encoding='utf-8') as fh:
            fh.write('\n'.join(head + body) + '\n')
        shown = ', '.join(types[:14]) + (f', +{len(types) - 14} more' if len(types) > 14 else '')
        index.append(f'| {pkg} | {len(packages[pkg])} | {total} | {name}.md | {shown} |')
    with open(os.path.join(dest, 'INDEX.md'), 'w', encoding='utf-8') as fh:
        fh.write('\n'.join(index) + '\n')


if __name__ == '__main__':
    main()
