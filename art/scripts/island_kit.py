"""Shared by the island art scripts: a small translator from a restricted Python subset to Kotlin.

The gull's animation (`island_gull.py`) is written once, as plain Python functions over a render state. Run as they are they pose the
model for the previews; this module turns the very same functions into the Kotlin of `GullModel.setupAnim`, so the two cannot drift apart
(the numeric check against the compiled Kotlin then guards the translation itself, and Kotlin's 32-bit floats against Python's 64-bit ones).

What the subset allows, and what the translation does with it
-------------------------------------------------------------
* Functions with annotated parameters (`float`, `int`, `bool`, or the state class by the name `s`) and an annotated return type. A docstring
  becomes a KDoc comment, a bare string statement a `//` comment (an empty string is a blank line).
* Assignments to names (`x = ...`, and `x += ...`), to elements of constant arrays, `if / elif / else`, `for k in range(n)` (or `range(a, b)`),
  `return`. A name must be given its first value where it is used: not inside an `if` or a `for` that its later uses are outside of
  (give it a value before the block). A name assigned more than once becomes a `var`, otherwise a `val`.
* Expressions: arithmetic (`+ - * /` and `**` with an integer exponent), comparisons, `and or not`, conditional expressions, calls to the
  functions of the subset, to `abs min max floor sqrt sin cos tan asin acos atan2 exp`, `float()`, `int()`, `len()` of a constant array, and
  `s.field` for the fields of the render state. Integers and floats are kept apart as Kotlin keeps them apart: write `2.0`, not `2`, for a float;
  `a / b` of two integers is turned into a float division as Python's is.
* Module constants (floats, ints, tuples of either, tuples of strings that name parts) used by the functions are written into the Kotlin.
* The model's own operations are `turn(part, x, y, z)` (add to the part's rotation, radians), `shift(part, x, y, z)` (add to its position, px),
  `squash(part, x, y, z)` (set its scale), `show(part, on)`; a part is a string naming it, or an element of a tuple of such strings.
"""
import ast
import inspect
import re
import textwrap

INT, FLOAT, BOOL, STR, VOID = 'Int', 'Float', 'Boolean', 'String', 'Unit'
STATE = 'State'
PART = 'Part'
FARR, IARR, SARR = 'FloatArray', 'IntArray', 'PartArray'


class DslError(Exception):
    pass


MATH_FUNCS = {'sin': 'sin', 'cos': 'cos', 'tan': 'tan', 'sqrt': 'sqrt', 'exp': 'exp', 'floor': 'floor', 'asin': 'asin', 'acos': 'acos',
              'atan2': 'atan2', 'abs': 'abs'}
PART_OPS = {'turn': 3, 'shift': 3, 'squash': 3, 'show': 1}

# Kotlin operator precedence, higher binds tighter
P_ATOM, P_UNARY, P_MUL, P_ADD, P_CMP, P_EQ, P_AND, P_OR, P_IF = 10, 9, 8, 7, 5, 4, 3, 2, 1


def camel(name):
    """snake_case to camelCase (UPPER_CASE constants are left as they are)."""
    if name.upper() == name:
        return name
    return re.sub(r'_([a-zA-Z0-9])', lambda m: m.group(1).upper(), name)


def kt_float(v):
    """A Python number as a Kotlin Float literal."""
    v = float(v)
    if v == int(v) and abs(v) < 1e7:
        return f'{int(v)}f'
    r = repr(v)
    if len(r) > 11:
        r = '%.9g' % v
    if 'e' in r:
        m, e = r.split('e')
        return f'{m}e{int(e)}f'
    return r + 'f'


class Expr:
    __slots__ = ('code', 'type', 'prec')

    def __init__(self, code, type_, prec=P_ATOM):
        self.code, self.type, self.prec = code, type_, prec


class Fn:
    """What the translator knows of a function of the subset: its name, the types of its parameters and of what it returns."""

    def __init__(self, name, params, ret, node):
        self.name, self.params, self.ret, self.node = name, params, ret, node


