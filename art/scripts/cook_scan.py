"""The dedicated-server scan (living-world/cook/design.md 9.4 and 12.4, Part M4; the method of living-world/m7/multiplayer.md section 1):
a dedicated server has none of the client's classes, so a class it loads that names one can fail there with NoClassDefFoundError, at the
start or the first time that code runs. This finds every compiled class outside Guildmark's `client/` package that names one.

    python3 -B art/scripts/cook_scan.py                    the classes kcheck-fast last compiled for this checkout
    python3 -B art/scripts/cook_scan.py --classes <dir>    the classes in <dir> (a compiler's output folder)

It runs Java (`javap`), so it runs through the shared compile lock, `flock -w 1800 /tmp/guildmark-kcheck.lock`: it puts itself under it,
unless it was started under it already. Run `kcheck-fast` first: it refuses classes that are not of the sources as they stand (the
sources' SHA-1s against the `sources.snap` kcheck-fast keeps beside them).

How. `javap -v -p` prints each class's constant pool and code. Every class a class names (its super class and interfaces, a field's or a
method's type, a call, a lambda, a companion, a type check, an annotation, a generic signature, Kotlin's metadata) is a Utf8 entry of that
pool, bare (`net/minecraft/client/Minecraft`) or in a descriptor (`Lnet/minecraft/client/Minecraft;`). The client's classes are the
Minecraft jar's entries its manifest marks `Minecraft-Dist: client` (net/minecraft/client, com/mojang/blaze3d and the rest), NeoForge's
`net/neoforged/neoforge/client/` (its manifest marks none), and Guildmark's own `client/`.

  - A class outside `client/` whose pool names one of the game's or NeoForge's client classes is FOUND. There must be none.
  - Guildmark's `client/` is in the mod's jar on a server too, so a class outside it may name a class of it if that code never runs there
    or reaches none of the game's client classes. Each such reach is placed in the method whose code makes it (a lambda's body in the
    method it is written in, a method reference through its bootstrap method), and is one of: plain code (the class of `client/`, and
    every class of `client/` it names in turn, names none of the game's client classes, so it can run anywhere); a client-bound payload's
    handler (a class of a payload the Kotlin registers only with `playToClient`, which NeoForge runs only on the client); or a method of
    REVIEWED below, read by hand and found behind `level.isClientSide` or called only from the client. Anything else is FOUND, and so is a
    name of `client/` in a pool that the scan cannot place in a method or the class's own attributes.
  - A string that spells a client class with dots names it for reflection only, which loads nothing by itself; those are listed apart.

What it cannot see: that a REVIEWED method still keeps its reach behind its guard (it trusts the reading, and lists each so it can be read
again), and a class loaded by a name built at run time. Exit status 0 if nothing is FOUND, 1 if something is, 2 if it could not scan.
Python's standard library only.
"""
import glob
import hashlib
import os
import re
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
LOCK = '/tmp/guildmark-kcheck.lock'
LIBS = '/mnt/project-files/guildmark-notes/compile-kit/libs'
MINECRAFT = 'minecraft-patched-26.3.0.52-beta.jar'
OURS = 'io/github/vaspyyy/guildmark/'
CLIENT = OURS + 'client/'
NEOFORGE_CLIENT = 'net/neoforged/neoforge/client/'
BATCH = 400                                                     # classes to a javap

