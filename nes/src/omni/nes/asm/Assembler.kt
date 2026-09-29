package omni.nes.asm

import java.io.ByteArrayOutputStream

/**
 * Clean-room two-pass assembler for a small ca65-like subset of 6502 assembly.
 * 100% original code; opcode numbers are long-published 6502 hardware facts.
 *
 * Supported syntax:
 * - `;` line comments (a `;` inside a "..." string literal is kept)
 * - labels: `name:` alone on a line, or in front of an instruction/directive
 * - constants: `name = expr` (must be defined before use)
 * - directives: `.org addr`, `.byte`/`.db b,...` (numbers or "string literals"),
 *   `.word`/`.dw w,...`, `.res count[,fill]`
 * - expressions: `$hex`, `%binary`, decimal, `<expr` (low byte), `>expr`
 *   (high byte), `+ - *`, parentheses, symbols, and `*` for the current PC
 * - all 56 official 6502 mnemonics (LDA STA LDX STX LDY STY TAX TAY TXA TYA
 *   TSX TXS DEX DEY INX INY ADC SBC AND ORA EOR CMP CPX CPY BIT ASL LSR ROL
 *   ROR INC DEC BPL BMI BVC BVS BCC BCS BNE BEQ JMP JSR RTS RTI BRK PHA PHP
 *   PLA PLP CLC SEC CLI SEI CLV CLD SED NOP) with their official modes:
 *   implied, `A` accumulator, `#imm` (also `#<label` / `#>label`), `zp`,
 *   `zp,X`, `zp,Y`, `abs`, `abs,X`, `abs,Y`, `(ind)` (JMP only), `(ind,X)`,
 *   `(ind),Y`; branches always assemble as relative.
 *
 * Two-pass rules (standard simple scheme):
 * - Pass 1 assigns every address. A symbol referenced before it is defined is
 *   assumed ABSOLUTE (3-byte operand) purely for size purposes.
 * - Pass 2 re-evaluates with all symbols known and emits bytes. An instruction
 *   whose operand was a forward reference in pass 1 KEEPS the absolute form
 *   chosen in pass 1 even if the symbol later resolves below $100, so both
 *   passes always agree on every address. (Consequence: a forward-referenced
 *   zero-page operand costs one extra byte; define zp symbols first to avoid it.)
 * - `.org` to a higher address zero-fills the gap; `.org` backwards is an error.
 * - Zero-page vs absolute: an operand expression that fully resolves below
 *   $100 uses the zero-page form when the mnemonic supports it, else absolute.
 *
 * [AssembleResult.bytes] runs from the first `.org` to the last emitted byte
 * (with no `.org` at all, assembly starts at address 0 and [AssembleResult.origin] is 0).
 */
class AssembleError(message: String, val line: Int) : Exception("line $line: $message")

data class AssembleResult(val bytes: ByteArray, val symbols: Map<String, Int>, val origin: Int)

object Assembler {