class Translator:
    """Translates functions of the subset to Kotlin source.

    [fns]: the Python functions, in order; [consts]: {name: value} the functions may use (numbers, tuples of numbers, tuples of part names);
    [fields]: {state field: Kotlin type}; [state_class]: the render state's class name; [state_consts]: names of the constants that live in the
    state class (`NONE`, `PREEN`..): written as `private const val PREEN = GullRenderState.PREEN`."""

    def __init__(self, fns, consts, fields, state_class, state_consts=()):
        self.consts = dict(consts)
        self.fields = dict(fields)
        self.state_class = state_class
        self.state_consts = set(state_consts)
        self.fns = {}
        self.order = []
        self.used_consts = set()
        self.used_math = set()
        self.parts_used = []          # part names in the order first used
        self.partsets_used = []
        for f in fns:
            src = textwrap.dedent(inspect.getsource(f))
            node = ast.parse(src).body[0]
            assert isinstance(node, ast.FunctionDef), f
            params = []
            for a in node.args.args:
                params.append((a.arg, self.ann_type(a.annotation, a.arg)))
            ret = self.ann_type(node.returns, None) if node.returns is not None else VOID
            self.fns[node.name] = Fn(node.name, params, ret, node)
            self.order.append(node.name)

    # ---------------------------------------------------------------- types
    def ann_type(self, ann, argname):
        if ann is None:
            raise DslError(f'parameter {argname} needs a type annotation')
        if isinstance(ann, ast.Constant) and isinstance(ann.value, str):
            return STATE if ann.value == 'State' else ann.value
        if isinstance(ann, ast.Name):
            return {'float': FLOAT, 'int': INT, 'bool': BOOL, 'State': STATE}[ann.id]
        if isinstance(ann, ast.Constant) and ann.value is None:
            return VOID
        raise DslError(f'unsupported annotation {ast.dump(ann)}')

    def const_type(self, v):
        if isinstance(v, bool):
            return BOOL
        if isinstance(v, int):
            return INT
        if isinstance(v, float):
            return FLOAT
        if isinstance(v, tuple):
            if v and all(isinstance(x, str) for x in v):
                return SARR
            if all(isinstance(x, int) and not isinstance(x, bool) for x in v):
                return IARR
            return FARR
        raise DslError(f'unsupported constant {v!r}')

    # ---------------------------------------------------------------- output
    def kotlin(self):
        """(the functions as Kotlin source, indented for a class body; the constants as Kotlin source for a companion object;
        the part names used (with the Kotlin property each gets); the part sets used; the imports needed)."""
        funcs = []
        for name in self.order:
            funcs.append(self.function(self.fns[name]))
        consts = self.emit_consts()
        imports = sorted({f'kotlin.math.{m}' for m in self.used_math})
        return '\n\n'.join(funcs), consts, list(self.parts_used), list(self.partsets_used), imports

    def emit_consts(self):
        out = []
        for name in sorted(self.used_consts):
            v = self.consts[name]
            ty = self.const_type(v)
            if name in self.state_consts:
                out.append(f'        private const val {name} = {self.state_class}.{name}')
            elif ty == FLOAT:
                out.append(f'        private const val {name} = {kt_float(v)}')
            elif ty == INT:
                out.append(f'        private const val {name} = {v}')
            elif ty == BOOL:
                out.append(f'        private const val {name} = {"true" if v else "false"}')
            elif ty == FARR:
                out.append(f'        private val {name} = floatArrayOf({", ".join(kt_float(x) for x in v)})')
            elif ty == IARR:
                out.append(f'        private val {name} = intArrayOf({", ".join(str(x) for x in v)})')
            elif ty == SARR:
                pass                      # a set of parts: written with the parts, in the class body
        return '\n'.join(out)

    # ---------------------------------------------------------------- functions
    def function(self, fn):
        self.fn = fn
        self.scopes = [dict((n, t) for n, t in fn.params)]
        self.declared_in = [set()]
        node = fn.node
        # which names are assigned more than once (or augmented): they are vars
        self.counts = {}
        for n in ast.walk(node):
            if isinstance(n, ast.Assign):
                for t in n.targets:
                    if isinstance(t, ast.Name):
                        self.counts[t.id] = self.counts.get(t.id, 0) + 1
            elif isinstance(n, ast.AugAssign) and isinstance(n.target, ast.Name):
                self.counts[n.target.id] = self.counts.get(n.target.id, 0) + 2
            elif isinstance(n, ast.For) and isinstance(n.target, ast.Name):
                self.counts[n.target.id] = self.counts.get(n.target.id, 0) + 1
        body = list(node.body)
        doc = None
        if body and isinstance(body[0], ast.Expr) and isinstance(body[0].value, ast.Constant) and isinstance(body[0].value.value, str):
            doc = body.pop(0).value.value
        lines = []
        if doc:
            ds = textwrap.dedent(doc).strip().split('\n')
            lines.append('    /**')
            for d in ds:
                lines.append(('     * ' + d).rstrip())
            lines.append('     */')
        params = ', '.join(f'{camel(n)}: {self.kt_type(t)}' for n, t in fn.params)
        ret = '' if fn.ret == VOID else f': {self.kt_type(fn.ret)}'
        lines.append(f'    private fun {camel(fn.name)}({params}){ret} {{')
        self.block(body, 2, lines)
        lines.append('    }')
        return '\n'.join(lines)

    def kt_type(self, t):
        return self.state_class if t == STATE else t

    # ---------------------------------------------------------------- statements
    def block(self, stmts, depth, out):
        self.scopes.append({})
        for st in stmts:
            self.stmt(st, depth, out)
        self.scopes.pop()

    def lookup(self, name):
        for sc in reversed(self.scopes):
            if name in sc:
                return sc[name]
        return None

    def stmt(self, st, depth, out):
        pad = '    ' * depth
        if isinstance(st, ast.Expr):
            v = st.value
            if isinstance(v, ast.Constant) and isinstance(v.value, str):
                if v.value == '':
                    out.append('')
                else:
                    for ln in textwrap.dedent(v.value).strip().split('\n'):
                        out.append((pad + '// ' + ln).rstrip())
                return
            e = self.expr(v)
            out.append(pad + e.code)
        elif isinstance(st, ast.Assign):
            if len(st.targets) != 1:
                raise DslError('chained assignment')
            t = st.targets[0]
            if isinstance(t, ast.Name):
                rhs = self.expr(st.value)
                known = self.lookup(t.id)
                if known is None:
                    kw = 'var' if self.counts.get(t.id, 0) > 1 else 'val'
                    ty = rhs.type
                    self.scopes[-1][t.id] = ty
                    out.append(f'{pad}{kw} {camel(t.id)} = {rhs.code}')
                else:
                    rhs = self.coerce(rhs, known)
                    out.append(f'{pad}{camel(t.id)} = {rhs.code}')
            elif isinstance(t, ast.Subscript):
                tgt = self.expr(t)
                rhs = self.coerce(self.expr(st.value), tgt.type)
                out.append(f'{pad}{tgt.code} = {rhs.code}')
            else:
                raise DslError(f'assignment target {ast.dump(t)}')
        elif isinstance(st, ast.AugAssign):
            op = {ast.Add: '+=', ast.Sub: '-=', ast.Mult: '*=', ast.Div: '/='}[type(st.op)]
            tgt = self.expr(st.target)
            rhs = self.coerce(self.expr(st.value), tgt.type if tgt.type in (FLOAT, INT) else FLOAT)
            out.append(f'{pad}{tgt.code} {op} {rhs.code}')
        elif isinstance(st, ast.If):
            cond = self.cond(st.test)
            out.append(f'{pad}if ({cond.code}) {{')
            self.block(st.body, depth + 1, out)
            orelse = st.orelse
            while orelse and len(orelse) == 1 and isinstance(orelse[0], ast.If):
                c2 = self.cond(orelse[0].test)
                out.append(f'{pad}}} else if ({c2.code}) {{')
                self.block(orelse[0].body, depth + 1, out)
                orelse = orelse[0].orelse
            if orelse:
                out.append(f'{pad}}} else {{')
                self.block(orelse, depth + 1, out)
            out.append(f'{pad}}}')
        elif isinstance(st, ast.For):
            if not (isinstance(st.iter, ast.Call) and isinstance(st.iter.func, ast.Name) and st.iter.func.id == 'range'):
                raise DslError('only `for k in range(...)`')
            a = st.iter.args
            lo, hi = (('0', self.expr(a[0]).code) if len(a) == 1 else (self.expr(a[0]).code, self.expr(a[1]).code))
            var = st.target.id
            out.append(f'{pad}for ({camel(var)} in {lo} until {hi}) {{')
            self.scopes.append({var: INT})
            self.block(st.body, depth + 1, out)
            self.scopes.pop()
            out.append(f'{pad}}}')
        elif isinstance(st, ast.Return):
            if st.value is None:
                out.append(f'{pad}return')
            else:
                e = self.coerce(self.expr(st.value), self.fn.ret)
                out.append(f'{pad}return {e.code}')
        elif isinstance(st, ast.Pass):
            pass
        else:
            raise DslError(f'unsupported statement {ast.dump(st)[:80]}')

    def cond(self, node):
        e = self.expr(node)
        if e.type != BOOL:
            raise DslError(f'a condition must be a comparison, not {e.type}: {ast.dump(node)[:80]}')
        return e

    # ---------------------------------------------------------------- expressions
    def coerce(self, e, want):
        """[e] as a [want] (Int to Float where Kotlin needs it said)."""
        if want == FLOAT and e.type == INT:
            if re.fullmatch(r'-?\d+', e.code):
                return Expr(e.code + 'f', FLOAT)
            return Expr(f'{self.paren(e, P_ATOM)}.toFloat()', FLOAT)
        return e

    def paren(self, e, prec, right=False):
        """[e]'s code, in parentheses if it binds looser than [prec] (or as loosely, on the right of a left-associative operator)."""
        if e.prec < prec or (right and e.prec == prec):
            return f'({e.code})'
        return e.code

    def expr(self, n):
        if isinstance(n, ast.Constant):
            v = n.value
            if isinstance(v, bool):
                return Expr('true' if v else 'false', BOOL)
            if isinstance(v, int):
                return Expr(str(v), INT)
            if isinstance(v, float):
                return Expr(kt_float(v), FLOAT)
            if isinstance(v, str):
                return Expr(f'"{v}"', STR)
            raise DslError(f'constant {v!r}')
        if isinstance(n, ast.Name):
            t = self.lookup(n.id)
            if t is not None:
                return Expr(camel(n.id), t)
            if n.id in self.consts:
                self.used_consts.add(n.id)
                ty = self.const_type(self.consts[n.id])
                if ty == SARR:
                    if n.id not in self.partsets_used:
                        self.partsets_used.append(n.id)
                        for p in self.consts[n.id]:
                            if p not in self.parts_used:
                                self.parts_used.append(p)
                return Expr(n.id, ty)
            if n.id == 'PI':
                return Expr('PI_F', FLOAT)
            raise DslError(f'unknown name {n.id} in {self.fn.name}')
        if isinstance(n, ast.Attribute):
            if isinstance(n.value, ast.Name):
                base = self.lookup(n.value.id)
                if base == STATE:
                    if n.attr not in self.fields:
                        raise DslError(f'the render state has no field {n.attr}')
                    return Expr(f'{camel(n.value.id)}.{n.attr}', self.fields[n.attr])
                if n.value.id == 'math' and n.attr == 'pi':
                    return Expr('PI_F', FLOAT)
            raise DslError(f'attribute {ast.dump(n)[:80]}')
        if isinstance(n, ast.UnaryOp):
            v = self.expr(n.operand)
            if isinstance(n.op, ast.USub):
                if isinstance(n.operand, ast.Constant):
                    return Expr('-' + v.code, v.type, P_UNARY)
                return Expr('-' + self.paren(v, P_UNARY), v.type, P_UNARY)
            if isinstance(n.op, ast.Not):
                return Expr('!' + self.paren(v, P_UNARY), BOOL, P_UNARY)
            if isinstance(n.op, ast.UAdd):
                return v
            raise DslError('unary operator')
        if isinstance(n, ast.BinOp):
            return self.binop(n)
        if isinstance(n, ast.BoolOp):
            op, prec = (('&&', P_AND) if isinstance(n.op, ast.And) else ('||', P_OR))
            parts = [self.expr(v) for v in n.values]
            code = f' {op} '.join(self.paren(p, prec + 1) for p in parts)
            return Expr(code, BOOL, prec)
        if isinstance(n, ast.Compare):
            codes = []
            left = self.expr(n.left)
            for op, rn in zip(n.ops, n.comparators):
                right = self.expr(rn)
                sym = {ast.Lt: '<', ast.LtE: '<=', ast.Gt: '>', ast.GtE: '>=', ast.Eq: '==', ast.NotEq: '!='}[type(op)]
                a, b = left, right
                if a.type == FLOAT and b.type == INT:
                    b = self.coerce(b, FLOAT)
                elif a.type == INT and b.type == FLOAT:
                    a = self.coerce(a, FLOAT)
                prec = P_EQ if sym in ('==', '!=') else P_CMP
                codes.append(f'{self.paren(a, prec + 1)} {sym} {self.paren(b, prec + 1)}')
                left = right
            if len(codes) == 1:
                return Expr(codes[0], BOOL, P_EQ if '==' in codes[0] or '!=' in codes[0] else P_CMP)
            return Expr(' && '.join(f'({c})' for c in codes), BOOL, P_AND)
        if isinstance(n, ast.IfExp):
            c = self.cond(n.test)
            a, b = self.expr(n.body), self.expr(n.orelse)
            ty = FLOAT if FLOAT in (a.type, b.type) else a.type
            a, b = self.coerce(a, ty), self.coerce(b, ty)
            return Expr(f'if ({c.code}) {a.code} else {b.code}', ty, P_IF)
        if isinstance(n, ast.Subscript):
            base = self.expr(n.value)
            idx = self.expr(n.slice)
            ty = {FARR: FLOAT, IARR: INT, SARR: PART}.get(base.type)
            if ty is None:
                raise DslError(f'subscript of {base.type}')
            return Expr(f'{base.code}[{idx.code}]', ty)
        if isinstance(n, ast.Call):
            return self.call(n)
        raise DslError(f'unsupported expression {ast.dump(n)[:100]}')

    def binop(self, n):
        a, b = self.expr(n.left), self.expr(n.right)
        if isinstance(n.op, ast.Pow):
            if not (isinstance(n.right, ast.Constant) and isinstance(n.right.value, int)):
                raise DslError('** needs an integer constant exponent')
            self.used_math.add('pow')
            return Expr(f'{self.paren(a, P_ATOM)}.pow({n.right.value})', FLOAT)
        sym = {ast.Add: '+', ast.Sub: '-', ast.Mult: '*', ast.Div: '/'}.get(type(n.op))
        if sym is None:
            raise DslError(f'operator {type(n.op).__name__}')
        ty = FLOAT if FLOAT in (a.type, b.type) else INT
        if sym == '/' and ty == INT:
            a, ty = self.coerce(a, FLOAT), FLOAT
        prec = P_ADD if sym in '+-' else P_MUL
        left = self.paren(a, prec)
        right = self.paren(b, prec, right=True)
        # a bare integer next to a float is fine in Kotlin (Int * Float): leave it, but a minus sign right of an operator wants care
        return Expr(f'{left} {sym} {right}', ty, prec)

    def call(self, n):
        if isinstance(n.func, ast.Attribute) and isinstance(n.func.value, ast.Name) and n.func.value.id == 'math':
            name = n.func.attr
        elif isinstance(n.func, ast.Name):
            name = n.func.id
        else:
            raise DslError('call of ' + ast.dump(n.func)[:60])
        if name in PART_OPS:
            return self.part_op(name, n)
        if name in self.fns:
            fn = self.fns[name]
            if len(n.args) != len(fn.params):
                raise DslError(f'{name} takes {len(fn.params)} arguments')
            args = [self.coerce(self.expr(a), p[1]).code for a, p in zip(n.args, fn.params)]
            return Expr(f'{camel(name)}({", ".join(args)})', fn.ret)
        args = [self.expr(a) for a in n.args]
        if name in ('min', 'max'):
            ty = FLOAT if any(a.type == FLOAT for a in args) else INT
            args = [self.coerce(a, ty) for a in args]
            self.used_math.add(name)
            return Expr(f'{name}({", ".join(a.code for a in args)})', ty)
        if name == 'abs':
            self.used_math.add('abs')
            return Expr(f'abs({args[0].code})', args[0].type)
        if name in MATH_FUNCS:
            self.used_math.add(MATH_FUNCS[name])
            args = [self.coerce(a, FLOAT) for a in args]
            return Expr(f'{MATH_FUNCS[name]}({", ".join(a.code for a in args)})', FLOAT)
        if name == 'float':
            return self.coerce(Expr(args[0].code, args[0].type, args[0].prec), FLOAT) if args[0].type == INT else Expr(args[0].code, FLOAT, args[0].prec)
        if name == 'int':
            return Expr(f'{self.paren(args[0], P_ATOM)}.toInt()', INT)
        if name == 'len':
            return Expr(f'{args[0].code}.size', INT)
        raise DslError(f'unknown function {name}')

    def part_op(self, name, n):
        want = PART_OPS[name]
        if len(n.args) != 1 + want:
            raise DslError(f'{name}(part, ...) takes {want} numbers')
        first = n.args[0]
        if isinstance(first, ast.Constant) and isinstance(first.value, str):
            if first.value not in self.parts_used:
                self.parts_used.append(first.value)
            target = camel(first.value)
        else:
            e = self.expr(first)
            if e.type != PART:
                raise DslError(f'{name}: the first argument must name a part')
            target = e.code
        if name == 'show':
            on = self.expr(n.args[1])
            return Expr(f'show({target}, {on.code})', VOID)
        args = [self.coerce(self.expr(a), FLOAT).code for a in n.args[1:]]
        return Expr(f'{name}({target}, {", ".join(args)})', VOID)


def parse_state_fields(kotlin_text):
    """{field: Kotlin type} and {constant: value} of a render state class, read from its source (`var name = 0f`, `const val X = 1`)."""
    fields, consts = {}, {}
    for m in re.finditer(r'^\s*var\s+(\w+)\s*=\s*([^\n/]+)', kotlin_text, re.M):
        name, init = m.group(1), m.group(2).strip()
        if re.fullmatch(r'-?[\d.]+f', init):
            fields[name] = FLOAT
        elif init in ('true', 'false'):
            fields[name] = BOOL
        elif re.fullmatch(r'-?\d+', init) or re.fullmatch(r'[A-Z_]+', init):
            fields[name] = INT
        else:
            raise DslError(f'cannot tell the type of {name} = {init}')
    for m in re.finditer(r'const\s+val\s+(\w+)\s*=\s*(-?\d+)', kotlin_text):
        consts[m.group(1)] = int(m.group(2))
    # `var a = 0f` and `var b = 0f` on one line, or `var x = 0f; var y`: the doc comments sit above, so lines are taken as they come
    for m in re.finditer(r'var\s+(\w+)\s*=\s*0f\b', kotlin_text):
        fields.setdefault(m.group(1), FLOAT)
    return fields, consts
