// Director 8 compiled Lingo: reading and writing script chunks.
//
//   Lctx  script context: which Lscr chunk holds script number N, and which Lnam to use
//   Lnam  names table shared by a context's scripts (handlers, properties, variables, symbols)
//   Lscr  one compiled script: properties, globals, handlers (bytecode + name tables), literals
//
// Layouts follow ProjectorRays (lingodec). All three chunks are big-endian.

import java.io.*;
import java.nio.*;
import java.nio.charset.Charset;
import java.util.*;

public class LingoScript {

    static final Charset MAC = Charset.forName("x-MacRoman");

    // ------------------------------------------------------------------ Lnam

    static List<String> readNames(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
        int namesOffset = bb.getShort(16) & 0xffff, count = bb.getShort(18) & 0xffff;
        List<String> names = new ArrayList<>();
        int p = namesOffset;
        for (int i = 0; i < count; i++) {
            int len = b[p] & 0xff;
            names.add(new String(b, p + 1, len, MAC));
            p += 1 + len;
        }
        return names;
    }

    // ------------------------------------------------------------------ Lctx

    static final class Context {
        int lnamSection;
        List<Integer> scriptSections = new ArrayList<>(); // index = script number - 1; -1 = empty
    }

    static Context readContext(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
        Context c = new Context();
        int entryCount = bb.getInt(8);
        int entriesOffset = bb.getShort(16) & 0xffff;
        c.lnamSection = bb.getInt(32);
        for (int i = 0; i < entryCount; i++) c.scriptSections.add(bb.getInt(entriesOffset + i * 12 + 4));
        return c;
    }

    // ------------------------------------------------------------------ Lscr

    static final class Literal {
        int type; // 1 string, 4 int, 9 float
        String string; int intValue; double floatValue;
        @Override public boolean equals(Object o) {
            if (!(o instanceof Literal)) return false;
            Literal l = (Literal) o;
            return type == l.type && Objects.equals(string, l.string) && intValue == l.intValue
                && Double.doubleToLongBits(floatValue) == Double.doubleToLongBits(l.floatValue);
        }
        @Override public int hashCode() { return Objects.hash(type, string, intValue, floatValue); }
        @Override public String toString() {
            return type == 1 ? "\"" + string + "\"" : type == 4 ? Integer.toString(intValue) : Double.toString(floatValue);
        }
    }

    static final class Handler {
        int nameId, vectorPos, unknown1, unknown2, stackHeight;
        byte[] bytecode = new byte[0];
        List<Integer> argNames = new ArrayList<>(), localNames = new ArrayList<>(), globalNames = new ArrayList<>();
        byte[] lineTable = new byte[0]; int lineCount;
    }

    static final class Script {
        byte[] raw;
        int scriptNumber, unk20, parentNumber, scriptFlags, unk42, castID, factoryNameID;
        byte[] headerPrefix;              // bytes 0..37, kept for round trips
        List<Integer> propertyNames = new ArrayList<>(), globalNames = new ArrayList<>();
        List<Handler> handlers = new ArrayList<>();
        List<Literal> literals = new ArrayList<>();
        int handlerVectorsCount; byte[] handlerVectors = new byte[0];
    }