    fun assemble(source: String): AssembleResult {
        val stmts = source.lines().flatMapIndexed { i, raw -> parseLine(raw, i + 1) }

        // ---------------- pass 1: assign addresses ----------------
        val symbols = LinkedHashMap<String, Int>()
        val items = ArrayList<Item>()
        var pc = 0 // no .org yet -> assemble from address 0
        fun here(): Int = pc

        for (s in stmts) {
            when (s) {
                is Stmt.Label -> {
                    val p = here()
                    if (s.name in symbols) throw AssembleError("duplicate symbol '${s.name}'", s.line)
                    symbols[s.name] = p
                }
                is Stmt.Const -> {
                    if (s.name in symbols) throw AssembleError("duplicate symbol '${s.name}'", s.line)
                    val r = evalExpr(s.expr, symbols, pc, s.line)
                    if (!r.known) throw AssembleError("undefined symbol '${r.unknownName}' in constant '${s.name}'", s.line)
                    symbols[s.name] = r.value
                }
                is Stmt.Org -> {
                    val r = evalExpr(s.expr, symbols, pc, s.line)
                    if (!r.known) throw AssembleError("undefined symbol '${r.unknownName}' in .org", s.line)
                    val v = r.value
                    if (v !in 0..0xFFFF) throw AssembleError(".org address out of range", s.line)
                    if (v < pc) throw AssembleError(".org backwards", s.line)
                    pc = v
                    items.add(Item.Org(v))
                }
                is Stmt.Bytes -> {
                    var p = here()
                    for (b in s.items) {
                        when (b) {
                            is Stmt.ByteItem.Expr -> {
                                evalExpr(b.text, symbols, p, s.line) // size is 1 byte either way
                                items.add(Item.ByteExpr(b.text, s.line))
                                p++
                            }
                            is Stmt.ByteItem.Str -> {
                                items.add(Item.ByteStr(b.text))
                                p += b.text.length
                            }
                        }
                    }
                    pc = p
                }
                is Stmt.Words -> {
                    val p = here()
                    for (w in s.exprs) {
                        evalExpr(w, symbols, p, s.line)
                        items.add(Item.Word(w, s.line))
                    }
                    pc = p + 2 * s.exprs.size
                }
                is Stmt.Res -> {
                    val p = here()
                    val rc = evalExpr(s.count, symbols, p, s.line)
                    if (!rc.known) throw AssembleError("undefined symbol '${rc.unknownName}' in .res", s.line)
                    val count = rc.value
                    if (count < 0) throw AssembleError("negative .res count", s.line)
                    val fill = s.fill?.let {
                        val rf = evalExpr(it, symbols, p, s.line)
                        if (!rf.known) throw AssembleError("undefined symbol '${rf.unknownName}' in .res", s.line)
                        rf.value and 0xFF
                    } ?: 0
                    items.add(Item.Res(count, fill))
                    pc = p + count
                }
                is Stmt.Instr -> {
                    val p = here()
                    val plan = planInstr(s.mnemonic, s.operand, symbols, p, s.line)
                    items.add(Item.Instr(plan))
                    pc = p + plan.mode.size
                }
            }
        }

        // ---------------- pass 2: emit bytes ----------------
        val out = ByteArrayOutputStream()
        var pc2 = 0
        var firstOrg: Int? = null
        for (item in items) {
            when (item) {
                is Item.Org -> {
                    val t = item.target
                    if (firstOrg == null && out.size() == 0) {
                        // first .org and nothing emitted yet: the image starts here
                        firstOrg = t
                        pc2 = t
                    } else {
                        while (pc2 < t) { out.write(0); pc2++ } // zero-fill the gap
                    }
                }
                is Item.ByteExpr -> {
                    val p = pc2
                    val r = evalExpr(item.expr, symbols, p, item.line)
                    if (!r.known) throw AssembleError("undefined symbol '${r.unknownName}'", item.line)
                    out.write(r.value and 0xFF)
                    pc2 = p + 1
                }
                is Item.ByteStr -> {
                    val p = pc2
                    for (c in item.text) out.write(c.code and 0xFF)
                    pc2 = p + item.text.length
                }
                is Item.Word -> {
                    val p = pc2
                    val r = evalExpr(item.expr, symbols, p, item.line)
                    if (!r.known) throw AssembleError("undefined symbol '${r.unknownName}'", item.line)
                    out.write(r.value and 0xFF)
                    out.write((r.value ushr 8) and 0xFF)
                    pc2 = p + 2
                }
                is Item.Res -> {
                    val p = pc2
                    repeat(item.count) { out.write(item.fill) }
                    pc2 = p + item.count
                }
                is Item.Instr -> {
                    val plan = item.plan
                    val p = pc2
                    val op = OPCODES[plan.mnemonic to plan.mode]
                        ?: throw AssembleError("bad addressing mode for '${plan.mnemonic}'", plan.line)
                    out.write(op)
                    when (plan.mode) {
                        Mode.IMP, Mode.ACC -> {}
                        Mode.IMM, Mode.ZP, Mode.ZPX, Mode.ZPY, Mode.INDX, Mode.INDY -> {
                            val r = evalExpr(plan.operand!!, symbols, p, plan.line)
                            if (!r.known) throw AssembleError("undefined symbol '${r.unknownName}'", plan.line)
                            out.write(r.value and 0xFF)
                        }
                        Mode.ABS, Mode.ABSX, Mode.ABSY, Mode.IND -> {
                            val r = evalExpr(plan.operand!!, symbols, p, plan.line)
                            if (!r.known) throw AssembleError("undefined symbol '${r.unknownName}'", plan.line)
                            out.write(r.value and 0xFF)
                            out.write((r.value ushr 8) and 0xFF)
                        }
                        Mode.REL -> {
                            val r = evalExpr(plan.operand!!, symbols, p, plan.line)
                            if (!r.known) throw AssembleError("undefined symbol '${r.unknownName}' in branch", plan.line)
                            val off = r.value - (p + 2)
                            if (off !in -128..127) throw AssembleError("branch out of range ($off)", plan.line)
                            out.write(off and 0xFF)
                        }
                    }
                    pc2 = p + plan.mode.size
                }
            }
        }
        return AssembleResult(out.toByteArray(), symbols.toMap(), firstOrg ?: 0)
    }

