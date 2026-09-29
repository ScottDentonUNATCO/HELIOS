package omni.nes.gen

/**
 * 8 KB CHR image shared by the mock-generator games. 100% original pixel
 * data drawn for this project (no Nintendo assets).
 *
 * Layout:
 * - tile 0: blank
 * - tile 1: player block (dpad-move, score games)
 * - tile 2: ball (bounce game)
 * - tiles 16..25: digits 0..9 (score display; 3x5 font centered in 8x8)
 */
object ChrData {

    private fun tile(rows: List<String>, hi: List<String>? = null): ByteArray {
        require(rows.size == 8 && rows.all { it.length == 8 })
        val out = ByteArray(16)
        for (r in 0 until 8) {
            var lo = 0
            var h = 0
            for (c in 0 until 8) {
                if (rows[r][c] == '#') lo = lo or (0x80 ushr c)
                if (hi != null && hi[r][c] == '#') h = h or (0x80 ushr c)
            }
            out[r] = lo.toByte()
            out[r + 8] = h.toByte()
        }
        return out
    }

    private val BLANK = tile(List(8) { "........" })

    private val PLAYER = tile(
        listOf(
            ".######.",
            "########",
            "########",
            "########",
            "########",
            "########",
            "########",
            ".######.",
        ),
    )

    private val BALL = tile(
        listOf(
            "........",
            "...##...",
            "..####..",
            "..####..",
            "..####..",
            "..####..",
            "...##...",
            "........",
        ),
    )

    // 3x5 digits, centered (rows 1..5, cols 2..4).
    private val DIGITS = listOf(
        listOf("###", "#.#", "#.#", "#.#", "###"), // 0
        listOf(".#.", ".#.", ".#.", ".#.", ".#."),
        listOf("###", "..#", "###", "#..", "###"),
        listOf("###", "..#", "###", "..#", "###"),
        listOf("#.#", "#.#", "###", "..#", "..#"),
        listOf("###", "#..", "###", "..#", "###"),
        listOf("###", "#..", "###", "#.#", "###"),
        listOf("###", "..#", "..#", ".#.", ".#."),
        listOf("###", "#.#", "###", "#.#", "###"),
        listOf("###", "#.#", "###", "..#", "###"),
    ).map { g ->
        val rows = MutableList(8) { "........" }
        for (r in 0 until 5) {
            rows[r + 1] = ".." + g[r] + "..."
        }
        tile(rows)
    }

    /** Full 8 KB CHR (8192 bytes) for the mock-generator games. */
    fun default(): ByteArray {
        val chr = ByteArray(8192)
        fun put(tileIndex: Int, data: ByteArray) {
            data.copyInto(chr, tileIndex * 16)
        }
        put(1, PLAYER)
        put(2, BALL)
        DIGITS.forEachIndexed { d, data -> put(16 + d, data) }
        return chr
    }
}