# The reaches into Guildmark's client/ that are neither plain code nor a payload's handler, read by hand. Class: (the methods that may
# make them (a lambda's body counts as its method's), where and why each runs only on the client). The first seven are those of
# living-world/m7/multiplayer.md section 4, read again at this checkout; TaleSounds and the Cook's Book came after it.
G = OURS
REVIEWED = {
    G + 'block/NoticeBoardBlock': (('useWithoutItem',), 'block/NoticeBoardBlock.kt:172-175, behind level.isClientSide()'),
    G + 'item/ContractItem': (('use',), 'item/ContractItem.kt:16-17, behind level.isClientSide()'),
    G + 'magic/CatalystItem': (('use',), 'magic/MagicItems.kt:27, behind level.isClientSide'),
    G + 'magic/SpellBolt': (('onClientRemoval',), 'magic/SpellBolt.kt:161-163, onClientRemoval, which only the client calls'),
    G + 'season/AlmanacItem': (('use',), 'season/AlmanacItem.kt:13, behind level.isClientSide()'),
    G + 'season/Seasons': (('today',), 'season/Seasons.kt:116-117, today(), behind level.isClientSide'),
    G + 'townsfolk/Townsperson': (('onSyncedDataUpdated', 'tick'), 'townsfolk/Townsperson.kt:263-277 and :295, behind level().isClientSide'),
    G + 'tales/TaleSounds': (('ui',), 'tales/TaleSounds.kt:73-75, ui(), called only from client/atlas/TaleBanner.kt:243'),
    G + 'cooking/dish/CookBookItem': (('use',), 'cooking/dish/CookBookItem.kt:25, behind level.isClientSide'),
}

NAME = r'[A-Za-z_$][\w$]*(?:/[\w$]+)+'
SLASHED = re.compile(r'(?<![\w$/])(%s)' % NAME)
IN_DESCRIPTOR = re.compile(r'L(%s)[;<]' % NAME)
DOTTED_TYPE = re.compile(r'(?<![\w$.])([a-z][\w$]*(?:\.[\w$]+)+)')
DOTTED = re.compile(r'\b((?:io\.github\.vaspyyy\.guildmark\.client|net\.minecraft\.client|net\.neoforged\.neoforge\.client|com\.mojang\.blaze3d)\.[\w.$]+)')
UTF8 = re.compile(r'^\s+#\d+ = Utf8\s+(.*)$')
INDY = re.compile(r'\binvokedynamic\s+#\d+,\s*0\s+// InvokeDynamic #(\d+):')
BOOT = re.compile(r'^  (\d+): #\d+ ')
MEMBER = re.compile(r'([\w$.]+)\(')


def names_in(text):
    """The class names (slashed) that a line of javap's output spells: bare, in a descriptor, or dotted in a declaration."""
    return set(IN_DESCRIPTOR.findall(text)) | set(SLASHED.findall(text))


class Parsed:
    """What javap -v -p says one class names: in its pool, by member (method name, '<field>', '<init>', '<clinit>' or '<class>' for its
    own attributes), and in its InnerClasses (which load nothing); and the dotted strings in its pool."""

    def __init__(self, name):
        self.name = name
        self.pool = set()
        self.members = {}
        self.inner = set()
        self.strings = set()
        self.boot = {}
        self.indy = []

    def finish(self):
        for member, k in self.indy:
            self.members.setdefault(member, set()).update(self.boot.get(k, ()))
        return self

    def unplaced(self):
        placed = set(self.inner).union(*self.members.values()) if self.members else set(self.inner)
        return self.pool - placed


