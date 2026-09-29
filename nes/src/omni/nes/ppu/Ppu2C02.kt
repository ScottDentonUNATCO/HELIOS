package omni.nes.ppu

import omni.nes.rom.Mirroring

/**
 * Clean-room NES PPU (2C02-ish) — original implementation from published
 * hardware facts. Scanline-stepped, cycle-approximate (not cycle-exact):
 * the PPU clock advances in whole CPU-instruction chunks, the background is
 * rendered per frame at vblank entry, and fine per-dot behaviors (odd-frame
 * cycle skip, exact sprite-overflow evaluation quirks, OAM decay) are not
 * modeled.
 *
 * What IS real:
 * - Loopy v/t/x/w scroll registers with real $2000/$2005/$2006/$2002
 *   interactions; t->v copy at frame start (pre-render behavior), so a game
 *   that sets scroll in its NMI handler gets it on the next frame.
 * - $2007 buffered reads (stale buffer first, except palette which returns
 *   immediately), +1/+32 increment from $2000 bit 2.
 * - 2KB nametable RAM with iNES horizontal/vertical mirroring, palette RAM
 *   with $3F10/$3F14/$3F18/$3F1C mirrors, CHR ROM or CHR-RAM.
 * - Background: per-pixel tile/attribute/palette fetch from frameV/frameX,
 *   256x240, left-8px clipping, forced blank, greyscale bit, backdrop color.
 * - Sprites: OAM, 8x8, 4 palettes, flip bits, front/behind priority,
 *   left-8px clipping, max 8 per scanline enforced in OAM order with the
 *   sprite-overflow flag ($2002 bit 5), sprite-0-hit ($2002 bit 6).
 * - vblank flag ($2002 bit 7) set at scanline 241, cleared by $2002 reads
 *   (which also reset the $2005/$2006 write toggle), NMI edge latched when
 *   $2000 bit 7 is set.
 * - $4014 OAM DMA (256 bytes from a CPU page, OAMADDR-relative wrap).
 *
 * Deliberately NOT modeled (documented approximations):
 * - 8x16 sprites (PPUCTRL bit 5 is ignored; tall sprites render as 8x8).
 * - Sprite-overflow exact hardware quirk (the diagonal-evaluation bug); the
 *   flag is set whenever >8 sprites share a scanline while rendering is on.
 * - Sprite-0-hit is evaluated per rendered scanline with the standard
 *   rules (needs bg+sprites enabled, x != 255, left-clip rule); mid-frame
 *   register changes don't move it.
 * - Emphasis bits and the $3F00 "v in palette" read quirk.
 * - NTSC composite decoding: colors come from a fixed 64-entry decoded
 *   RGB table (a widely-adopted 2C02 NTSC decode; not Nintendo data).
 *
 * Timing: one frame = 89340 PPU dots = 29780 CPU cycles (341 dots x 262
 * scanlines / 3). Scanlines 0-239 visible, 241-261 vblank-ish; the frame is
 * rendered when scanline 241 is entered. The CPU-side driver polls
 * [takeFrame] (frame rendered) and [takeNmi] (NMI edge, latched only when
 * $2000 bit 7 was set at vblank entry).
 */