    static List<Integer> nameTable(ByteBuffer bb, int count, int offset) {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < count; i++) l.add((int) bb.getShort(offset + i * 2));
        return l;
    }

    static Script readScript(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
        Script s = new Script();
        s.raw = b;
        s.headerPrefix = Arrays.copyOf(b, 38);
        s.scriptNumber = bb.getShort(18) & 0xffff;
        s.unk20 = bb.getShort(20);
        s.parentNumber = bb.getShort(22);
        s.scriptFlags = bb.getInt(38);
        s.unk42 = bb.getShort(42);
        s.castID = bb.getInt(44);
        s.factoryNameID = bb.getShort(48);
        s.handlerVectorsCount = bb.getShort(50) & 0xffff;
        int hvOffset = bb.getInt(52), hvSize = bb.getInt(56);
        s.handlerVectors = Arrays.copyOfRange(b, hvOffset, Math.min(b.length, hvOffset + hvSize));
        int propCount = bb.getShort(60) & 0xffff, propOffset = bb.getInt(62);
        int globCount = bb.getShort(66) & 0xffff, globOffset = bb.getInt(68);
        int handlerCount = bb.getShort(72) & 0xffff, handlerOffset = bb.getInt(74);
        int litCount = bb.getShort(78) & 0xffff, litOffset = bb.getInt(80);
        int litDataOffset = bb.getInt(88);
        s.propertyNames = nameTable(bb, propCount, propOffset);
        s.globalNames = nameTable(bb, globCount, globOffset);
        int rec = 42; // handler record size before Director 8.5 (8.5+ adds a 4-byte stackHeight)
        for (int i = 0; i < handlerCount; i++) {
            int p = handlerOffset + i * rec;
            Handler h = new Handler();
            h.nameId = bb.getShort(p);
            h.vectorPos = bb.getShort(p + 2) & 0xffff;
            int compiledLen = bb.getInt(p + 4), compiledOffset = bb.getInt(p + 8);
            int argCount = bb.getShort(p + 12) & 0xffff, argOffset = bb.getInt(p + 14);
            int localCount = bb.getShort(p + 18) & 0xffff, localOffset = bb.getInt(p + 20);
            int hGlobCount = bb.getShort(p + 24) & 0xffff, hGlobOffset = bb.getInt(p + 26);
            h.unknown1 = bb.getInt(p + 30);
            h.unknown2 = bb.getShort(p + 34) & 0xffff;
            h.lineCount = bb.getShort(p + 36) & 0xffff;
            int lineOffset = bb.getInt(p + 38);
            h.bytecode = Arrays.copyOfRange(b, compiledOffset, compiledOffset + compiledLen);
            h.argNames = nameTable(bb, argCount, argOffset);
            h.localNames = nameTable(bb, localCount, localOffset);
            h.globalNames = nameTable(bb, hGlobCount, hGlobOffset);
            if (h.lineCount > 0 && lineOffset > 0 && lineOffset < b.length)
                h.lineTable = Arrays.copyOfRange(b, lineOffset, Math.min(b.length, lineOffset + h.lineCount));
            s.handlers.add(h);
        }
        for (int i = 0; i < litCount; i++) {
            int p = litOffset + i * 8;
            Literal l = new Literal();
            l.type = bb.getInt(p);
            int off = bb.getInt(p + 4);
            if (l.type == 4) l.intValue = off;
            else {
                int q = litDataOffset + off;
                int len = bb.getInt(q);
                if (l.type == 1) l.string = new String(b, q + 4, Math.max(0, len - 1), MAC);
                else if (l.type == 9) l.floatValue = len == 8 ? bb.getDouble(q + 4) : 0;
            }
            s.literals.add(l);
        }
        return s;
    }

    // ------------------------------------------------------------------ disassembly

    static final String[] OP1 = new String[0x40];
    static final String[] OPN = new String[0x40];
    static {
        String[][] one = {{"01","ret"},{"02","retfactory"},{"03","pushzero"},{"04","mul"},{"05","add"},{"06","sub"},{"07","div"},
            {"08","mod"},{"09","inv"},{"0a","joinstr"},{"0b","joinpadstr"},{"0c","lt"},{"0d","lteq"},{"0e","nteq"},{"0f","eq"},
            {"10","gt"},{"11","gteq"},{"12","and"},{"13","or"},{"14","not"},{"15","containsstr"},{"16","contains0str"},
            {"17","getchunk"},{"18","hilitechunk"},{"19","ontospr"},{"1a","intospr"},{"1b","getfield"},{"1c","starttell"},
            {"1d","endtell"},{"1e","pushlist"},{"1f","pushproplist"},{"21","swap"}};
        for (String[] o : one) OP1[Integer.parseInt(o[0], 16)] = o[1];
        String[][] multi = {{"01","pushint8"},{"02","pusharglistnoret"},{"03","pusharglist"},{"04","pushcons"},{"05","pushsymb"},
            {"06","pushvarref"},{"08","getglobal2"},{"09","getglobal"},{"0a","getprop"},{"0b","getparam"},{"0c","getlocal"},
            {"0e","setglobal2"},{"0f","setglobal"},{"10","setprop"},{"11","setparam"},{"12","setlocal"},{"13","jmp"},
            {"14","endrepeat"},{"15","jmpifz"},{"16","localcall"},{"17","extcall"},{"18","objcallv4"},{"19","put"},
            {"1a","putchunk"},{"1b","deletechunk"},{"1c","get"},{"1d","set"},{"1f","getmovieprop"},{"20","setmovieprop"},
            {"21","getobjprop"},{"22","setobjprop"},{"23","tellcall"},{"24","peek"},{"25","pop"},{"26","thebuiltin"},
            {"27","objcall"},{"2d","pushchunkvarref"},{"2e","pushint16"},{"2f","pushint32"},{"30","getchainedprop"},
            {"31","pushfloat32"},{"32","gettoplevelprop"},{"33","newobj"}};
        for (String[] o : multi) OPN[Integer.parseInt(o[0], 16)] = o[1];
    }

    static String opName(int op) {
        if (op < 0x40) return OP1[op] != null ? OP1[op] : String.format("unk%02x", op);
        String n = OPN[op % 0x40];
        return n != null ? n : String.format("unk%02x", op);
    }

    /** One line per instruction: "[pos] name operand" (operand as decoded, like ProjectorRays). */
    static List<String> disassemble(byte[] code) {
        ByteBuffer bb = ByteBuffer.wrap(code).order(ByteOrder.BIG_ENDIAN);
        List<String> out = new ArrayList<>();
        int p = 0;
        while (p < code.length) {
            int op = code[p] & 0xff, start = p++;
            String name = opName(op);
            String arg = "";
            if (op >= 0xc0) { arg = " " + bb.getInt(p); p += 4; }
            else if (op >= 0x80) {
                boolean signed = name.equals("pushint8") || name.equals("pushint16");
                arg = " " + (signed ? bb.getShort(p) : (bb.getShort(p) & 0xffff)); p += 2;
            } else if (op >= 0x40) {
                arg = " " + (name.equals("pushint8") ? code[p] : (code[p] & 0xff)); p += 1;
            }
            out.add(String.format("[%3d] %s%s", start, name, arg));
        }
        return out;
    }
}