def parse(lines, folder):
    """The classes in one javap -v -p output, by name."""
    out, p, part, member, boot, simple = {}, None, None, None, None, None
    for line in lines:
        if line.startswith('Classfile '):
            if p is not None:
                out[p.name] = p.finish()
            name = os.path.relpath(line[len('Classfile '):].strip(), folder)[:-len('.class')].replace(os.sep, '/')
            p, part, member, boot = Parsed(name), 'head', None, None
            simple = name.rsplit('/', 1)[-1]
            continue
        if p is None:
            continue
        if part == 'head':
            if line.startswith('Constant pool:'):
                part = 'pool'
            else:
                dotted = {t.replace('.', '/') for t in DOTTED_TYPE.findall(line)} if not line.startswith(' ') else set()
                p.members.setdefault('<class>', set()).update(names_in(line) | dotted)
        elif part == 'pool':
            if line == '{':
                part = 'body'
                continue
            m = UTF8.match(line)
            if m:
                u = m.group(1)
                p.pool |= {u.lstrip('[')} if re.fullmatch(r'\[*%s' % NAME, u) else set()
                p.pool |= set(IN_DESCRIPTOR.findall(u))
                p.strings |= set(DOTTED.findall(u))
        elif part == 'body':
            if line == '}':
                part, member = 'attrs', '<class>'
                continue
            if line.startswith('  ') and not line.startswith('   ') and line.strip():
                head = line.strip()
                m = MEMBER.search(head)
                if head == 'static {};':
                    member = '<clinit>'
                elif m is None:
                    member = '<field>'
                else:
                    member = m.group(1).rsplit('.', 1)[-1]
                    member = '<init>' if member == simple else member
                found = {t.replace('.', '/') for t in DOTTED_TYPE.findall(head)}
            else:
                found = names_in(line)
                k = INDY.search(line)
                if k and member is not None:
                    p.indy.append((member, int(k.group(1))))
            if member is not None:
                p.members.setdefault(member, set()).update(found)
        else:                                                                   # the class's attributes
            if line and not line.startswith(' '):
                section = line.split(':', 1)[0]
                member = {'InnerClasses': '<inner>', 'BootstrapMethods': '<boot>'}.get(section, '<class>')
                boot = None
                if member == '<class>':
                    p.members.setdefault(member, set()).update(names_in(line))
                continue
            if member == '<inner>':
                p.inner |= names_in(line)
            elif member == '<boot>':
                b = BOOT.match(line)
                if b:
                    boot = int(b.group(1))
                if boot is not None:
                    p.boot.setdefault(boot, set()).update(names_in(line))
            else:
                p.members.setdefault('<class>', set()).update(names_in(line))
    if p is not None:
        out[p.name] = p.finish()
    return out


def under_lock():
    """Whether a process this one was started by is `flock ... LOCK`, so the lock is held for it already."""
    pid = os.getppid()
    while pid > 1:
        try:
            with open('/proc/%d/cmdline' % pid, 'rb') as f:
                args = f.read().split(b'\0')
            with open('/proc/%d/stat' % pid) as f:
                stat = f.read()
        except OSError:
            return False
        if args and os.path.basename(args[0]) == b'flock' and LOCK.encode() in args:
            return True
        pid = int(stat[stat.rindex(')') + 2:].split()[1])
    return False


def classes_dir():
    """The output folder of kcheck-fast's state for this checkout, and its sources.snap; (None, None) if it has none."""
    base = os.environ.get('KCHECK_FAST_DIR') or '/tmp/kcheck-fast-%d' % os.getuid()
    for repo in sorted(glob.glob(os.path.join(base, '*', 'repos', '*', 'repo.txt'))):
        with open(repo) as f:
            if os.path.normpath(f.read().strip()) == ROOT:
                d = os.path.dirname(repo)
                return os.path.join(d, 'out'), os.path.join(d, 'sources.snap')
    return None, None


def stale(snap):
    """The sources whose SHA-1 differs from [snap]'s (or that are new, or gone), as paths relative to the checkout."""
    want = {}
    with open(snap) as f:
        for line in f:
            sha, _, path = line.rstrip('\n').partition(' ')
            want[os.path.normpath(path)] = sha
    have = {}
    for top_dir, _, names in os.walk(os.path.join(ROOT, 'src', 'main', 'kotlin')):
        for n in names:
            if n.endswith(('.kt', '.java')):
                p = os.path.join(top_dir, n)
                with open(p, 'rb') as f:
                    have[p] = hashlib.sha1(f.read()).hexdigest()
    return sorted(os.path.relpath(p, ROOT) for p in set(want) | set(have) if want.get(p) != have.get(p))


def client_classes():
    """The game's client classes: the Minecraft jar's that its manifest marks for the client ('net/minecraft/client/Minecraft')."""
    with zipfile.ZipFile(os.path.join(LIBS, MINECRAFT)) as z:
        text = z.read('META-INF/MANIFEST.MF').decode('utf-8')
    text = text.replace('\r\n', '\n').replace('\n ', '')                   # a long line goes on after a newline and a space
    out = set()
    for section in text.split('\n\n'):
        fields = dict(line.split(': ', 1) for line in section.split('\n') if ': ' in line)
        name = fields.get('Name', '')
        if fields.get('Minecraft-Dist') == 'client' and name.endswith('.class'):
            out.add(name[:-len('.class')])
    return out


