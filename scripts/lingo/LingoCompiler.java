// Lingo compiler: Director 8 Lingo source -> Director 8 bytecode.
//
// The goal is to reproduce Macromedia's compiler exactly, so that a script decompiled by
// ProjectorRays and compiled again here gives the original bytecode back. That lets edited
// scripts be written into a .dir without Adobe Director. docs/LINGO.md lists the rules it follows.
//
// Pipeline: Lexer -> Parser (AST) -> CodeGen (bytecode, literals, names).

import java.util.*;

public class LingoCompiler {

    // ================================================================== lexer

    enum T { IDENT, INT, FLOAT, STRING, SYMBOL, OP, NL, EOF }

    static final class Tok {
        final T t; final String s; final int line;
        Tok(T t, String s, int line) { this.t = t; this.s = s; this.line = line; }
        boolean is(String w) { return (t == T.IDENT || t == T.OP) && s.equalsIgnoreCase(w); }
        public String toString() { return t + ":" + s; }
    }

    static List<Tok> lex(String src) {
        List<Tok> out = new ArrayList<>();
        int i = 0, line = 1, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < n && src.charAt(i + 1) == '\n') i++;
                out.add(new Tok(T.NL, "\n", line++)); i++; continue;
            }
            if (c == ' ' || c == '\t') { i++; continue; }
            if (c == (char) 0xAC) { // line continuation (Mac Roman not-sign)
                i++;
                while (i < n && src.charAt(i) != '\r' && src.charAt(i) != '\n') i++;
                if (i < n && src.charAt(i) == '\r') i++;
                if (i < n && src.charAt(i) == '\n') i++;
                line++;
                continue;
            }
            if (c == '-' && i + 1 < n && src.charAt(i + 1) == '-') {
                while (i < n && src.charAt(i) != '\r' && src.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '"') {
                int j = i + 1;
                while (j < n && src.charAt(j) != '"') j++;
                out.add(new Tok(T.STRING, src.substring(i + 1, Math.min(j, n)), line));
                i = j + 1; continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)) && (i == 0 || src.charAt(i - 1) != '.'))) {
                int j = i; boolean flt = false;
                while (j < n && Character.isDigit(src.charAt(j))) j++;
                if (j < n && src.charAt(j) == '.' && j + 1 < n && Character.isDigit(src.charAt(j + 1))) {
                    flt = true; j++;
                    while (j < n && Character.isDigit(src.charAt(j))) j++;
                }
                if (j < n && (src.charAt(j) == 'e' || src.charAt(j) == 'E')) {
                    int k = j + 1;
                    if (k < n && (src.charAt(k) == '+' || src.charAt(k) == '-')) k++;
                    if (k < n && Character.isDigit(src.charAt(k))) {
                        flt = true; j = k;
                        while (j < n && Character.isDigit(src.charAt(j))) j++;
                    }
                }
                out.add(new Tok(flt ? T.FLOAT : T.INT, src.substring(i, j), line));
                i = j; continue;
            }
            if (c == '#') {
                int j = i + 1;
                while (j < n && isIdent(src.charAt(j))) j++;
                out.add(new Tok(T.SYMBOL, src.substring(i + 1, j), line));
                i = j; continue;
            }
            if (isIdentStart(c)) {
                int j = i;
                while (j < n && isIdent(src.charAt(j))) j++;
                out.add(new Tok(T.IDENT, src.substring(i, j), line));
                i = j; continue;
            }
            String two = i + 1 < n ? src.substring(i, i + 2) : "";
            if (two.equals("<>") || two.equals("<=") || two.equals(">=") || two.equals("&&")) {
                out.add(new Tok(T.OP, two, line)); i += 2; continue;
            }
            out.add(new Tok(T.OP, String.valueOf(c), line));
            i++;
        }
        out.add(new Tok(T.NL, "\n", line));
        out.add(new Tok(T.EOF, "", line));
        return out;
    }

    static boolean isIdentStart(char c) { return Character.isLetter(c) || c == '_' || c > 127; }
    static boolean isIdent(char c) { return Character.isLetterOrDigit(c) || c == '_' || c > 127; }

    // ================================================================== AST

    static abstract class Node {}
    static abstract class Expr extends Node {}
    static abstract class Stmt extends Node { int line; }

    static final class IntLit extends Expr { final int v; IntLit(int v) { this.v = v; } }
    static final class FloatLit extends Expr { final double v; FloatLit(double v) { this.v = v; } }
    static final class StrLit extends Expr { final String v; StrLit(String v) { this.v = v; } }
    static final class SymLit extends Expr { final String v; SymLit(String v) { this.v = v; } }
    static final class Const extends Expr { final String v; Const(String v) { this.v = v; } }
    static final class Var extends Expr { final String name; Var(String name) { this.name = name; } }
    static final class Call extends Expr { final String name; final List<Expr> args; Call(String n, List<Expr> a) { name = n; args = a; } }
    static final class ObjCall extends Expr { final Expr obj; final String name; final List<Expr> args; ObjCall(Expr o, String n, List<Expr> a) { obj = o; name = n; args = a; } }
    static final class ObjProp extends Expr { final Expr obj; final String name; ObjProp(Expr o, String n) { obj = o; name = n; } }
    static final class Index extends Expr { final Expr obj; final List<Expr> idx; Index(Expr o, List<Expr> i) { obj = o; idx = i; } }
    static final class ListLit extends Expr { final List<Expr> items; ListLit(List<Expr> i) { items = i; } }
    static final class PropListLit extends Expr { final List<Expr> keys, vals; PropListLit(List<Expr> k, List<Expr> v) { keys = k; vals = v; } }
    static final class Binary extends Expr { final String op; final Expr l, r; Binary(String op, Expr l, Expr r) { this.op = op; this.l = l; this.r = r; } }
    static final class Not extends Expr { final Expr e; Not(Expr e) { this.e = e; } }
    static final class Neg extends Expr { final Expr e; Neg(Expr e) { this.e = e; } }
    static final class The extends Expr { final String prop; final Expr of; The(String p, Expr of) { prop = p; this.of = of; } }
    static final class Chunk extends Expr { final String type; final Expr first, last, of; Chunk(String t, Expr f, Expr l, Expr o) { type = t; first = f; last = l; of = o; } }

    static final class Assign extends Stmt { final Expr target, value; Assign(Expr t, Expr v) { target = t; value = v; } }
    static final class ExprStmt extends Stmt { final Expr e; ExprStmt(Expr e) { this.e = e; } }
    static final class If extends Stmt { final Expr cond; final List<Stmt> then; List<Stmt> els; If(Expr c, List<Stmt> t) { cond = c; then = t; } }
    static final class CaseBranch { final List<Expr> labels; final List<Stmt> body; CaseBranch(List<Expr> l, List<Stmt> b) { labels = l; body = b; } }
    static final class Case extends Stmt { final Expr value; final List<CaseBranch> branches = new ArrayList<>(); List<Stmt> otherwise; Case(Expr v) { value = v; } }
    static final class RepeatWhile extends Stmt { final Expr cond; final List<Stmt> body; RepeatWhile(Expr c, List<Stmt> b) { cond = c; body = b; } }
    static final class RepeatTo extends Stmt { final String var; final Expr from, to; final boolean down; final List<Stmt> body; RepeatTo(String v, Expr f, Expr t, boolean d, List<Stmt> b) { var = v; from = f; to = t; down = d; body = b; } }
    static final class RepeatIn extends Stmt { final String var; final Expr list; final List<Stmt> body; RepeatIn(String v, Expr l, List<Stmt> b) { var = v; list = l; body = b; } }
    static final class ExitRepeat extends Stmt {}
    static final class NextRepeat extends Stmt {}
    static final class Exit extends Stmt {}
    static final class Return extends Stmt { final Expr e; Return(Expr e) { this.e = e; } }
    static final class Tell extends Stmt { final Expr target; final List<Stmt> body; Tell(Expr t, List<Stmt> b) { target = t; body = b; } }
    static final class Put extends Stmt { final Expr value; final String mode; final Expr target; Put(Expr v, String m, Expr t) { value = v; mode = m; target = t; } }
    static final class GlobalDecl extends Stmt { final List<String> names; GlobalDecl(List<String> n) { names = n; } }

    static final class HandlerDef { String name; List<String> args = new ArrayList<>(); List<String> globals = new ArrayList<>(); List<Stmt> body; }
    static final class ScriptDef { List<String> properties = new ArrayList<>(); List<String> globals = new ArrayList<>(); List<HandlerDef> handlers = new ArrayList<>(); }

    // ================================================================== parser

    static final class ParseError extends RuntimeException { ParseError(String m) { super(m); } }

    static final class Parser {
        final List<Tok> toks; int p = 0;
        Parser(List<Tok> t) { toks = t; }

        Tok peek() { return toks.get(p); }
        Tok peek(int k) { return toks.get(Math.min(p + k, toks.size() - 1)); }
        Tok next() { return toks.get(p++); }
        boolean at(String w) { return peek().is(w); }
        boolean accept(String w) { if (at(w)) { p++; return true; } return false; }
        Tok expect(String w) { if (!at(w)) throw err("expected '" + w + "'"); return next(); }
        ParseError err(String m) { Tok t = peek(); return new ParseError("line " + t.line + ": " + m + " near '" + t.s + "'"); }
        void skipNl() { while (peek().t == T.NL) p++; }
        void endLine() { if (peek().t != T.NL && peek().t != T.EOF) throw err("expected end of line"); skipNl(); }
        String ident() { Tok t = next(); if (t.t != T.IDENT) throw new ParseError("line " + t.line + ": expected name near '" + t.s + "'"); return t.s; }

        ScriptDef script() {
            ScriptDef s = new ScriptDef();
            skipNl();
            while (peek().t != T.EOF) {
                if (accept("property")) { s.properties.addAll(nameList()); endLine(); }
                else if (accept("global")) { s.globals.addAll(nameList()); endLine(); }
                else if (at("on")) s.handlers.add(handler());
                else throw err("expected 'on', 'property' or 'global'");
            }
            return s;
        }

        List<String> nameList() {
            List<String> l = new ArrayList<>();
            l.add(ident());
            while (accept(",")) l.add(ident());
            return l;
        }

        HandlerDef handler() {
            expect("on");
            HandlerDef h = new HandlerDef();
            h.name = ident();
            if (peek().t == T.IDENT) { h.args.addAll(nameList()); }
            endLine();
            h.body = block("end");
            expect("end");
            if (peek().t == T.IDENT && peek().s.equalsIgnoreCase(h.name)) next();
            endLine();
            return h;
        }

        /** Statements until one of the terminators (not consumed). */
        List<Stmt> block(String... terms) {
            List<Stmt> l = new ArrayList<>();
            while (true) {
                skipNl();
                Tok t = peek();
                if (t.t == T.EOF) throw err("unexpected end of script");
                for (String term : terms) if (isTerm(term)) return l;
                l.add(statement());
            }
        }

        boolean isTerm(String term) {
            switch (term) {
                case "end": return at("end");
                case "else": return at("else");
                case "case-label": return false;
                default: return at(term);
            }
        }

        Stmt statement() {
            int line = peek().line;
            Stmt s = statementInner();
            s.line = line;
            return s;
        }

        Stmt statementInner() {
            if (accept("if")) return ifStmt();
            if (accept("case")) return caseStmt();
            if (accept("repeat")) return repeatStmt();
            if (at("exit") && peek(1).is("repeat")) { next(); next(); endLine(); return new ExitRepeat(); }
            if (at("next") && peek(1).is("repeat")) { next(); next(); endLine(); return new NextRepeat(); }
            if (at("exit") && (peek(1).t == T.NL)) { next(); endLine(); return new Exit(); }
            if (accept("return")) {
                Expr e = (peek().t == T.NL) ? null : expr();
                endLine();
                return new Return(e);
            }
            if (accept("global")) { List<String> n = nameList(); endLine(); return new GlobalDecl(n); }
            if (accept("tell")) {
                Expr target = expr(); endLine();
                List<Stmt> body = block("end");
                expect("end"); expect("tell"); endLine();
                return new Tell(target, body);
            }
            if (at("put") && !peek(1).is("(") ) {
                next();
                Expr v = expr();
                if (accept("into")) { Expr t = expr(); endLine(); return new Put(v, "into", t); }
                if (accept("after")) { Expr t = expr(); endLine(); return new Put(v, "after", t); }
                if (accept("before")) { Expr t = expr(); endLine(); return new Put(v, "before", t); }
                endLine();
                return new Put(v, null, null);
            }
            if (at("set") && peek(1).t == T.IDENT && !peek(1).is("(")) {
                // set x to y / set x = y
                int save = p; next();
                Expr target = postfix();
                if (accept("to") || accept("=")) { Expr v = expr(); endLine(); return new Assign(target, v); }
                p = save;
            }
            // assignment: the target is a postfix expression (so its '=' isn't read as a comparison)
            int save = p;
            Expr target = null;
            try { target = postfix(); } catch (ParseError ignored) { }
            if (target != null && accept("=")) {
                Expr v = expr();
                endLine();
                return new Assign(target, v);
            }
            p = save;
            if (at("go") && !peek(1).is("(")) return goStmt();
            Expr e = expr();
            if (e instanceof Var) {
                // command form: name [arg, arg]
                List<Expr> args = new ArrayList<>();
                if (peek().t != T.NL) {
                    args.add(expr());
                    while (accept(",")) args.add(expr());
                }
                endLine();
                return new ExprStmt(new Call(((Var) e).name, args));
            }
            endLine();
            return new ExprStmt(e);
        }

        /** go [to] [frame] f [of movie m]  /  go [to] movie m */
        Stmt goStmt() {
            expect("go");
            accept("to");
            List<Expr> args = new ArrayList<>();
            if (accept("movie")) { args.add(new IntLit(1)); args.add(expr()); }
            else {
                accept("frame");
                args.add(expr());
                if (accept("of")) { expect("movie"); args.add(expr()); }
            }
            endLine();
            return new ExprStmt(new Call("go", args));
        }

        Stmt ifStmt() {
            Expr cond = expr();
            expect("then");
            if (peek().t != T.NL) {
                // one-line if: if c then stmt [else stmt]
                List<Stmt> then = new ArrayList<>();
                then.add(statementNoEnd());
                If s = new If(cond, then);
                if (accept("else")) { s.els = new ArrayList<>(); s.els.add(statement()); }
                return s;
            }
            skipNl();
            List<Stmt> then = block("end", "else");
            If s = new If(cond, then);
            if (accept("else")) {
                if (at("if") && peek(1).t != T.NL) {
                    // else if ... (chain): the nested if ends with its own 'end if'
                    next();
                    s.els = new ArrayList<>();
                    Stmt inner = ifStmt();
                    s.els.add(inner);
                    return s;
                }
                endLine();
                s.els = block("end");
            }
            expect("end"); expect("if"); endLine();
            return s;
        }

        /** A single statement on the same line as 'then' (stops before 'else'). */
        Stmt statementNoEnd() {
            // find 'else' on this line to cut the statement
            int q = p; int depth = 0;
            while (toks.get(q).t != T.NL && toks.get(q).t != T.EOF) {
                if (toks.get(q).is("(") || toks.get(q).is("[")) depth++;
                if (toks.get(q).is(")") || toks.get(q).is("]")) depth--;
                if (depth == 0 && toks.get(q).is("else")) break;
                q++;
            }
            if (toks.get(q).is("else")) {
                List<Tok> sub = new ArrayList<>(toks.subList(p, q));
                sub.add(new Tok(T.NL, "\n", toks.get(q).line));
                sub.add(new Tok(T.EOF, "", toks.get(q).line));
                Parser inner = new Parser(sub);
                Stmt s = inner.statement();
                p = q;
                return s;
            }
            return statement();
        }

        Stmt caseStmt() {
            Expr v = expr();
            expect("of"); endLine();
            Case c = new Case(v);
            while (true) {
                skipNl();
                if (at("end")) break;
                if (accept("otherwise")) {
                    accept(":");
                    if (peek().t != T.NL) { c.otherwise = new ArrayList<>(); c.otherwise.add(statement()); }
                    else { skipNl(); c.otherwise = block("end"); }
                    break;
                }
                List<Expr> labels = new ArrayList<>();
                labels.add(expr());
                while (accept(",")) { skipNl(); labels.add(expr()); }
                expect(":");
                List<Stmt> body;
                if (peek().t != T.NL) { body = new ArrayList<>(); body.add(statement()); }
                else { skipNl(); body = caseBody(); }
                c.branches.add(new CaseBranch(labels, body));
            }
            expect("end"); expect("case"); endLine();
            return c;
        }

        /** Statements of a case branch: until the next label line, 'otherwise' or 'end case'. */
        List<Stmt> caseBody() {
            List<Stmt> l = new ArrayList<>();
            while (true) {
                skipNl();
                if (at("end") || at("otherwise") || looksLikeLabel()) return l;
                l.add(statement());
            }
        }

        boolean looksLikeLabel() {
            // a label line is "expr[, expr]:" followed by end of line or a statement
            int q = p, depth = 0;
            while (toks.get(q).t != T.NL && toks.get(q).t != T.EOF) {
                Tok t = toks.get(q);
                if (t.is("(") || t.is("[")) depth++;
                else if (t.is(")") || t.is("]")) depth--;
                else if (depth == 0 && t.is(":")) return true;
                else if (depth == 0 && t.is("=") ) return false;
                q++;
            }
            return false;
        }

        Stmt repeatStmt() {
            if (accept("while")) {
                Expr c = expr(); endLine();
                List<Stmt> b = block("end"); expect("end"); expect("repeat"); endLine();
                return new RepeatWhile(c, b);
            }
            expect("with");
            String v = ident();
            if (accept("in")) {
                Expr l = expr(); endLine();
                List<Stmt> b = block("end"); expect("end"); expect("repeat"); endLine();
                return new RepeatIn(v, l, b);
            }
            expect("=");
            Expr from = expr();
            boolean down = false;
            if (accept("down")) down = true;
            expect("to");
            Expr to = expr(); endLine();
            List<Stmt> b = block("end"); expect("end"); expect("repeat"); endLine();
            return new RepeatTo(v, from, to, down, b);
        }

        // ---------------- expressions

        static final Map<String, Integer> PREC = new HashMap<>();
        static {
            PREC.put("or", 6); PREC.put("and", 5);
            for (String o : new String[] {"=", "<>", "<", "<=", ">", ">=", "contains", "starts"}) PREC.put(o, 4);
            PREC.put("&", 3); PREC.put("&&", 3);
            PREC.put("+", 2); PREC.put("-", 2);
            PREC.put("*", 1); PREC.put("/", 1); PREC.put("mod", 1);
        }

        Expr expr() { return binary(6); }

        String binOp() {
            Tok t = peek();
            if (t.t == T.OP || t.t == T.IDENT) {
                String s = t.s.toLowerCase();
                if (PREC.containsKey(s)) return s;
            }
            return null;
        }

        Expr binary(int level) {
            if (level == 0) return unary();
            Expr l = binary(level - 1);
            while (true) {
                String op = binOp();
                if (op == null || PREC.get(op) != level) return l;
                next();
                Expr r = binary(level - 1);
                l = new Binary(op, l, r);
            }
        }

        Expr unary() {
            if (accept("not")) return new Not(unary());
            if (at("-")) { next(); return new Neg(unary()); }
            return postfix();
        }

        Expr postfix() {
            Expr e = primary();
            while (true) {
                if (at(".") && peek(1).t == T.IDENT) {
                    next();
                    String name = ident();
                    if (accept("(")) e = new ObjCall(e, name, args(")"));
                    else e = new ObjProp(e, name);
                } else if (at("[")) {
                    next();
                    List<Expr> idx = new ArrayList<>();
                    idx.add(expr());
                    if (at(".") && peek(1).is(".")) {
                        // range: x.char[a..b]
                        next(); next();
                        idx.add(expr());
                    } else {
                        while (accept(",")) idx.add(expr());
                    }
                    expect("]");
                    e = new Index(e, idx);
                } else return e;
            }
        }

        List<Expr> args(String close) {
            List<Expr> a = new ArrayList<>();
            if (accept(close)) return a;
            a.add(expr());
            while (accept(",")) a.add(expr());
            expect(close);
            return a;
        }

        static final Set<String> CONSTS = new HashSet<>(Arrays.asList(
            "void", "true", "false", "empty", "return", "quote", "tab", "space", "enter", "backspace", "pi"));
        static final Set<String> CHUNKS = new HashSet<>(Arrays.asList("char", "word", "item", "line"));

        Expr primary() {
            Tok t = next();
            switch (t.t) {
                case INT: return new IntLit((int) Long.parseLong(t.s));
                case FLOAT: return new FloatLit(Double.parseDouble(t.s));
                case STRING: return new StrLit(t.s);
                case SYMBOL: return new SymLit(t.s);
                case OP:
                    if (t.s.equals("(")) { Expr e = expr(); expect(")"); return e; }
                    if (t.s.equals("[")) return listLit();
                    break;
                case IDENT: {
                    String w = t.s.toLowerCase();
                    if (w.equals("the") && peek().t == T.IDENT) {
                        String prop = ident();
                        if (prop.equalsIgnoreCase("number") && at("of") && peek(1).t == T.IDENT
                                && ANIM2_PROPS.containsKey("number of " + peek(1).s.toLowerCase())) {
                            next();
                            String what = ident();
                            Expr lib = null;
                            if (what.equalsIgnoreCase("castMembers") && at("of") && peek(1).is("castLib")) { next(); next(); lib = unary(); }
                            return new The("number of " + what, lib);
                        }
                        Expr of = null;
                        if (accept("of")) of = unary();
                        return new The(prop, of);
                    }
                    if (CHUNKS.contains(w) && !at("(") && !at(".") ) {
                        Expr first = expr();
                        Expr last = null;
                        if (accept("to")) last = expr();
                        expect("of");
                        Expr of = unary();
                        return new Chunk(w, first, last, of);
                    }
                    if (CONSTS.contains(w) && !at("(")) return new Const(w);
                    if (at("(")) { next(); return new Call(t.s, args(")")); }
                    return new Var(t.s);
                }
                default: break;
            }
            throw new ParseError("line " + t.line + ": unexpected '" + t.s + "'");
        }

        Expr listLit() {
            if (accept("]")) return new ListLit(new ArrayList<>());
            if (at(":") && peek(1).is("]")) { next(); next(); return new PropListLit(new ArrayList<>(), new ArrayList<>()); }
            Expr first = expr();
            if (accept(":")) {
                List<Expr> k = new ArrayList<>(), v = new ArrayList<>();
                k.add(first); v.add(expr());
                while (accept(",")) { k.add(expr()); expect(":"); v.add(expr()); }
                expect("]");
                return new PropListLit(k, v);
            }
            List<Expr> items = new ArrayList<>();
            items.add(first);
            while (accept(",")) items.add(expr());
            expect("]");
            return new ListLit(items);
        }
    }

    public static ScriptDef parse(String source) { return new Parser(lex(source)).script(); }

    // ================================================================== code generation

    /** Resolves names to ids in the context's shared name table (Lnam), adding new ones. */
    static final class Names {
        final List<String> list;
        final Map<String, List<Integer>> byLower = new HashMap<>();
        Set<Integer> preferred = Collections.emptySet(); // ids the original script used (for exact round trips)
        // Director writes name operands 2 bytes wide when the name was new to the names table
        // while that script was compiled, even if the id fits in a byte. Names from here on count as new.
        int newFrom;
        Names(List<String> names) {
            list = new ArrayList<>(names);
            newFrom = list.size();
            for (int i = 0; i < list.size(); i++) byLower.computeIfAbsent(list.get(i).toLowerCase(), k -> new ArrayList<>()).add(i);
        }
        int id(String name) {
            List<Integer> c = byLower.get(name.toLowerCase());
            if (c != null) {
                for (int i : c) if (preferred.contains(i) && list.get(i).equals(name)) return i;
                for (int i : c) if (preferred.contains(i)) return i;
                for (int i : c) if (list.get(i).equals(name)) return i;
                return c.get(0);
            }
            list.add(name);
            byLower.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>()).add(list.size() - 1);
            return list.size() - 1;
        }
    }

    static final class CompiledHandler {
        String name; int nameId;
        byte[] bytecode;
        List<Integer> argNames = new ArrayList<>(), localNames = new ArrayList<>(), globalNames = new ArrayList<>();
    }

    static final class CompiledScript {
        List<Integer> propertyNames = new ArrayList<>(), globalNames = new ArrayList<>();
        List<CompiledHandler> handlers = new ArrayList<>();
        List<LingoScript.Literal> literals = new ArrayList<>();
    }

    // opcodes (multi-byte ones are the base 0x40-0x7f value)
    static final int RET = 0x01, PUSHZERO = 0x03, MUL = 0x04, ADD = 0x05, SUB = 0x06, DIV = 0x07, MOD = 0x08,
        INV = 0x09, JOINSTR = 0x0a, JOINPADSTR = 0x0b, LT = 0x0c, LTEQ = 0x0d, NTEQ = 0x0e, EQ = 0x0f, GT = 0x10,
        GTEQ = 0x11, AND = 0x12, OR = 0x13, NOT = 0x14, CONTAINSSTR = 0x15, CONTAINS0STR = 0x16, GETCHUNK = 0x17, STARTTELL = 0x1c,
        ENDTELL = 0x1d, PUSHLIST = 0x1e, PUSHPROPLIST = 0x1f;
    static final int PUSHINT8 = 0x41, PUSHARGLISTNORET = 0x42, PUSHARGLIST = 0x43, PUSHCONS = 0x44, PUSHSYMB = 0x45,
        GETGLOBAL = 0x49, GETPROP = 0x4a, GETPARAM = 0x4b, GETLOCAL = 0x4c, SETGLOBAL = 0x4f, SETPROP = 0x50,
        SETPARAM = 0x51, SETLOCAL = 0x52, JMP = 0x53, ENDREPEAT = 0x54, JMPIFZ = 0x55, LOCALCALL = 0x56,
        EXTCALL = 0x57, PUT = 0x59, PUTCHUNK = 0x5a, GET = 0x5c, SET = 0x5d, GETMOVIEPROP = 0x5f, SETMOVIEPROP = 0x60,
        GETOBJPROP = 0x61, SETOBJPROP = 0x62, TELLCALL = 0x63, PEEK = 0x64, POP = 0x65, THEBUILTIN = 0x66,
        OBJCALL = 0x67, PUSHINT32 = 0x6f;

    /** Opcodes whose operand Director widens to 2 bytes from 32 up. */
    static final Set<Integer> SMALL_OPS = new HashSet<>(Arrays.asList(PUSHARGLIST, PUSHARGLISTNORET, ENDREPEAT, PUT, PUTCHUNK));

    /** Opcodes whose operand is a name id. */
    static final Set<Integer> NAME_OPS = new HashSet<>(Arrays.asList(PUSHSYMB, GETGLOBAL, SETGLOBAL, GETPROP, SETPROP,
        EXTCALL, OBJCALL, GETMOVIEPROP, SETMOVIEPROP, GETOBJPROP, SETOBJPROP, TELLCALL, THEBUILTIN));

    /** Movie properties read and written by number (get/set 7), numbered as in Director. */
    static final Map<String, Integer> ANIM_PROPS = new HashMap<>();
    /** Counts read by number (get 8). */
    static final Map<String, Integer> ANIM2_PROPS = new HashMap<>();
    static {
        String[] anim = {null, "beepOn", "buttonStyle", "centerStage", "checkBoxAccess", "checkboxType", "colorDepth",
            "colorQD", "exitLock", "fixStageSize", "fullColorPermit", "imageDirect", "doubleClick", "key", "lastClick",
            "lastEvent", "keyCode", "lastKey", "lastRoll", "timeoutLapsed", "multiSound", "pauseState",
            "quickTimePresent", "selEnd", "selStart", "soundEnabled", "soundLevel", "stageColor", null,
            "switchColorDepth", "timeoutKeyDown", "timeoutLength", "timeoutMouse", "timeoutPlay", "timer",
            "preLoadRAM", "videoForWindowsPresent", "netPresent", "safePlayer", "soundKeepDevice", "soundMixMedia"};
        for (int i = 0; i < anim.length; i++) if (anim[i] != null) ANIM_PROPS.put(anim[i].toLowerCase(), i);
        String[] anim2 = {null, "perFrameHook", "number of castMembers", "number of menus", "number of castLibs", "number of xtras"};
        for (int i = 0; i < anim2.length; i++) if (anim2[i] != null) ANIM2_PROPS.put(anim2[i].toLowerCase(), i);
    }

    /** Built-in functions that are called as such even where the script defines a handler with the name. */
    static final Set<String> BUILTIN_CALLS = new HashSet<>(Arrays.asList("new"));

    static final Set<String> THE_BUILTINS = new HashSet<>(Arrays.asList(
        "frame", "ticks", "stagetop", "stagebottom", "stageleft", "stageright", "key", "keycode", "mouseh", "mousev",
        "clickon", "rollover", "timer", "lastclick", "lastevent", "lastkey", "lastroll", "commanddown", "controldown",
        "optiondown", "shiftdown", "mousedown", "mouseup", "stillDown".toLowerCase()));

    static final class CodeGen {
        final Names names;
        // Director 8 (Lctx) multiplies variable and literal numbers by 8 and writes every pushint8 2 bytes
        // wide; 8.5 and later (LctX) use plain indexes and the smallest width.
        final boolean d8; final int mul;
        final CompiledScript out = new CompiledScript();
        ScriptDef script;
        Set<String> props = new HashSet<>(), scriptGlobals = new HashSet<>();

        // per handler
        ByteArrayOutputStream code;
        HandlerDef current;
        List<String> args, locals;
        Set<String> globals;
        Deque<Loop> loops = new ArrayDeque<>();
        boolean inTell;
        int stackItems; // values case and repeat-in statements keep on the stack

        static final class Loop { int nextTarget = -1; List<Integer> exitPatches = new ArrayList<>(), nextPatches = new ArrayList<>(); }

        CodeGen(Names names, boolean d8) { this.names = names; this.d8 = d8; mul = d8 ? 8 : 1; }

        CompiledScript compile(ScriptDef s) {
            script = s;
            for (String p : s.properties) { props.add(p.toLowerCase()); out.propertyNames.add(names.id(p)); }
            for (String g : s.globals) { scriptGlobals.add(g.toLowerCase()); out.globalNames.add(names.id(g)); }
            for (HandlerDef h : s.handlers) out.handlers.add(handler(h));
            return out;
        }

        // ---------------- emission

        void op(int op) { code.write(op); }

        /**
         * Operand width: forward jumps and pushint8 always use 2 bytes, and so do names new to this
         * script. Argument counts, loop-back distances and put modes switch to 2 bytes from 32 up.
         * Everything else uses the smallest width that fits.
         */
        void op(int base, int arg) {
            boolean forceWide = base == JMP || base == JMPIFZ || (base == PUSHINT8 && d8)
                || (NAME_OPS.contains(base) && arg >= names.newFrom)
                || (SMALL_OPS.contains(base) && arg >= 32);
            if (!forceWide && arg >= 0 && arg <= 0xff) { code.write(base); code.write(arg); }
            else if (arg >= -0x8000 && arg <= 0xffff && !(base != PUSHINT8 && arg < 0)) {
                code.write(base + 0x40); code.write((arg >> 8) & 0xff); code.write(arg & 0xff);
            } else {
                code.write(base + 0x80);
                code.write((arg >>> 24) & 0xff); code.write((arg >>> 16) & 0xff); code.write((arg >>> 8) & 0xff); code.write(arg & 0xff);
            }
        }

        void opNarrow(int base, int arg) { code.write(base); code.write(arg); }

        int pos() { return code.size(); }

        /** Emits a 2-byte jump; returns its position for patching. */
        int jump(int base) { int at = pos(); op(base, 0); return at; }

        void patch(int at, int target) {
            byte[] b = code.toByteArray();
            int rel = target - at;
            b[at + 1] = (byte) (rel >> 8); b[at + 2] = (byte) rel;
            code.reset(); code.write(b, 0, b.length);
        }

        /** Each use of a constant gets its own literal; Director doesn't pool them. */
        int literal(LingoScript.Literal l) {
            out.literals.add(l);
            return (out.literals.size() - 1) * mul;
        }

        int stringLiteral(String s) { LingoScript.Literal l = new LingoScript.Literal(); l.type = 1; l.string = s; return literal(l); }

        // ---------------- handlers

        CompiledHandler handler(HandlerDef h) {
            code = new ByteArrayOutputStream();
            current = h;
            args = h.args; locals = new ArrayList<>(); globals = new HashSet<>(scriptGlobals);
            List<String> handlerGlobals = new ArrayList<>();
            collectGlobals(h.body, handlerGlobals);
            for (String g : handlerGlobals) globals.add(g.toLowerCase());
            collectLocals(h.body);
            loops.clear(); inTell = false; stackItems = 0;
            block(h.body);
            op(RET);
            CompiledHandler c = new CompiledHandler();
            c.name = h.name; c.nameId = names.id(h.name);
            c.bytecode = code.toByteArray();
            for (String a : args) c.argNames.add(names.id(a));
            for (String l : locals) c.localNames.add(names.id(l));
            for (String g : handlerGlobals) c.globalNames.add(names.id(g));
            return c;
        }

        void collectGlobals(List<Stmt> body, List<String> out) {
            for (Stmt s : body) {
                if (s instanceof GlobalDecl) for (String n : ((GlobalDecl) s).names) if (!containsIgnoreCase(out, n)) out.add(n);
                for (List<Stmt> sub : children(s)) collectGlobals(sub, out);
            }
        }

        static boolean containsIgnoreCase(List<String> l, String s) { for (String x : l) if (x.equalsIgnoreCase(s)) return true; return false; }

        static List<List<Stmt>> children(Stmt s) {
            List<List<Stmt>> l = new ArrayList<>();
            if (s instanceof If) { l.add(((If) s).then); if (((If) s).els != null) l.add(((If) s).els); }
            else if (s instanceof Case) { for (CaseBranch b : ((Case) s).branches) l.add(b.body); if (((Case) s).otherwise != null) l.add(((Case) s).otherwise); }
            else if (s instanceof RepeatWhile) l.add(((RepeatWhile) s).body);
            else if (s instanceof RepeatTo) l.add(((RepeatTo) s).body);
            else if (s instanceof RepeatIn) l.add(((RepeatIn) s).body);
            else if (s instanceof Tell) l.add(((Tell) s).body);
            return l;
        }

        boolean isLocalCandidate(String n) {
            String k = n.toLowerCase();
            return !containsIgnoreCase(args, n) && !props.contains(k) && !globals.contains(k);
        }

        void addLocal(String n) { if (isLocalCandidate(n) && !containsIgnoreCase(locals, n)) locals.add(n); }

        /** Locals in the order their names first appear in the source. */
        void collectLocals(List<Stmt> body) {
            for (Stmt s : body) {
                if (s instanceof Assign) { exprLocals(((Assign) s).target); exprLocals(((Assign) s).value); }
                else if (s instanceof ExprStmt) exprLocals(((ExprStmt) s).e);
                else if (s instanceof If) exprLocals(((If) s).cond);
                else if (s instanceof Case) { exprLocals(((Case) s).value); }
                else if (s instanceof RepeatWhile) exprLocals(((RepeatWhile) s).cond);
                else if (s instanceof RepeatTo) { addLocal(((RepeatTo) s).var); exprLocals(((RepeatTo) s).from); exprLocals(((RepeatTo) s).to); }
                else if (s instanceof RepeatIn) { addLocal(((RepeatIn) s).var); exprLocals(((RepeatIn) s).list); }
                else if (s instanceof Return && ((Return) s).e != null) exprLocals(((Return) s).e);
                else if (s instanceof Tell) exprLocals(((Tell) s).target);
                else if (s instanceof Put) { exprLocals(((Put) s).value); if (((Put) s).target != null) exprLocals(((Put) s).target); }
                if (s instanceof Case) {
                    for (CaseBranch b : ((Case) s).branches) { for (Expr e : b.labels) exprLocals(e); collectLocals(b.body); }
                    if (((Case) s).otherwise != null) collectLocals(((Case) s).otherwise);
                } else for (List<Stmt> sub : children(s)) collectLocals(sub);
            }
        }

        void exprLocals(Expr e) {
            if (e instanceof Var) addLocal(((Var) e).name);
            else if (e instanceof Call) for (Expr a : ((Call) e).args) exprLocals(a);
            else if (e instanceof ObjCall) { exprLocals(((ObjCall) e).obj); for (Expr a : ((ObjCall) e).args) exprLocals(a); }
            else if (e instanceof ObjProp) exprLocals(((ObjProp) e).obj);
            else if (e instanceof Index) { exprLocals(((Index) e).obj); for (Expr a : ((Index) e).idx) exprLocals(a); }
            else if (e instanceof ListLit) for (Expr a : ((ListLit) e).items) exprLocals(a);
            else if (e instanceof PropListLit) { for (int i = 0; i < ((PropListLit) e).keys.size(); i++) { exprLocals(((PropListLit) e).keys.get(i)); exprLocals(((PropListLit) e).vals.get(i)); } }
            else if (e instanceof Binary) { exprLocals(((Binary) e).l); exprLocals(((Binary) e).r); }
            else if (e instanceof Not) exprLocals(((Not) e).e);
            else if (e instanceof Neg) exprLocals(((Neg) e).e);
            else if (e instanceof The && ((The) e).of != null) exprLocals(((The) e).of);
            else if (e instanceof Chunk) { exprLocals(((Chunk) e).first); if (((Chunk) e).last != null) exprLocals(((Chunk) e).last); exprLocals(((Chunk) e).of); }
        }

        // ---------------- statements

        void block(List<Stmt> body) { for (Stmt s : body) stmt(s); }

        void stmt(Stmt s) {
            if (s instanceof Assign) assign((Assign) s);
            else if (s instanceof ExprStmt) exprStmt(((ExprStmt) s).e);
            else if (s instanceof If) ifStmt((If) s);
            else if (s instanceof Case) caseStmt((Case) s);
            else if (s instanceof RepeatWhile) repeatWhile((RepeatWhile) s);
            else if (s instanceof RepeatTo) repeatTo((RepeatTo) s);
            else if (s instanceof RepeatIn) repeatIn((RepeatIn) s);
            else if (s instanceof ExitRepeat) loops.peek().exitPatches.add(jump(JMP));
            else if (s instanceof NextRepeat) loops.peek().nextPatches.add(jump(JMP));
            else if (s instanceof Exit) op(RET);
            else if (s instanceof Return) returnStmt((Return) s);
            else if (s instanceof Tell) tell((Tell) s);
            else if (s instanceof Put) put((Put) s);
            else if (s instanceof GlobalDecl) { /* no code */ }
            else throw new ParseError("line " + s.line + ": cannot compile " + s.getClass().getSimpleName());
        }

        void assign(Assign a) {
            Expr t = a.target;
            if (t instanceof Var) { expr(a.value); setVar(((Var) t).name); return; }
            if (t instanceof ObjProp) { expr(((ObjProp) t).obj); expr(a.value); op(SETOBJPROP, names.id(((ObjProp) t).name)); return; }
            if (t instanceof The && ((The) t).of == null && ANIM_PROPS.containsKey(((The) t).prop.toLowerCase())) {
                expr(a.value); op(PUSHINT8, ANIM_PROPS.get(((The) t).prop.toLowerCase())); op(SET, 7); return;
            }
            if (t instanceof The && ((The) t).of != null) {
                // the prop of obj = v  ==  obj.prop = v
                expr(((The) t).of); expr(a.value); op(SETOBJPROP, names.id(((The) t).prop)); return;
            }
            if (t instanceof The && ((The) t).of == null) { expr(a.value); op(SETMOVIEPROP, names.id(((The) t).prop)); return; }
            if (t instanceof Index) {
                Index ix = (Index) t;
                if (ix.obj instanceof ObjProp && ix.idx.size() == 1) {
                    expr(((ObjProp) ix.obj).obj); op(PUSHSYMB, names.id(((ObjProp) ix.obj).name)); expr(ix.idx.get(0)); expr(a.value);
                    op(PUSHARGLIST, 4); op(OBJCALL, names.id("setProp")); // Director keeps the return slot here
                } else {
                    indexBase(ix.obj); for (Expr i : ix.idx) expr(i); expr(a.value);
                    op(PUSHARGLISTNORET, ix.idx.size() + 2); op(OBJCALL, names.id("setAt"));
                }
                return;
            }
            throw new ParseError("line " + a.line + ": cannot assign to this expression");
        }

        void exprStmt(Expr e) {
            if (e instanceof Call) { call((Call) e, true); return; }
            if (e instanceof ObjCall) { objCall((ObjCall) e, true); return; }
            expr(e); // e.g. a bare value; Director leaves it on the stack
        }

        void ifStmt(If s) {
            expr(s.cond);
            int jz = jump(JMPIFZ);
            block(s.then);
            if (s.els != null) {
                int j = jump(JMP);
                patch(jz, pos());
                block(s.els);
                patch(j, pos());
            } else patch(jz, pos());
        }

        void caseStmt(Case c) {
            expr(c.value);
            stackItems++;
            List<Integer> toEnd = new ArrayList<>();
            for (CaseBranch b : c.branches) {
                List<Integer> toBody = new ArrayList<>();
                int jz = -1;
                for (int i = 0; i < b.labels.size(); i++) {
                    op(PEEK, 0);
                    expr(b.labels.get(i));
                    if (i < b.labels.size() - 1) {
                        // more labels: straight into the body when this one matches
                        op(NTEQ);
                        toBody.add(jump(JMPIFZ));
                    } else { op(EQ); jz = jump(JMPIFZ); }
                }
                int bodyStart = pos();
                for (int j : toBody) patch(j, bodyStart);
                block(b.body);
                // the last branch falls through to the end unless an otherwise follows
                if (b != c.branches.get(c.branches.size() - 1) || c.otherwise != null) toEnd.add(jump(JMP));
                patch(jz, pos());
            }
            if (c.otherwise != null) block(c.otherwise);
            stackItems--;
            int end = pos();
            for (int j : toEnd) patch(j, end);
            op(POP, 1);
        }

        void repeatWhile(RepeatWhile r) {
            Loop loop = new Loop(); loops.push(loop);
            int start = pos();
            expr(r.cond);
            int jz = jump(JMPIFZ);
            block(r.body);
            loop.nextTarget = pos();
            int er = pos(); op(ENDREPEAT, er - start);
            int end = pos();
            patch(jz, end);
            finishLoop(loop, end);
        }

        void repeatTo(RepeatTo r) {
            Loop loop = new Loop(); loops.push(loop);
            expr(r.from);
            setVar(r.var);
            int cond = pos();
            getVar(r.var);
            expr(r.to);
            op(r.down ? GTEQ : LTEQ);
            int jz = jump(JMPIFZ);
            block(r.body);
            loop.nextTarget = pos();
            op(PUSHINT8, r.down ? -1 : 1);
            getVar(r.var);
            op(ADD);
            setVar(r.var);
            int er = pos(); op(ENDREPEAT, er - cond);
            int end = pos();
            patch(jz, end);
            finishLoop(loop, end);
        }

        void repeatIn(RepeatIn r) {
            Loop loop = new Loop(); loops.push(loop);
            expr(r.list);
            op(PEEK, 0); op(PUSHARGLIST, 1); op(EXTCALL, names.id("count"));
            opNarrow(PUSHINT8, 1); // repeat-in writes its index constants 1 byte wide
            int cond = pos();
            op(PEEK, 0); op(PEEK, 2); op(LTEQ);
            int jz = jump(JMPIFZ);
            op(PEEK, 2); op(PEEK, 1); op(PUSHARGLIST, 2); op(EXTCALL, names.id("getAt"));
            setVar(r.var);
            stackItems += 3; block(r.body); stackItems -= 3;
            loop.nextTarget = pos();
            opNarrow(PUSHINT8, 1); op(ADD);
            int er = pos(); op(ENDREPEAT, er - cond);
            int end = pos();
            patch(jz, end);
            op(POP, 3);
            finishLoop(loop, end);
        }

        void finishLoop(Loop loop, int end) {
            loops.pop();
            for (int j : loop.exitPatches) patch(j, end);
            for (int j : loop.nextPatches) patch(j, loop.nextTarget);
        }

        void returnStmt(Return r) {
            if (r.e == null) {
                // a bare return first drops what enclosing case and repeat-in statements keep on the stack
                if (stackItems > 0) op(POP, stackItems);
                op(PUSHARGLISTNORET, 0); op(EXTCALL, names.id("return"));
                return;
            }
            expr(r.e);
            op(PUSHARGLISTNORET, 1);
            op(EXTCALL, names.id("return"));
        }

        void tell(Tell t) {
            expr(t.target);
            op(STARTTELL);
            boolean was = inTell; inTell = true;
            block(t.body);
            inTell = was;
            op(ENDTELL);
        }

        void put(Put p) {
            if (p.target == null) { expr(p.value); op(PUSHARGLISTNORET, 1); op(EXTCALL, names.id("put")); return; }
            // put value into/after/before variable, or a chunk of one: put x into char 3 of line 2 of s
            int mode = p.mode.equals("into") ? 1 : p.mode.equals("after") ? 2 : 3;
            Expr t = p.target;
            Map<String, Chunk> chunks = new HashMap<>();
            while (t instanceof Chunk) { chunks.put(((Chunk) t).type, (Chunk) t); t = ((Chunk) t).of; }
            if (!(t instanceof Var)) throw new ParseError("line " + p.line + ": unsupported put target");
            String n = ((Var) t).name;
            // variable types as Director numbers them; a local is written as its plain index, a parameter
            // as index * 8 like getparam (in D8) (ProjectorRays 0.2.0 divides both by 8, which misnames locals)
            int varType, varId;
            if (props.contains(n.toLowerCase())) { varType = 3; varId = names.id(n); }
            else if (containsIgnoreCase(args, n)) { varType = 4; varId = indexOf(args, n) * mul; }
            else if (globals.contains(n.toLowerCase())) { varType = 1; varId = names.id(n); }
            else { addLocal(n); varType = 5; varId = indexOf(locals, n); }
            expr(p.value);
            if (!chunks.isEmpty()) {
                for (String type : new String[] {"char", "word", "item", "line"}) {
                    Chunk c = chunks.get(type);
                    if (c == null) { op(PUSHZERO); op(PUSHZERO); continue; }
                    expr(c.first);
                    if (c.last == null) op(PUSHZERO); else expr(c.last);
                }
            }
            op(PUSHINT8, varId);
            op(chunks.isEmpty() ? PUT : PUTCHUNK, (mode << 4) | varType);
        }

        static int indexOf(List<String> l, String s) { for (int i = 0; i < l.size(); i++) if (l.get(i).equalsIgnoreCase(s)) return i; return -1; }

        // ---------------- variables

        void getVar(String n) {
            int i;
            if (props.contains(n.toLowerCase())) op(GETPROP, names.id(n)); // a property shadows a parameter of the same name
            else if ((i = indexOf(args, n)) >= 0) op(GETPARAM, i * mul);
            else if (globals.contains(n.toLowerCase())) op(GETGLOBAL, names.id(n));
            else if ((i = indexOf(locals, n)) >= 0) op(GETLOCAL, i * mul);
            else { addLocal(n); op(GETLOCAL, indexOf(locals, n) * mul); }
        }

        void setVar(String n) {
            int i;
            if (props.contains(n.toLowerCase())) op(SETPROP, names.id(n));
            else if ((i = indexOf(args, n)) >= 0) op(SETPARAM, i * mul);
            else if (globals.contains(n.toLowerCase())) op(SETGLOBAL, names.id(n));
            else { addLocal(n); op(SETLOCAL, indexOf(locals, n) * mul); }
        }

        // ---------------- expressions

        void expr(Expr e) {
            if (e instanceof IntLit) intLit(((IntLit) e).v);
            else if (e instanceof FloatLit) { LingoScript.Literal l = new LingoScript.Literal(); l.type = 9; l.floatValue = ((FloatLit) e).v; op(PUSHCONS, literal(l)); }
            else if (e instanceof StrLit) op(PUSHCONS, stringLiteral(((StrLit) e).v));
            else if (e instanceof SymLit) op(PUSHSYMB, names.id(((SymLit) e).v));
            else if (e instanceof Const) constant(((Const) e).v);
            else if (e instanceof Var) getVar(((Var) e).name);
            else if (e instanceof Call) call((Call) e, false);
            else if (e instanceof ObjCall) objCall((ObjCall) e, false);
            else if (e instanceof ObjProp) objProp((ObjProp) e);
            else if (e instanceof Index) index((Index) e);
            else if (e instanceof ListLit) { for (Expr i : ((ListLit) e).items) expr(i); op(PUSHARGLIST, ((ListLit) e).items.size()); op(PUSHLIST); }
            else if (e instanceof PropListLit) {
                PropListLit pl = (PropListLit) e;
                for (int i = 0; i < pl.keys.size(); i++) { expr(pl.keys.get(i)); expr(pl.vals.get(i)); }
                op(PUSHARGLIST, pl.keys.size() * 2); op(PUSHPROPLIST);
            }
            else if (e instanceof Binary) binary((Binary) e);
            else if (e instanceof Not) { expr(((Not) e).e); op(NOT); }
            else if (e instanceof Neg) { expr(((Neg) e).e); op(INV); }
            else if (e instanceof The) the((The) e);
            else if (e instanceof Chunk) chunk((Chunk) e);
            else throw new ParseError("cannot compile expression " + e.getClass().getSimpleName());
        }

        void intLit(int v) {
            if (v == 0) op(PUSHZERO);
            else if (v >= -0x8000 && v <= 0x7fff) op(PUSHINT8, v);
            else { LingoScript.Literal l = new LingoScript.Literal(); l.type = 4; l.intValue = v; op(PUSHCONS, literal(l)); }
        }

        void constant(String c) {
            switch (c) {
                case "true": op(PUSHINT8, 1); break;
                case "false": op(PUSHZERO); break;
                case "void": op(PUSHARGLIST, 0); op(EXTCALL, names.id("VOID")); break;
                case "empty": op(PUSHCONS, stringLiteral("")); break;
                case "return": op(PUSHCONS, stringLiteral("\r")); break;
                case "quote": op(PUSHCONS, stringLiteral("\"")); break;
                case "tab": op(PUSHCONS, stringLiteral("\t")); break;
                case "space": op(PUSHCONS, stringLiteral(" ")); break;
                case "enter": op(PUSHCONS, stringLiteral("\u0003")); break;
                case "backspace": op(PUSHCONS, stringLiteral("\b")); break;
                case "pi": { LingoScript.Literal l = new LingoScript.Literal(); l.type = 9; l.floatValue = Math.PI; op(PUSHCONS, literal(l)); break; }
                default: throw new ParseError("unknown constant " + c);
            }
        }

        boolean isLocalHandler(String name) {
            for (HandlerDef h : script.handlers) if (h.name.equalsIgnoreCase(name)) return true;
            return false;
        }

        int handlerIndex(String name) {
            for (int i = 0; i < script.handlers.size(); i++) if (script.handlers.get(i).name.equalsIgnoreCase(name)) return i;
            return -1;
        }

        void call(Call c, boolean statement) {
            for (Expr a : c.args) expr(a);
            op(statement ? PUSHARGLISTNORET : PUSHARGLIST, c.args.size());
            if (inTell) op(TELLCALL, names.id(c.name));
            // built-in functions win over a handler of the same name (new() inside a script with on new)
            else if (isLocalHandler(c.name) && !BUILTIN_CALLS.contains(c.name.toLowerCase())) op(LOCALCALL, handlerIndex(c.name));
            else op(EXTCALL, names.id(c.name));
        }

        void objCall(ObjCall c, boolean statement) {
            indexBase(c.obj); // obj.list[i].method() calls through a reference
            for (Expr a : c.args) expr(a);
            op(statement ? PUSHARGLISTNORET : PUSHARGLIST, c.args.size() + 1);
            op(OBJCALL, names.id(c.name));
        }

        /** char/word/item/line a [to b] of ...: the eight bounds (0 when unused), the string, getchunk. */
        void chunk(Chunk c) {
            Map<String, Chunk> chunks = new HashMap<>();
            Expr t = c;
            while (t instanceof Chunk) { chunks.put(((Chunk) t).type, (Chunk) t); t = ((Chunk) t).of; }
            for (String type : new String[] {"char", "word", "item", "line"}) {
                Chunk k = chunks.get(type);
                if (k == null) { op(PUSHZERO); op(PUSHZERO); continue; }
                expr(k.first);
                if (k.last == null) op(PUSHZERO); else expr(k.last);
            }
            expr(t);
            op(GETCHUNK);
        }

        void objProp(ObjProp p) {
            if (p.name.equalsIgnoreCase("count") && p.obj instanceof ObjProp) {
                // obj.prop.count is count(obj, #prop)
                ObjProp inner = (ObjProp) p.obj;
                expr(inner.obj); op(PUSHSYMB, names.id(inner.name)); op(PUSHARGLIST, 2); op(OBJCALL, names.id(p.name));
                return;
            }
            expr(p.obj);
            op(GETOBJPROP, names.id(p.name));
        }

        void index(Index ix) { index(ix, false); }

        /** The list being indexed; an obj.prop[i] there is fetched by reference (getPropRef). */
        void indexBase(Expr e) {
            if (e instanceof Index) index((Index) e, true);
            else expr(e);
        }

        void index(Index ix, boolean ref) {
            if (ix.obj instanceof ObjProp && ix.idx.size() <= 2) {
                // obj.prop[i] and obj.prop[a..b]: obj, #prop, bounds
                expr(((ObjProp) ix.obj).obj);
                op(PUSHSYMB, names.id(((ObjProp) ix.obj).name));
                for (Expr i : ix.idx) expr(i);
                op(PUSHARGLIST, 2 + ix.idx.size());
                op(OBJCALL, names.id(ref ? "getPropRef" : "getProp"));
                return;
            }
            indexBase(ix.obj);
            for (Expr i : ix.idx) expr(i);
            op(PUSHARGLIST, ix.idx.size() + 1);
            op(OBJCALL, names.id("getAt"));
        }

        static final Map<String, Integer> BIN = new HashMap<>();
        static {
            BIN.put("*", MUL); BIN.put("+", ADD); BIN.put("-", SUB); BIN.put("/", DIV); BIN.put("mod", MOD);
            BIN.put("&", JOINSTR); BIN.put("&&", JOINPADSTR); BIN.put("<", LT); BIN.put("<=", LTEQ); BIN.put("<>", NTEQ);
            BIN.put("=", EQ); BIN.put(">", GT); BIN.put(">=", GTEQ); BIN.put("and", AND); BIN.put("or", OR);
            BIN.put("contains", CONTAINSSTR); BIN.put("starts", CONTAINS0STR);
        }

        void binary(Binary b) { expr(b.l); expr(b.r); op(BIN.get(b.op)); }

        void the(The t) {
            String p = t.prop;
            Integer id = ANIM2_PROPS.get(p.toLowerCase());
            if (id != null) {
                // the number of castMembers takes a castLib (0 = all)
                if (p.equalsIgnoreCase("number of castMembers")) { if (t.of == null) op(PUSHZERO); else expr(t.of); }
                op(PUSHINT8, id); op(GET, 8);
                return;
            }
            if (t.of == null && (id = ANIM_PROPS.get(p.toLowerCase())) != null) { op(PUSHINT8, id); op(GET, 7); return; }
            if (t.of == null) {
                if (THE_BUILTINS.contains(p.toLowerCase())) { op(PUSHARGLIST, 0); op(THEBUILTIN, names.id(p)); }
                else op(GETMOVIEPROP, names.id(p));
                return;
            }
            // the prop of obj  ==  obj.prop
            expr(t.of);
            op(GETOBJPROP, names.id(p));
        }
    }

    public static CompiledScript compile(ScriptDef s, Names names) { return compile(s, names, true); }

    /** d8: Director 8 bytecode (an Lctx context); false for 8.5 and later (LctX). */
    public static CompiledScript compile(ScriptDef s, Names names, boolean d8) { return new CodeGen(names, d8).compile(s); }

    static final class ByteArrayOutputStream extends java.io.ByteArrayOutputStream {
        @Override public synchronized void write(int b) { super.write(b); }
    }
}
