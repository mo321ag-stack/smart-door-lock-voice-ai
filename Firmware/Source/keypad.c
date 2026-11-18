/*
 * keypad.c - 4x4 matrix keypad scan + debounce.
 *
 * Rows R1..R4 = PA0..PA3, columns C1..C4 = PA4..PA7.
 * Idle: all PORTA pins are inputs with pull-ups.
 * Scan: one row at a time is driven LOW (the other rows stay high-impedance
 * inputs, so two pressed keys can never short two outputs together); a
 * pressed key in that row pulls its column LOW.
 * If more than one key is detected the scan returns "no key" (prevents
 * ghost keys from being accepted as PIN digits).
 */
#include "config.h"
#include <avr/io.h>
#include <avr/pgmspace.h>
#include <util/delay.h>
#include "timebase.h"
#include "keypad.h"

static const char keymap[4][4] PROGMEM = {
    { '1', '2', '3', 'A' },   /* R1 */
    { '4', '5', '6', 'B' },   /* R2 */
    { '7', '8', '9', 'C' },   /* R3 */
    { '*', '0', '#', 'D' }    /* R4 */
};

void keypad_init(void)
{
    SFIOR &= (uint8_t)~(1 << PUD);   /* make sure pull-ups are globally enabled */
    DDRA  = 0x00;
    PORTA = 0xFF;
}

char keypad_scan_raw(void)
{
    char    key   = 0;
    uint8_t count = 0;
    uint8_t row, col, cols;

    for (row = 0; row < 4; row++) {
        DDRA  = (uint8_t)(1 << row);             /* only this row is an output */
        PORTA = (uint8_t)(0xFF & ~(1 << row));   /* drive it LOW, pull-ups elsewhere */
        _delay_us(10);                           /* let the line settle */
        cols = (uint8_t)((~PINA >> 4) & 0x0F);   /* 1 = pressed */
        for (col = 0; col < 4; col++) {
            if (cols & (1 << col)) {
                key = (char)pgm_read_byte(&keymap[row][col]);
                count++;
            }
        }
    }
    DDRA  = 0x00;
    PORTA = 0xFF;

    return (count == 1) ? key : 0;
}

char keypad_poll(uint32_t now)
{
    static uint32_t last_scan;
    static uint32_t cand_since;
    static char     candidate;
    static char     stable;
    char raw;

    if (!time_elapsed(now, last_scan, KEYPAD_SCAN_INTERVAL_MS)) {
        return 0;
    }
    last_scan = now;

    raw = keypad_scan_raw();
    if (raw != candidate) {
        candidate  = raw;
        cand_since = now;
        return 0;
    }
    if (candidate != stable && time_elapsed(now, cand_since, KEYPAD_DEBOUNCE_MS)) {
        stable = candidate;
        return stable;      /* key char on press, 0 on release */
    }
    return 0;
}