def theirs(name, marked):
    """Whether [name] is one of the game's or NeoForge's client classes (not Guildmark's)."""
    return name.startswith(NEOFORGE_CLIENT) or name in marked or ('$' in name and name.split('$', 1)[0] in marked)


def scan(folder):
    """The classes in [folder], parsed, by name; and how many javap printed."""
    files = sorted(glob.glob(os.path.join(folder, '**', '*.class'), recursive=True))
    out = {}
    for i in range(0, len(files), BATCH):
        batch = files[i:i + BATCH]
        run = subprocess.run(['nice', '-n', '10', 'javap', '-J-Xmx512m', '-v', '-p'] + batch, capture_output=True, text=True, errors='replace')
        if run.returncode != 0:
            raise RuntimeError('javap failed on the batch from %s: %s' % (os.path.relpath(batch[0], folder), run.stderr.strip()[-400:]))
        out.update(parse(run.stdout.split('\n'), folder))
    if len(out) != len(files):
        raise RuntimeError('javap printed %d classes of %d' % (len(out), len(files)))
    return out


def plain(name, classes, marked):
    """None if Guildmark's client class [name], and every class of client/ it names in turn, names none of the game's client classes;
    else the chain to the first that does ('client/A -> client/B -> net/minecraft/client/Minecraft')."""
    seen, todo = {name}, [(name, [name])]
    while todo:
        c, chain = todo.pop()
        if c not in classes:
            return ' -> '.join(chain) + ' (not among the classes read)'
        game = sorted(n for n in classes[c].pool if theirs(n, marked))
        if game:
            return ' -> '.join(chain + [game[0]])
        for o in sorted(n for n in classes[c].pool if n.startswith(CLIENT) and n not in seen):
            seen.add(o)
            todo.append((o, chain + [o]))
    return None


TO_CLIENT = re.compile(r'\bplayToClient\(\s*(?:(\w+)\.)?TYPE\b')
OTHER = re.compile(r'\b(?:playToServer|playBidirectional|configurationToClient|configurationToServer|configurationBidirectional|'
                   r'commonToClient|commonToServer|commonBidirectional)\(\s*(?:(\w+)\.)?TYPE\b')


def payloads():
    """The payloads the Kotlin registers for the client only, by simple class name ('FestivalPayload'): their handlers run only there.
    A bare `TYPE` is the payload class the file declares, when it declares one."""
    to_client, other = set(), set()
    for top_dir, _, names in os.walk(os.path.join(ROOT, 'src', 'main', 'kotlin')):
        for n in names:
            if n.endswith('.kt'):
                with open(os.path.join(top_dir, n), encoding='utf-8') as f:
                    src = f.read()
                own = set(re.findall(r'\bclass\s+(\w+Payload)\b', src))
                for rx, into in ((TO_CLIENT, to_client), (OTHER, other)):
                    for name in rx.findall(src):
                        if name:
                            into.add(name)
                        elif len(own) == 1:
                            into |= own
                        else:
                            other.add('?')                                  # a bare TYPE in a file of none or several: counts for none
    return to_client - other


def short(name):
    return name[len(OURS):] if name.startswith(OURS) else name


