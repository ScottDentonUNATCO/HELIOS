package omni.nes.rom

/** Nametable mirroring from iNES flag 6 bit 0. */
enum class Mirroring { HORIZONTAL, VERTICAL }

/** A parsed iNES ROM image (header stripped). */
data class INesRom(
    val prg: ByteArray,
    val chr: ByteArray,
    val mapper: Int,
    val mirroring: Mirroring,
    val battery: Boolean,
)

/**
 * Thrown by [INes.parse] when a ROM fails the Phase-1 gate.
 * [reason] always starts with the rule id, e.g. "HDR_MAGIC: ...", so the
 * validator can map it straight to a [omni.nes.validate.Finding].
 */
class INesError(val reason: String) : Exception(reason)

/**
 * Minimal iNES parser with deliberately strict Phase-1 rules:
 * mapper 0 (NROM) only, no trainer, no NES 2.0, sane bank counts.
 */
object INes {
    fun parse(bytes: ByteArray): INesRom {
        if (bytes.size < 16) {
            throw INesError("HDR_MAGIC: file is ${bytes.size} bytes, shorter than a 16-byte iNES header")
        }
        val magicOk = bytes[0] == 0x4E.toByte() && bytes[1] == 0x45.toByte() &&
            bytes[2] == 0x53.toByte() && bytes[3] == 0x1A.toByte()
        if (!magicOk) {
            val got = bytes.take(4).joinToString(" ") { "%02X".format(it) }
            throw INesError("HDR_MAGIC: bad magic, expected 4E 45 53 1A (NES\\x1A), got $got")
        }
        val prgBanks = bytes[4].toInt() and 0xFF
        val chrBanks = bytes[5].toInt() and 0xFF
        val f6 = bytes[6].toInt() and 0xFF
        val f7 = bytes[7].toInt() and 0xFF

        if (prgBanks == 0) throw INesError("HDR_PRG_SIZE: PRG banks = 0, a ROM needs code")
        if (prgBanks > 2) {
            throw INesError("HDR_PRG_SIZE: PRG banks = $prgBanks (${prgBanks * 16}KB); NROM max is 2 (32KB)")
        }
        if (chrBanks > 1) {
            throw INesError("HDR_CHR_SIZE: CHR banks = $chrBanks (${chrBanks * 8}KB); max 1 (8KB)")
        }
        if (f6 and 0x04 != 0) throw INesError("HDR_TRAINER: 512-byte trainer present; obsolete, not supported in phase 1")
        val mapper = ((f7 and 0xF0) or ((f6 and 0xF0) shr 4))
        if (mapper != 0) {
            throw INesError("HDR_MAPPER: mapper $mapper unsupported; phase 1 supports NROM (mapper 0) only")
        }
        if (f7 and 0x0C == 0x08) throw INesError("HDR_NES20: NES 2.0 header detected; not supported in phase 1")

        val prgSize = prgBanks * 16384
        val chrSize = chrBanks * 8192
        val expected = 16 + prgSize + chrSize
        if (bytes.size < expected) {
            throw INesError(
                "HDR_PRG_SIZE: file truncated: header claims ${prgBanks}x16KB PRG + ${chrBanks}x8KB CHR " +
                    "= $expected bytes total, file is ${bytes.size} bytes",
            )
        }

        val prg = bytes.copyOfRange(16, 16 + prgSize)
        val chr = if (chrBanks == 0) ByteArray(8192) else bytes.copyOfRange(16 + prgSize, 16 + prgSize + chrSize)
        return INesRom(
            prg = prg,
            chr = chr,
            mapper = mapper,
            mirroring = if (f6 and 0x01 != 0) Mirroring.VERTICAL else Mirroring.HORIZONTAL,
            battery = (f6 and 0x02) != 0,
        )
    }
}

/** Builds a Phase-1 (mapper 0) iNES file from PRG/CHR images. */
object RomBuilder {
    fun build(
        prg: ByteArray,
        chr: ByteArray = ByteArray(8192),
        mirroring: Mirroring = Mirroring.VERTICAL,
    ): ByteArray {
        require(prg.size == 16384 || prg.size == 32768) {
            "NROM PRG must be 16KB or 32KB, got ${prg.size} bytes"
        }
        require(chr.size == 8192) { "CHR must be 8KB, got ${chr.size} bytes" }
        val header = ByteArray(16)
        header[0] = 0x4E.toByte() // 'N'
        header[1] = 0x45.toByte() // 'E'
        header[2] = 0x53.toByte() // 'S'
        header[3] = 0x1A.toByte()
        header[4] = (prg.size / 16384).toByte()
        header[5] = 1 // one 8KB CHR bank
        header[6] = (if (mirroring == Mirroring.VERTICAL) 0x01 else 0x00).toByte()
        // bytes 7..15 stay zero: mapper 0, iNES (not NES 2.0)
        return header + prg + chr
    }
}