    // ------------------------------------------------------------------
    // line parsing
    // ------------------------------------------------------------------

    private sealed interface Stmt {
        val line: Int
        data class Label(val name: String, override val line: Int) : Stmt
        data class Const(val name: String, val expr: String, override val line: Int) : Stmt
        data class Org(val expr: String, override val line: Int) : Stmt
        data class Bytes(val items: List<ByteItem>, override val line: Int) : Stmt
        data class Words(val exprs: List<String>, override val line: Int) : Stmt
        data class Res(val count: String, val fill: String?, override val line: Int) : Stmt
        data class Instr(val mnemonic: String, val operand: String?, override val line: Int) : Stmt

        sealed interface ByteItem {
            data class Expr(val text: String) : ByteItem
            data class Str(val text: String) : ByteItem
        }
    }

    private sealed interface Item {
        data class Org(val target: Int) : Item
        data class ByteExpr(val expr: String, val line: Int) : Item
        data class ByteStr(val text: String) : Item
        data class Word(val expr: String, val line: Int) : Item
        data class Res(val count: Int, val fill: Int) : Item
        data class Instr(val plan: InstrPlan) : Item
    }

    private data class InstrPlan(val mnemonic: String, val mode: Mode, val operand: String?, val line: Int)

    private fun parseLine(raw: String, line: Int): List<Stmt> {
        var s = stripComment(raw).trim()
        if (s.isEmpty()) return emptyList()
        val out = ArrayList<Stmt>()

        // label: `name:` alone or ahead of an instruction/directive
        val lm = Regex("""^([A-Za-z_][A-Za-z0-9_]*):""").find(s)
        if (lm != null) {
            out.add(Stmt.Label(lm.groupValues[1], line))
            s = s.substring(lm.value.length).trim()
            if (s.isEmpty()) return out
        }

        if (s.startsWith(".")) {
            val dm = Regex("""^\.([A-Za-z]+)""").find(s)
                ?: throw AssembleError("bad directive", line)
            val d = dm.groupValues[1].lowercase()
            val rest = s.substring(dm.value.length).trim()
            when (d) {
                "org" -> {
                    if (rest.isEmpty()) throw AssembleError(".org needs an address", line)
                    out.add(Stmt.Org(rest, line))
                }
                "byte", "db" -> {
                    out.add(Stmt.Bytes(splitItems(rest, line).map { item ->
                        val t = item.trim()
                        when {
                            t.startsWith("\"") -> {
                                if (t.length < 2 || !t.endsWith("\""))
                                    throw AssembleError("unterminated string", line)
                                Stmt.ByteItem.Str(t.substring(1, t.length - 1))
                            }
                            t.isEmpty() -> throw AssembleError("empty .byte item", line)
                            else -> Stmt.ByteItem.Expr(t)
                        }
                    }, line))
                }
                "word", "dw" -> {
                    out.add(Stmt.Words(splitItems(rest, line).map { item ->
                        val t = item.trim()
                        if (t.isEmpty()) throw AssembleError("empty .word item", line)
                        t
                    }, line))
                }
                "res" -> {
                    val parts = splitItems(rest, line)
                    if (parts.isEmpty() || parts.size > 2)
                        throw AssembleError(".res needs count[,fill]", line)
                    out.add(Stmt.Res(parts[0].trim(), parts.getOrNull(1)?.trim(), line))
                }
                else -> throw AssembleError("unknown directive '.$d'", line)
            }
            return out
        }

        val cm = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.+)$""").find(s)
        if (cm != null) {
            out.add(Stmt.Const(cm.groupValues[1], cm.groupValues[2].trim(), line))
            return out
        }

        val im = Regex("""^([A-Za-z]+)\b\s*(.*)$""").find(s)
            ?: throw AssembleError("can't parse line", line)
        val mnemonic = im.groupValues[1].uppercase()
        if (mnemonic !in MNEMONICS) throw AssembleError("unknown mnemonic '$mnemonic'", line)
        val operand = im.groupValues[2].trim().ifEmpty { null }
        out.add(Stmt.Instr(mnemonic, operand, line))
        return out
    }

    /** Cut a `;` comment, but keep `;` inside "..." string literals. */
    private fun stripComment(raw: String): String {
        val sb = StringBuilder()
        var inStr = false
        for (c in raw) {
            if (c == '"') inStr = !inStr
            if (c == ';' && !inStr) break
            sb.append(c)
        }
        return sb.toString()
    }

    /** Split on commas that are not inside "..." string literals. */
    private fun splitItems(s: String, line: Int): List<String> {
        val items = ArrayList<String>()
        val cur = StringBuilder()
        var inStr = false
        for (c in s) {
            if (c == '"') {
                inStr = !inStr
                cur.append(c)
            } else if (c == ',' && !inStr) {
                items.add(cur.toString())
                cur.clear()
            } else {
                cur.append(c)
            }
        }
        if (inStr) throw AssembleError("unterminated string", line)
        items.add(cur.toString())
        return items
    }

    // ------------------------------------------------------------------
    // instruction planning (mode selection)
    // ------------------------------------------------------------------

    private val indxRe = Regex("""^\(\s*(.+?)\s*,\s*[Xx]\s*\)$""")
    private val indyRe = Regex("""^\(\s*(.+?)\s*\)\s*,\s*[Yy]$""")
    private val indRe = Regex("""^\(\s*(.+?)\s*\)$""")

    /** Returns (mode, inner-expression) for `(e)`, `(e,X)`, `(e),Y`, else null. */
    private fun parseIndirect(t: String): Pair<Mode, String>? {
        indxRe.matchEntire(t)?.let { return Mode.INDX to it.groupValues[1] }
        indyRe.matchEntire(t)?.let { return Mode.INDY to it.groupValues[1] }
        indRe.matchEntire(t)?.let { return Mode.IND to it.groupValues[1] }
        return null
    }

    private fun planInstr(
        mnemonic: String,
        operand: String?,
        symbols: Map<String, Int>,
        pc: Int,
        line: Int,
    ): InstrPlan {
        fun bad(): Nothing = throw AssembleError("bad addressing mode for '$mnemonic'", line)

        if (mnemonic in BRANCHES) {
            if (operand == null) throw AssembleError("'$mnemonic' needs an operand", line)
            return InstrPlan(mnemonic, Mode.REL, operand, line)
        }
        if (mnemonic == "JMP") {
            if (operand == null) throw AssembleError("'JMP' needs an operand", line)
            parseIndirect(operand.trim())?.let { (mode, expr) ->
                if (mode != Mode.IND) bad() // JMP takes (ind) only, not (ind,X)/(ind),Y
                return InstrPlan("JMP", Mode.IND, expr, line)
            }
            val t = operand.trim()
            if (t.startsWith("#") || t.contains(',')) bad()
            return InstrPlan("JMP", Mode.ABS, t, line)
        }
        if (mnemonic == "JSR") {
            if (operand == null) throw AssembleError("'JSR' needs an operand", line)
            val t = operand.trim()
            if (t.startsWith("#") || t.startsWith("(") || t.contains(',')) bad()
            return InstrPlan("JSR", Mode.ABS, t, line)
        }
        if (mnemonic in IMPLIED) {
            if (operand != null) throw AssembleError("'$mnemonic' takes no operand", line)
            return InstrPlan(mnemonic, Mode.IMP, null, line)
        }
        if (operand == null) throw AssembleError("'$mnemonic' needs an operand", line)
        val t = operand.trim()
        if (mnemonic in SHIFTS && t.equals("A", ignoreCase = true))
            return InstrPlan(mnemonic, Mode.ACC, null, line)
        if (t.startsWith("#")) {
            if (OPCODES[mnemonic to Mode.IMM] == null) bad()
            return InstrPlan(mnemonic, Mode.IMM, t.substring(1), line)
        }
        parseIndirect(t)?.let { (mode, expr) ->
            if (mode == Mode.IND) bad() // (ind) is JMP-only
            if (OPCODES[mnemonic to mode] == null) bad()
            return InstrPlan(mnemonic, mode, expr, line)
        }
        val comma = t.indexOf(',')
        if (comma >= 0) {
            val expr = t.substring(0, comma).trim()
            val idx = t.substring(comma + 1).trim().uppercase()
            if (idx != "X" && idx != "Y") throw AssembleError("bad index register ',${t.substring(comma + 1).trim()}'", line)
            if (expr.isEmpty()) throw AssembleError("missing expression before ',${idx}'", line)
            val (v, known) = evalExpr(expr, symbols, pc, line)
            val zp = known && v in 0..0xFF
            val mode = when (idx) {
                "X" -> if (zp) Mode.ZPX else Mode.ABSX
                else -> if (zp) Mode.ZPY else Mode.ABSY
            }
            if (OPCODES[mnemonic to mode] == null) bad()
            return InstrPlan(mnemonic, mode, expr, line)
        }
        val (v, known) = evalExpr(t, symbols, pc, line)
        val mode = if (known && v in 0..0xFF) Mode.ZP else Mode.ABS
        if (OPCODES[mnemonic to mode] == null) bad()
        return InstrPlan(mnemonic, mode, t, line)
    }

    // ------------------------------------------------------------------
    // expression evaluator: $hex %binary decimal <low >high + - * ( ) sym *
    // ------------------------------------------------------------------

    private data class EvalResult(val value: Int, val known: Boolean, val unknownName: String?)

    private fun evalExpr(text: String, symbols: Map<String, Int>, pc: Int, line: Int): EvalResult {
        val e = ExprParser(text, symbols, pc, line)
        return EvalResult(e.parse(), e.allKnown, e.unknownName)
    }

    private class ExprParser(
        val text: String,
        val symbols: Map<String, Int>,
        val pc: Int,
        val line: Int,
    ) {
        var pos = 0
        var allKnown = true
        var unknownName: String? = null

        fun parse(): Int {
            val v = parseAdd()
            skipWs()
            if (pos < text.length) fail("unexpected '${text[pos]}' in expression")
            return v
        }

        private fun fail(msg: String): Nothing = throw AssembleError(msg, line)

        private fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun parseAdd(): Int {
            var v = parseMul()
            while (true) {
                skipWs()
                when {
                    pos < text.length && text[pos] == '+' -> { pos++; v += parseMul() }
                    pos < text.length && text[pos] == '-' -> { pos++; v -= parseMul() }
                    else -> return v
                }
            }
        }

        private fun parseMul(): Int {
            var v = parseUnary()
            while (true) {
                skipWs()
                if (pos < text.length && text[pos] == '*') {
                    pos++
                    skipWs()
                    if (pos < text.length && text[pos] == '*') fail("unexpected '*' in expression")
                    v *= parseUnary()
                } else return v
            }
        }

        private fun parseUnary(): Int {
            skipWs()
            if (pos >= text.length) fail("unexpected end of expression")
            return when (text[pos]) {
                '-' -> { pos++; -parseUnary() }
                '<' -> { pos++; parseUnary() and 0xFF }
                '>' -> { pos++; (parseUnary() ushr 8) and 0xFF }
                else -> parsePrimary()
            }
        }

        private fun parsePrimary(): Int {
            skipWs()
            if (pos >= text.length) fail("unexpected end of expression")
            val c = text[pos]
            return when {
                c == '(' -> {
                    pos++
                    val v = parseAdd()
                    skipWs()
                    if (pos >= text.length || text[pos] != ')') fail("missing ')' in expression")
                    pos++
                    v
                }
                c == '$' -> { pos++; parseDigits(16) }
                c == '%' -> { pos++; parseDigits(2) }
                c.isDigit() -> parseDigits(10)
                c == '*' -> { pos++; pc } // current-PC symbol
                c.isLetter() || c == '_' -> parseSymbol()
                else -> fail("unexpected '$c' in expression")
            }
        }

        private fun parseDigits(base: Int): Int {
            val start = pos
            while (pos < text.length && isDigitInBase(text[pos], base)) pos++
            if (pos == start) fail("expected digits in expression")
            return text.substring(start, pos).toInt(base)
        }

        private fun isDigitInBase(c: Char, base: Int): Boolean = when (base) {
            16 -> c.isDigit() || c.lowercaseChar() in 'a'..'f'
            2 -> c == '0' || c == '1'
            else -> c.isDigit()
        }

        private fun parseSymbol(): Int {
            val start = pos
            while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] == '_')) pos++
            val name = text.substring(start, pos)
            return symbols[name] ?: run {
                allKnown = false
                if (unknownName == null) unknownName = name
                0
            }
        }
    }

    // ------------------------------------------------------------------
    // opcode table: the 151 official 6502 opcodes (hardware facts)
    // ------------------------------------------------------------------

    private enum class Mode(val size: Int) {
        IMP(1), ACC(1), IMM(2), ZP(2), ZPX(2), ZPY(2),
        ABS(3), ABSX(3), ABSY(3), IND(3), INDX(2), INDY(2), REL(2),
    }

    private fun e(m: String, mode: Mode, code: Int) = (m to mode) to code

    private val OPCODES: Map<Pair<String, Mode>, Int> = mapOf(
        // LDA
        e("LDA", Mode.IMM, 0xA9), e("LDA", Mode.ZP, 0xA5), e("LDA", Mode.ZPX, 0xB5),
        e("LDA", Mode.ABS, 0xAD), e("LDA", Mode.ABSX, 0xBD), e("LDA", Mode.ABSY, 0xB9),
        e("LDA", Mode.INDX, 0xA1), e("LDA", Mode.INDY, 0xB1),
        // STA
        e("STA", Mode.ZP, 0x85), e("STA", Mode.ZPX, 0x95), e("STA", Mode.ABS, 0x8D),
        e("STA", Mode.ABSX, 0x9D), e("STA", Mode.ABSY, 0x99),
        e("STA", Mode.INDX, 0x81), e("STA", Mode.INDY, 0x91),
        // LDX
        e("LDX", Mode.IMM, 0xA2), e("LDX", Mode.ZP, 0xA6), e("LDX", Mode.ZPY, 0xB6),
        e("LDX", Mode.ABS, 0xAE), e("LDX", Mode.ABSY, 0xBE),
        // STX
        e("STX", Mode.ZP, 0x86), e("STX", Mode.ZPY, 0x96), e("STX", Mode.ABS, 0x8E),
        // LDY
        e("LDY", Mode.IMM, 0xA0), e("LDY", Mode.ZP, 0xA4), e("LDY", Mode.ZPX, 0xB4),
        e("LDY", Mode.ABS, 0xAC), e("LDY", Mode.ABSX, 0xBC),
        // STY
        e("STY", Mode.ZP, 0x84), e("STY", Mode.ZPX, 0x94), e("STY", Mode.ABS, 0x8C),
        // implied single-byte transfers / inc-dec
        e("TAX", Mode.IMP, 0xAA), e("TAY", Mode.IMP, 0xA8), e("TXA", Mode.IMP, 0x8A),
        e("TYA", Mode.IMP, 0x98), e("TSX", Mode.IMP, 0xBA), e("TXS", Mode.IMP, 0x9A),
        e("DEX", Mode.IMP, 0xCA), e("DEY", Mode.IMP, 0x88),
        e("INX", Mode.IMP, 0xE8), e("INY", Mode.IMP, 0xC8),
        // ADC
        e("ADC", Mode.IMM, 0x69), e("ADC", Mode.ZP, 0x65), e("ADC", Mode.ZPX, 0x75),
        e("ADC", Mode.ABS, 0x6D), e("ADC", Mode.ABSX, 0x7D), e("ADC", Mode.ABSY, 0x79),
        e("ADC", Mode.INDX, 0x61), e("ADC", Mode.INDY, 0x71),
        // SBC
        e("SBC", Mode.IMM, 0xE9), e("SBC", Mode.ZP, 0xE5), e("SBC", Mode.ZPX, 0xF5),
        e("SBC", Mode.ABS, 0xED), e("SBC", Mode.ABSX, 0xFD), e("SBC", Mode.ABSY, 0xF9),
        e("SBC", Mode.INDX, 0xE1), e("SBC", Mode.INDY, 0xF1),
        // AND
        e("AND", Mode.IMM, 0x29), e("AND", Mode.ZP, 0x25), e("AND", Mode.ZPX, 0x35),
        e("AND", Mode.ABS, 0x2D), e("AND", Mode.ABSX, 0x3D), e("AND", Mode.ABSY, 0x39),
        e("AND", Mode.INDX, 0x21), e("AND", Mode.INDY, 0x31),
        // ORA
        e("ORA", Mode.IMM, 0x09), e("ORA", Mode.ZP, 0x05), e("ORA", Mode.ZPX, 0x15),
        e("ORA", Mode.ABS, 0x0D), e("ORA", Mode.ABSX, 0x1D), e("ORA", Mode.ABSY, 0x19),
        e("ORA", Mode.INDX, 0x01), e("ORA", Mode.INDY, 0x11),
        // EOR
        e("EOR", Mode.IMM, 0x49), e("EOR", Mode.ZP, 0x45), e("EOR", Mode.ZPX, 0x55),
        e("EOR", Mode.ABS, 0x4D), e("EOR", Mode.ABSX, 0x5D), e("EOR", Mode.ABSY, 0x59),
        e("EOR", Mode.INDX, 0x41), e("EOR", Mode.INDY, 0x51),
        // CMP
        e("CMP", Mode.IMM, 0xC9), e("CMP", Mode.ZP, 0xC5), e("CMP", Mode.ZPX, 0xD5),
        e("CMP", Mode.ABS, 0xCD), e("CMP", Mode.ABSX, 0xDD), e("CMP", Mode.ABSY, 0xD9),
        e("CMP", Mode.INDX, 0xC1), e("CMP", Mode.INDY, 0xD1),
        // CPX / CPY
        e("CPX", Mode.IMM, 0xE0), e("CPX", Mode.ZP, 0xE4), e("CPX", Mode.ABS, 0xEC),
        e("CPY", Mode.IMM, 0xC0), e("CPY", Mode.ZP, 0xC4), e("CPY", Mode.ABS, 0xCC),
        // BIT
        e("BIT", Mode.ZP, 0x24), e("BIT", Mode.ABS, 0x2C),
        // ASL
        e("ASL", Mode.ACC, 0x0A), e("ASL", Mode.ZP, 0x06), e("ASL", Mode.ZPX, 0x16),
        e("ASL", Mode.ABS, 0x0E), e("ASL", Mode.ABSX, 0x1E),
        // LSR
        e("LSR", Mode.ACC, 0x4A), e("LSR", Mode.ZP, 0x46), e("LSR", Mode.ZPX, 0x56),
        e("LSR", Mode.ABS, 0x4E), e("LSR", Mode.ABSX, 0x5E),
        // ROL
        e("ROL", Mode.ACC, 0x2A), e("ROL", Mode.ZP, 0x26), e("ROL", Mode.ZPX, 0x36),
        e("ROL", Mode.ABS, 0x2E), e("ROL", Mode.ABSX, 0x3E),
        // ROR
        e("ROR", Mode.ACC, 0x6A), e("ROR", Mode.ZP, 0x66), e("ROR", Mode.ZPX, 0x76),
        e("ROR", Mode.ABS, 0x6E), e("ROR", Mode.ABSX, 0x7E),
        // INC / DEC
        e("INC", Mode.ZP, 0xE6), e("INC", Mode.ZPX, 0xF6),
        e("INC", Mode.ABS, 0xEE), e("INC", Mode.ABSX, 0xFE),
        e("DEC", Mode.ZP, 0xC6), e("DEC", Mode.ZPX, 0xD6),
        e("DEC", Mode.ABS, 0xCE), e("DEC", Mode.ABSX, 0xDE),
        // branches
        e("BPL", Mode.REL, 0x10), e("BMI", Mode.REL, 0x30),
        e("BVC", Mode.REL, 0x50), e("BVS", Mode.REL, 0x70),
        e("BCC", Mode.REL, 0x90), e("BCS", Mode.REL, 0xB0),
        e("BNE", Mode.REL, 0xD0), e("BEQ", Mode.REL, 0xF0),
        // jumps
        e("JMP", Mode.ABS, 0x4C), e("JMP", Mode.IND, 0x6C),
        e("JSR", Mode.ABS, 0x20),
        e("RTS", Mode.IMP, 0x60), e("RTI", Mode.IMP, 0x40), e("BRK", Mode.IMP, 0x00),
        // stack
        e("PHA", Mode.IMP, 0x48), e("PHP", Mode.IMP, 0x08),
        e("PLA", Mode.IMP, 0x68), e("PLP", Mode.IMP, 0x28),
        // flags
        e("CLC", Mode.IMP, 0x18), e("SEC", Mode.IMP, 0x38),
        e("CLI", Mode.IMP, 0x58), e("SEI", Mode.IMP, 0x78),
        e("CLV", Mode.IMP, 0xB8), e("CLD", Mode.IMP, 0xD8), e("SED", Mode.IMP, 0xF8),
        // nop
        e("NOP", Mode.IMP, 0xEA),
    ).also { check(it.size == 151) { "opcode table has ${it.size} entries, expected 151" } }

    private val MNEMONICS: Set<String> = OPCODES.keys.map { it.first }.toSet()

    private val BRANCHES = setOf("BPL", "BMI", "BVC", "BVS", "BCC", "BCS", "BNE", "BEQ")

    private val IMPLIED = setOf(
        "TAX", "TAY", "TXA", "TYA", "TSX", "TXS", "DEX", "DEY", "INX", "INY",
        "RTS", "RTI", "BRK", "PHA", "PHP", "PLA", "PLP",
        "CLC", "SEC", "CLI", "SEI", "CLV", "CLD", "SED", "NOP",
    )

    private val SHIFTS = setOf("ASL", "LSR", "ROL", "ROR")
}