def main(argv):
    if not under_lock() and os.environ.get('COOK_SCAN_LOCKED') != '1':
        env = dict(os.environ, COOK_SCAN_LOCKED='1')
        os.execvpe('flock', ['flock', '-E', '2', '-w', '1800', LOCK, sys.executable, '-B', os.path.abspath(__file__)] + argv, env)
    snap = None
    if argv[:1] == ['--classes'] and len(argv) == 2:
        folder = os.path.abspath(argv[1])
    elif not argv:
        folder, snap = classes_dir()
        if folder is None or not os.path.isdir(folder):
            print('kcheck-fast keeps no classes for %s: run it first' % ROOT, file=sys.stderr)
            return 2
    else:
        print(__doc__.strip().split('\n\n')[1], file=sys.stderr)
        return 2
    if snap is not None:
        old = stale(snap)
        if old:
            print('the classes are not of the sources as they stand (%d differ, %s first): run kcheck-fast first' % (len(old), old[0]), file=sys.stderr)
            return 2
    marked = client_classes()
    try:
        classes = scan(folder)
    except RuntimeError as e:
        print(e, file=sys.stderr)
        return 2
    common = {c: p for c, p in classes.items() if not c.startswith(CLIENT)}
    print('%d classes read, %d of them outside client/; the game\'s client classes: %d marked in %s, and %s*' % (
        len(classes), len(common), len(marked), MINECRAFT, NEOFORGE_CLIENT))
    found = []

    game = {c: sorted(n for n in p.pool if theirs(n, marked)) for c, p in common.items()}
    game = {c: ns for c, ns in game.items() if ns}
    print('\nclasses outside client/ that name a client class of the game or NeoForge: %d' % len(game))
    for c, ns in sorted(game.items()):
        found.append('%s names %s%s' % (short(c), ', '.join(ns[:4]), ' (+%d more)' % (len(ns) - 4) if len(ns) > 4 else ''))
        print('  FOUND ' + found[-1])

    bound, used = payloads(), set()
    kinds = {'plain': [], 'payload': [], 'reviewed': [], 'FOUND': []}
    reach = 0
    for c, p in sorted(common.items()):
        ours = {n for n in p.pool if n.startswith(CLIENT)}
        if not ours:
            continue
        top = c.split('$', 1)[0].rsplit('/', 1)[-1]
        for n in sorted(p.unplaced() & ours):
            if plain(n, classes, marked) is not None:
                kinds['FOUND'].append('%s names %s where this scan can\'t place it' % (short(c), short(n)))
        for member, names in sorted(p.members.items()):
            for n in sorted(names & ours):
                reach += 1
                chain = plain(n, classes, marked)
                what = '%s.%s names %s' % (short(c), member, short(n))
                allowed = REVIEWED.get(c, ((), ''))
                if chain is None:
                    kinds['plain'].append(what)
                elif top in bound:
                    kinds['payload'].append(what)
                elif any(member == m or member.startswith(m + '$') for m in allowed[0]):
                    used.add(c)
                    kinds['reviewed'].append('%s (%s)' % (what, allowed[1]))
                else:
                    kinds['FOUND'].append('%s, which reaches the game\'s client: %s' % (what, ' -> '.join(short(x) for x in chain.split(' -> '))))
    print('\nreaches from outside client/ into Guildmark\'s client/, by method: %d' % reach)
    print('  plain code (it, and what it names of client/, names none of the game\'s client classes): %d' % len(kinds['plain']))
    for line in kinds['plain']:
        print('    ' + line)
    print('  a client-bound payload\'s handler (NeoForge runs it only on the client): %d' % len(kinds['payload']))
    for line in kinds['payload']:
        print('    ' + line)
    print('  read by hand, run only on the client (REVIEWED): %d' % len(kinds['reviewed']))
    for line in kinds['reviewed']:
        print('    ' + line)
    print('  none of these: %d' % len(kinds['FOUND']))
    for line in kinds['FOUND']:
        print('    FOUND ' + line)
    found += kinds['FOUND']
    for c in sorted(set(REVIEWED) - used):
        print('  (REVIEWED lists %s, which no longer reaches client/ from those methods)' % short(c))

    strings = {c: sorted(p.strings) for c, p in common.items() if p.strings}
    if strings:
        print('\nstrings outside client/ that spell a client class (they load nothing by themselves; read them): %d classes' % len(strings))
        for c, ss in sorted(strings.items()):
            print('  %s: %s' % (short(c), ', '.join(ss[:4])))
    print('\nfound: %d' % len(found))
    return 1 if found else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
