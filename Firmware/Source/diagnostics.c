/*
 * diagnostics.c - Hardware bring-up firmware (ENABLE_DIAGNOSTICS = 1).
 *
 * WARNING: in this mode the keypad moves the servo freely. Never leave this
 * build installed on a real door.
 *
 * What it does (open a serial terminal at 9600 8N1 - via HC-05 + phone
 * "Serial Bluetooth Terminal" app, or a USB-UART adapter on PD0/PD1):
 *   1. Prints a banner. Readable text = 8 MHz clock + UART are correct.
 *   2. Scans I2C addresses 0x08..0x77 and prints every device that ACKs
 *      (the LCD backpack should appear at 0x27 or 0x3F).
 *   3. Loop:
 *      - every key press is printed on UART and LCD (keypad test)
 *      - servo calibration keys:
 *          1 = SERVO_LOCKED_US   3 = SERVO_UNLOCKED_US   5 = 1500 us
 *          4 / 6 = -10 / +10 us  7 / 9 = -100 / +100 us
 *      - every received UART line is echoed back as "RX:<line>"
 */
#include "config.h"

#if ENABLE_DIAGNOSTICS

#include <stdlib.h>
#include <avr/pgmspace.h>
#include "timebase.h"
#include "uart.h"
#include "i2c.h"
#include "lcd_i2c.h"
#include "keypad.h"
#include "servo.h"
#include "diagnostics.h"

static void print_hex8(uint8_t v)
{
    static const char hex[] PROGMEM = "0123456789ABCDEF";
    uart_puts_P(PSTR("0x"));
    uart_putc((char)pgm_read_byte(&hex[v >> 4]));
    uart_putc((char)pgm_read_byte(&hex[v & 0x0F]));
}

static void print_u16(uint16_t v)
{
    char buf[6];
    utoa(v, buf, 10);
    uart_puts(buf);
}

static void show_status(char key)
{
    char line[17];
    char num[6];
    uint8_t i = 0, j = 0;
    uint16_t pw = servo_get_pulse_us();

    line[i++] = 'K'; line[i++] = 'E'; line[i++] = 'Y'; line[i++] = ' ';
    line[i++] = key ? key : '-';
    line[i++] = ' '; line[i++] = 'P'; line[i++] = 'W'; line[i++] = ' ';
    utoa(pw, num, 10);
    while (num[j] && i < 14) line[i++] = num[j++];
    line[i++] = 'u'; line[i++] = 's';
    line[i] = '\0';
    lcd_line(1, line);
}

void diagnostics_run(void)
{
    uint8_t addr, found = 0;
    char    rx_line[33];
    uint8_t rx_len = 0;

    uart_puts_P(PSTR("\r\n=== SMART DOOR LOCK - DIAGNOSTICS MODE ===\r\n"));
    uart_puts_P(PSTR("If this text is readable at 9600 8N1, F_CPU = 8 MHz and UART are OK.\r\n"));
    uart_puts_P(PSTR("UART errors so far: "));
    print_u16(uart_error_count());
    uart_puts_P(PSTR("\r\nI2C scan (0x08..0x77):\r\n"));

    for (addr = 0x08; addr <= 0x77; addr++) {
        if (i2c_probe(addr)) {
            uart_puts_P(PSTR("  device at "));
            print_hex8(addr);
            uart_puts_P(PSTR("\r\n"));
            found++;
        }
    }
    if (!found) {
        uart_puts_P(PSTR("  no I2C device found - check SDA/SCL, VCC, GND, pull-ups\r\n"));
    }
    uart_puts_P(PSTR("LCD driver using address: "));
    print_hex8(lcd_address());
    uart_puts_P(lcd_present() ? PSTR(" (OK)\r\n") : PSTR(" (NOT FOUND)\r\n"));

    lcd_line_P(0, PSTR("DIAGNOSTICS"));
    show_status(0);
    uart_puts_P(PSTR("Keys: 1=lock 3=unlock 5=1500us 4/6=-+10us 7/9=-+100us\r\n"));

    for (;;) {
        uint32_t now = millis();
        char key = keypad_poll(now);
        int16_t c;

        if (key) {
            uint16_t pw = servo_get_pulse_us();
            switch (key) {
            case '1': servo_set_pulse_us(SERVO_LOCKED_US);   break;
            case '3': servo_set_pulse_us(SERVO_UNLOCKED_US); break;
            case '5': servo_set_pulse_us(1500U);             break;
            case '4': servo_set_pulse_us((uint16_t)(pw - 10U));  break;
            case '6': servo_set_pulse_us((uint16_t)(pw + 10U));  break;
            case '7': servo_set_pulse_us((uint16_t)(pw - 100U)); break;
            case '9': servo_set_pulse_us((uint16_t)(pw + 100U)); break;
            default: break;
            }
            uart_puts_P(PSTR("KEY "));
            uart_putc(key);
            uart_puts_P(PSTR("  servo pulse = "));
            print_u16(servo_get_pulse_us());
            uart_puts_P(PSTR(" us\r\n"));
            show_status(key);
        }

        while ((c = uart_getc()) >= 0) {
            if (c == '\n' || c == '\r') {
                if (rx_len) {
                    rx_line[rx_len] = '\0';
                    uart_puts_P(PSTR("RX:"));
                    uart_puts(rx_line);
                    uart_puts_P(PSTR("\r\n"));
                    rx_len = 0;
                }
            } else if (rx_len < sizeof(rx_line) - 1) {
                rx_line[rx_len++] = (c >= 0x20 && c <= 0x7E) ? (char)c : '?';
            }
        }
    }
}

#endif /* ENABLE_DIAGNOSTICS */
