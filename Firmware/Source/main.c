/*
 * main.c - Smart Door Lock firmware entry point.
 *
 * Target : ATmega32 (DIP-40), 8 MHz external crystal, AVR-GCC.
 * Startup order is chosen for safety: the servo PWM is started at the
 * LOCKED position before anything else, so the door is locked from the
 * first 20 ms after reset regardless of what happens next.
 *
 * Main loop (non-blocking):
 *   1. reset watchdog
 *   2. take at most one Bluetooth command from the UART buffer
 *   3. take at most one debounced key press
 *   4. run state-machine timeouts
 */
#include "config.h"
#include <avr/io.h>
#include <avr/interrupt.h>
#include <avr/wdt.h>
#include <avr/pgmspace.h>
#include <util/delay.h>
#include "timebase.h"
#include "uart.h"
#include "i2c.h"
#include "lcd_i2c.h"
#include "keypad.h"
#include "servo.h"
#include "protocol.h"
#include "authentication.h"
#include "diagnostics.h"

/* Pull-ups on all pins this project does not use, so nothing floats. */
static void init_unused_pins(void)
{
    DDRB   = 0x00;
    PORTB  = 0xFF;                       /* PB0..PB7 (PB5..PB7 = ISP) */
    DDRC  &= 0x03;
    PORTC |= 0xFC;                       /* PC2..PC7 */
    DDRD  &= (uint8_t)~0xDD;             /* PD0, PD2..PD4, PD6, PD7 as inputs */
    PORTD |= 0xDD;                       /* pull-ups (PD0 = RXD: idle high if HC-05 absent) */
}

int main(void)
{
    uint8_t reset_flags = MCUCSR;
    MCUCSR = 0;
    wdt_disable();

    servo_init();                        /* FIRST: door locked */
    init_unused_pins();
    timebase_init();
    uart_init();
    i2c_init();
    keypad_init();
    sei();

    lcd_init();                          /* auto-detects 0x27 / 0x3F; no-op if absent */

#if ENABLE_DIAGNOSTICS
    diagnostics_run();                   /* never returns */
#endif

    lcd_show_P(PSTR("WELCOME"),
               (reset_flags & (1 << WDRF)) ? PSTR("WATCHDOG RESET") : PSTR("SMART DOOR LOCK"));
    {
        uint8_t i;
        for (i = 0; i < 150; i++) {      /* 1.5 s splash */
            _delay_ms(10);
        }
    }

    uart_flush_rx();                     /* ignore anything received during boot */
    protocol_init();

#if USE_WATCHDOG
    wdt_enable(WDTO_1S);
#endif

    auth_init(millis());                 /* enters LOCKED (or lockout) and sends LOCKED */

    for (;;) {
        uint32_t    now;
        proto_cmd_t cmd;
        char        key;

        wdt_reset();
        now = millis();

        cmd = protocol_poll();
        if (cmd != PROTO_CMD_NONE) {
            auth_on_command(cmd, now);
        }

        key = keypad_poll(now);
        if (key) {
            auth_on_key(key, now);
        }

        auth_task(millis());
    }
}