class Ppu2C02(
    chrRom: ByteArray,
    chrIsRam: Boolean,
    val mirroring: Mirroring,
) {
    // ---- VRAM ----
    // 8KB CHR address space: ROM-backed (writes ignored) or RAM (writable).
    private val chr: ByteArray = if (chrIsRam) ByteArray(8192) else ByteArray(8192).also {
        chrRom.copyInto(it, 0, 0, chrRom.size.coerceAtMost(8192))
    }
    private val chrWritable = chrIsRam
    private val nameTable = ByteArray(2048)
    private val paletteRam = ByteArray(32)

    // ---- OAM ----
    val oam = ByteArray(256)
    var oamAddr = 0
        private set

    // ---- loopy scroll registers ----
    var v = 0 // current VRAM address (15 bits)
        private set
    private var t = 0 // temp VRAM address (15 bits)
    private var fineX = 0 // fine X scroll (3 bits)
    private var writeLatch = false // $2005/$2006 first/second write

    // ---- control/mask ----
    var ppuctrl = 0
        private set
    var ppumask = 0
        private set

    // ---- flags ----
    var vblankFlag = false
        private set
    var spriteZeroHit = false
        private set
    var spriteOverflow = false
        private set

    // ---- timing ----
    private var dots = 0L // dots elapsed in the current frame, 0..89339
    private var readBuffer = 0

    // ---- frame handoff to the CPU-side driver ----
    private var nmiLatched = false
    private var frameLatched = false
    private var frameV = 0 // v snapshotted at frame start (after t->v)
    private var frameX = 0 // fineX snapshotted at frame start

    /** 256x240 pixels as 0xRRGGBB, valid after [takeFrame] returns true. */
    val framebuffer = IntArray(256 * 240)

    companion object {
        const val DOTS_PER_SCANLINE = 341
        const val VBLANK_SCANLINE = 241
        const val FRAME_DOTS = 89340L // 29780 CPU cycles * 3
        const val VBLANK_DOTS = (VBLANK_SCANLINE * DOTS_PER_SCANLINE).toLong() // 82181

        /** Widely-adopted 2C02 NTSC decoded palette, index $00..$3F, 0xRRGGBB. */
        val MASTER_PALETTE = intArrayOf(
            0x7C7C7C, 0x0000FC, 0x0000BC, 0x4428BC, 0x940084, 0xA80020, 0xA81000, 0x881400,
            0x503000, 0x007800, 0x006800, 0x005800, 0x004058, 0x000000, 0x000000, 0x000000,
            0xBCBCBC, 0x0078F8, 0x0058F8, 0x6844FC, 0xD800CC, 0xE40058, 0xF83800, 0xE45C10,
            0xAC7C00, 0x00B800, 0x00A800, 0x00A844, 0x008888, 0x000000, 0x000000, 0x000000,
            0xF8F8F8, 0x3CBCFC, 0x6888FC, 0x9878F8, 0xF878F8, 0xF85898, 0xF87858, 0xFCA044,
            0xF8B800, 0xB8F818, 0x58D854, 0x58F898, 0x00E8D8, 0x787878, 0x000000, 0x000000,
            0xFCFCFC, 0xA4E4FC, 0xB8B8F8, 0xD8B8F8, 0xF8B8F8, 0xF8A4C0, 0xF0D0B0, 0xFCE0A8,
            0xF8D878, 0xD8F878, 0xB8F8B8, 0xB8F8D8, 0x00FCFC, 0xF8D8F8, 0x000000, 0x000000,
        )
    }

    // ================= register interface (called by the bus) =================

    fun readReg(r: Int): Int = when (r) {
        2 -> {
            var s = 0
            if (vblankFlag) s = s or 0x80
            if (spriteZeroHit) s = s or 0x40
            if (spriteOverflow) s = s or 0x20
            // Reading $2002 clears vblank and resets the $2005/$2006 latch.
            vblankFlag = false
            writeLatch = false
            s
        }
        4 -> oam[oamAddr].toInt() and 0xFF
        7 -> readData()
        else -> 0 // $2000/$2001/$2003/$2005/$2006 are write-only on hardware
    }

    fun writeReg(r: Int, value: Int) {
        val v8 = value and 0xFF
        when (r) {
            0 -> {
                ppuctrl = v8
                t = (t and 0xF3FF) or ((v8 and 0x03) shl 10)
            }
            1 -> ppumask = v8
            3 -> oamAddr = v8
            4 -> {
                oam[oamAddr] = v8.toByte()
                oamAddr = (oamAddr + 1) and 0xFF
            }
            5 -> {
                if (!writeLatch) {
                    t = (t and 0xFFE0) or (v8 ushr 3)
                    fineX = v8 and 0x07
                    writeLatch = true
                } else {
                    t = (t and 0x8FFF) or ((v8 and 0x07) shl 12)
                    t = (t and 0xFC1F) or ((v8 and 0xF8) shl 2)
                    writeLatch = false
                }
            }
            6 -> {
                if (!writeLatch) {
                    t = (t and 0x80FF) or ((v8 and 0x3F) shl 8)
                    writeLatch = true
                } else {
                    t = (t and 0xFF00) or v8
                    v = t
                    writeLatch = false
                }
            }
            7 -> {
                writeData(v8)
                v = (v + if (ppuctrl and 0x04 != 0) 32 else 1) and 0x7FFF
            }
            // $2002 writes are ignored on hardware.
        }
    }

    /** $4014 OAM DMA: copy 256 bytes from CPU page [page] via [cpuRead]. */
    fun oamDma(page: Int, cpuRead: (Int) -> Int) {
        for (i in 0 until 256) {
            oam[(oamAddr + i) and 0xFF] = cpuRead(((page and 0xFF) shl 8) or i).toByte()
        }
        advanceDots(513) // DMA stall, cycle-approximate (real: 513/514)
    }

    // ================= timing =================

    /**
     * Advance the PPU clock by [n] dots. Entering scanline 241 renders the
     * frame and latches vblank/NMI; wrapping past the frame end performs the
     * pre-render t->v copy and clears the scanline flags.
     */
    fun advanceDots(n: Long) {
        var remaining = n
        while (remaining > 0) {
            val toVblank = VBLANK_DOTS - dots
            val toWrap = FRAME_DOTS - dots
            var step = remaining
            if (toVblank > 0 && toVblank < step) step = toVblank
            if (toWrap < step) step = toWrap
            dots += step
            remaining -= step
            if (dots == VBLANK_DOTS) enterVblank()
            if (dots == FRAME_DOTS) startFrame()
        }
    }

    /** True once per rendered frame; consumes the latch. */
    fun takeFrame(): Boolean {
        val f = frameLatched
        frameLatched = false
        return f
    }

    /** True once per vblank entry while $2000 bit 7 was set; consumes it. */
    fun takeNmi(): Boolean {
        val n = nmiLatched
        nmiLatched = false
        return n
    }

    private fun enterVblank() {
        vblankFlag = true
        renderFrame()
        frameLatched = true
        if (ppuctrl and 0x80 != 0) nmiLatched = true
    }

    private fun startFrame() {
        dots = 0
        v = t // pre-render copy, like hardware
        frameV = v
        frameX = fineX
        vblankFlag = false
        spriteZeroHit = false
        spriteOverflow = false
    }

    // ================= VRAM =================

    private fun chrRead(addr: Int): Int = chr[addr and 0x1FFF].toInt() and 0xFF

    private fun ntPhys(addr: Int): Int {
        val a = addr and 0x0FFF // $3000-$3FFF mirrors $2000-$2FFF
        val page = a ushr 10 // 0..2 ($2C00+ region folds into 2)
        val physPage = when (mirroring) {
            Mirroring.HORIZONTAL -> page / 2 // $2000=$2400, $2800=$2C00
            Mirroring.VERTICAL -> page % 2 // $2000=$2800, $2400=$2C00
        }
        return physPage * 0x400 + (a and 0x3FF)
    }

    private fun paletteIndex(addr: Int): Int {
        var p = addr and 0x1F
        if (p == 0x10 || p == 0x14 || p == 0x18 || p == 0x1C) p -= 0x10
        return p
    }

    private fun memRead(addr: Int): Int {
        val a = addr and 0x3FFF
        return when {
            a < 0x2000 -> chrRead(a)
            a < 0x3F00 -> nameTable[ntPhys(a)].toInt() and 0xFF
            else -> paletteRam[paletteIndex(a)].toInt() and 0x3F
        }
    }

    private fun memWrite(addr: Int, value: Int) {
        val a = addr and 0x3FFF
        val v8 = value and 0xFF
        when {
            a < 0x2000 -> if (chrWritable) chr[a] = v8.toByte() // CHR-RAM; CHR ROM writes ignored
            a < 0x3F00 -> nameTable[ntPhys(a)] = v8.toByte()
            else -> paletteRam[paletteIndex(a)] = (v8 and 0x3F).toByte()
        }
    }

    /** $2007 read with the real hardware read buffer. */
    private fun readData(): Int {
        val a = v and 0x3FFF
        val result = if (a >= 0x3F00) {
            // Palette reads return immediately; the buffer still loads the
            // mirrored nametable byte underneath.
            readBuffer = memRead(a and 0x2FFF)
            memRead(a)
        } else {
            val r = readBuffer
            readBuffer = memRead(a)
            r
        }
        v = (v + if (ppuctrl and 0x04 != 0) 32 else 1) and 0x7FFF
        return result
    }

    private fun writeData(value: Int) = memWrite(v and 0x3FFF, value)

    // ================= rendering =================

    private fun rgb(nesColor: Int): Int {
        var c = MASTER_PALETTE[nesColor and 0x3F]
        if (ppumask and 0x01 != 0) c = MASTER_PALETTE[(nesColor and 0x30)] // greyscale
        return c
    }

    private fun renderFrame() {
        val bgOn = ppumask and 0x08 != 0
        val sprOn = ppumask and 0x10 != 0
        val backdrop = rgb(paletteRam[0].toInt() and 0x3F)
        if (!bgOn && !sprOn) {
            framebuffer.fill(backdrop)
            return
        }
        val bgClip = ppumask and 0x02 == 0 // hide bg in left 8 px
        val sprClip = ppumask and 0x04 == 0 // hide sprites in left 8 px
        val sprBase = if (ppuctrl and 0x08 != 0) 0x1000 else 0x0000
        val bgBase = if (ppuctrl and 0x10 != 0) 0x1000 else 0x0000
        val scrollX = ((frameV and 0x1F) shl 3) or frameX
        val scrollY = (((frameV ushr 5) and 0x1F) shl 3) or ((frameV ushr 12) and 0x07)
        val baseNt = (frameV ushr 10) and 0x03

        for (sl in 0 until 240) {
            // Sprite evaluation for this scanline (OAM order, max 8 enforced).
            var sprCount = 0
            val lineSprites = IntArray(8) // OAM sprite indices, at most 8
            if (sprOn) {
                for (i in 0 until 64) {
                    val y = oam[i * 4].toInt() and 0xFF
                    if (sl >= y + 1 && sl <= y + 8) {
                        if (sprCount < 8) lineSprites[sprCount] = i
                        sprCount++
                    }
                }
                if (sprCount > 8) spriteOverflow = true
            }
            val visible = sprCount.coerceAtMost(8)

            val ty = scrollY + sl
            val tyw = ty % 240
            val tileY = tyw ushr 3
            val fineY = tyw and 0x07
            val ntY = (ty / 240) and 0x01

            for (px in 0 until 256) {
                // ---- background pixel ----
                var bgIdx = 0
                var bgNes = paletteRam[0].toInt() and 0x3F
                val bgVisible = bgOn && !(px < 8 && bgClip)
                if (bgVisible) {
                    val tx = scrollX + px
                    val tileX = (tx ushr 3) and 0x1F
                    val fineXb = tx and 0x07
                    val ntSel = baseNt xor ((tx ushr 8) and 0x01) xor (ntY shl 1)
                    val ntAddr = 0x2000 + ntSel * 0x400 + tileY * 32 + tileX
                    val tile = memRead(ntAddr)
                    val attrAddr = 0x23C0 + ntSel * 0x400 + (tileY ushr 2) * 8 + (tileX ushr 2)
                    val attr = memRead(attrAddr)
                    val pal = (attr ushr (((tileY and 0x02) shl 1) or (tileX and 0x02))) and 0x03
                    val b0 = chrRead(bgBase + tile * 16 + fineY)
                    val b1 = chrRead(bgBase + tile * 16 + 8 + fineY)
                    val bit = 7 - fineXb
                    bgIdx = ((b0 ushr bit) and 0x01) or (((b1 ushr bit) and 0x01) shl 1)
                    if (bgIdx != 0) bgNes = paletteRam[pal * 4 + bgIdx].toInt() and 0x3F
                }

                // ---- sprite pixel: first opaque sprite in OAM order ----
                var sprNes = -1
                var sprBehind = false
                var sprIsZero = false
                if (sprOn && !(px < 8 && sprClip)) {
                    var s = 0
                    while (s < visible) {
                        val i = lineSprites[s]
                        val sy = oam[i * 4].toInt() and 0xFF
                        val tile = oam[i * 4 + 1].toInt() and 0xFF
                        val attr = oam[i * 4 + 2].toInt() and 0xFF
                        val sx = oam[i * 4 + 3].toInt() and 0xFF
                        if (px >= sx && px < sx + 8) {
                            var row = sl - (sy + 1)
                            if (attr and 0x80 != 0) row = 7 - row
                            val dx = px - sx
                            val bit = if (attr and 0x40 != 0) dx else 7 - dx
                            val b0 = chrRead(sprBase + tile * 16 + row)
                            val b1 = chrRead(sprBase + tile * 16 + 8 + row)
                            val c = ((b0 ushr bit) and 0x01) or (((b1 ushr bit) and 0x01) shl 1)
                            if (c != 0) {
                                sprNes = paletteRam[0x10 + (attr and 0x03) * 4 + c].toInt() and 0x3F
                                sprBehind = attr and 0x20 != 0
                                sprIsZero = i == 0
                                break
                            }
                        }
                        s++
                    }
                }

                // ---- sprite-0 hit ----
                if (sprIsZero && bgIdx != 0 && bgVisible && px != 255) {
                    spriteZeroHit = true
                }

                // ---- priority mux ----
                val out = when {
                    sprNes >= 0 && (!sprBehind || bgIdx == 0) -> rgb(sprNes)
                    bgIdx != 0 && bgVisible -> rgb(bgNes)
                    else -> backdrop
                }
                framebuffer[sl * 256 + px] = out
            }
        }
    }
}
