; OMNI Phase 1 proof ROM — a real tiny NES demo (NROM-128).
; Assembled with omni.nes.asm.Assembler (clean-room two-pass assembler).
; Layout: PRG .org $C000, vectors at $FFFA -> exactly 16384 bytes ($C000-$FFFF).
; Behavior: inits the NES, uploads a palette with rendering off (forced blank,
; which is legal), places sprite 0 at (128,128), enables NMI + rendering, then
; the NMI handler slides sprite 0 right one pixel per frame via OAM DMA.

.org $C000

; ---------------- RESET ----------------
RESET:
    SEI                 ; no IRQs while we set up
    CLD                 ; binary arithmetic
    LDX #$40
    STX $4017           ; disable APU frame IRQ
    LDX #$00
    STX $4010           ; disable DMC IRQs
    STX $2000           ; NMI off
    STX $2001           ; rendering off
    STX $4015           ; all APU channels off
    DEX
    TXS                 ; stack pointer = $FF
    INX                 ; X = 0 again

vblank1:
    BIT $2002
    BPL vblank1         ; wait for first vblank (PPU warm-up)

    ; clear $0000-$07FF RAM (256 bytes x 8 pages)
    TXA                 ; A = 0
clear:
    STA $0000,X
    STA $0100,X
    STA $0200,X
    STA $0300,X
    STA $0400,X
    STA $0500,X
    STA $0600,X
    STA $0700,X
    INX
    BNE clear

vblank2:
    BIT $2002
    BPL vblank2         ; wait for second vblank

    ; palette upload with rendering OFF (forced blank — legal on hardware)
    LDA #$3F
    STA $2006
    LDA #$00
    STA $2006           ; PPUADDR = $3F00
    LDX #$00
pal_loop:
    LDA palette,X       ; forward label -> absolute, both passes agree
    STA $2007
    INX
    CPX #$20            ; 32 bytes
    BNE pal_loop

    ; sprite 0 in OAM shadow at $0200: Y, tile, attr, X
    LDA #$80
    STA $0200           ; Y = 128
    LDA #$01
    STA $0201           ; tile 1
    LDA #$00
    STA $0202           ; attributes: palette 0, no flip
    LDA #$80
    STA $0203           ; X = 128

    CLI                 ; interrupts on: the NMI handler below is the frame loop
    LDA #%10000000
    STA $2000           ; NMI on, sprites use pattern table $0000
    LDA #%00011110
    STA $2001           ; rendering on: bg + sprites, no clipping

main:
    JMP main            ; idle; the NMI handler does the work

; ---------------- NMI (vertical blank) ----------------
NMI:
    PHA
    TXA
    PHA                 ; preserve A and X
    INC $0203           ; slide sprite 0 one pixel right each frame
    LDA #$02
    STA $4014           ; OAM DMA from page $0200
    PLA
    TAX
    PLA                 ; restore X and A
    RTI

palette:
    .byte $0F,$0F,$0F,$0F, $0F,$16,$26,$36, $0F,$0F,$0F,$0F, $0F,$0F,$0F,$0F
    ; sprite palettes: palette 0/1 get real colors so sprite 0 (tile 1, color 1)
    ; renders visibly ($16 red) instead of black-on-black
    .byte $0F,$16,$26,$36, $0F,$16,$26,$36, $0F,$0F,$0F,$0F, $0F,$0F,$0F,$0F

; ---------------- vectors ----------------
    .org $FFFA          ; zero-pads the gap -> PRG is exactly 16384 bytes
    .word NMI, RESET, $0000
