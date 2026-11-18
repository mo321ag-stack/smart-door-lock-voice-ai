/*
 * lcd_i2c.c - HD44780 in 4-bit mode behind a PCF8574.
 *
 * PCF8574 mapping (config.h): P0=RS P1=RW P2=EN P3=Backlight P4..P7=D4..D7.
 * Each nibble is sent in ONE I2C transaction of three bytes:
 *   data (EN=0) -> data (EN=1) -> data (EN=0)
 * At 100 kHz each byte takes ~90 us, which satisfies the HD44780 EN pulse
 * width and setup/hold times. RW is always 0 (write only).
 */
#include "config.h"
#include <avr/io.h>
#include <avr/pgmspace.h>
#include <util/delay.h>
#include "i2c.h"
#include "lcd_i2c.h"

#define LCD_RS (1 << LCD_BIT_RS)
#define LCD_EN (1 << LCD_BIT_EN)
#define LCD_BL (1 << LCD_BIT_BL)

#define CMD_CLEAR        0x01
#define CMD_ENTRY_MODE   0x06   /* increment, no shift */
#define CMD_DISPLAY_OFF  0x08
#define CMD_DISPLAY_ON   0x0C   /* display on, cursor off, blink off */
#define CMD_FUNCTION_4B  0x28   /* 4-bit, 2 lines, 5x8 */
#define CMD_SET_DDRAM    0x80

static uint8_t s_addr;
static uint8_t s_present;
static uint8_t s_bl = LCD_BL;

/* Send the upper 4 bits of 'nib' with RS = rs. */
static void write_nibble(uint8_t nib, uint8_t rs)
{
    uint8_t v;
    if (!s_present) {
        return;
    }
    v = (uint8_t)((nib & 0xF0) | (rs ? LCD_RS : 0) | s_bl);
    if (i2c_start(s_addr, 0) == I2C_OK) {
        i2c_write(v);
        i2c_write((uint8_t)(v | LCD_EN));
        i2c_write(v);
    }
    i2c_stop();
}

static void lcd_send(uint8_t value, uint8_t rs)
{
    write_nibble(value & 0xF0, rs);
    write_nibble((uint8_t)(value << 4), rs);
}

static void lcd_cmd(uint8_t c)
{
    lcd_send(c, 0);
    if (c == CMD_CLEAR || c == 0x02) {
        _delay_ms(2);           /* clear / home need 1.52 ms */
    } else {
        _delay_us(50);          /* other commands need 37 us */
    }
}

uint8_t lcd_init(void)
{
    s_present = 0;
    _delay_ms(50);              /* HD44780 needs >40 ms after VCC rises */

#if LCD_AUTO_DETECT
    if (i2c_probe(LCD_I2C_ADDR_PRIMARY)) {
        s_addr = LCD_I2C_ADDR_PRIMARY;
    } else if (i2c_probe(LCD_I2C_ADDR_SECONDARY)) {
        s_addr = LCD_I2C_ADDR_SECONDARY;
    } else {
        return 0;
    }
#else
    s_addr = LCD_I2C_ADDR_PRIMARY;
    if (!i2c_probe(s_addr)) {
        return 0;
    }
#endif
    s_present = 1;

    /* Initialization by instruction (HD44780 datasheet, figure 24). */
    write_nibble(0x30, 0); _delay_ms(5);
    write_nibble(0x30, 0); _delay_ms(1);
    write_nibble(0x30, 0); _delay_ms(1);
    write_nibble(0x20, 0); _delay_ms(1);   /* switch to 4-bit */

    lcd_cmd(CMD_FUNCTION_4B);
    lcd_cmd(CMD_DISPLAY_OFF);
    lcd_cmd(CMD_CLEAR);
    lcd_cmd(CMD_ENTRY_MODE);
    lcd_cmd(CMD_DISPLAY_ON);
    return s_addr;
}

uint8_t lcd_present(void) { return s_present; }
uint8_t lcd_address(void) { return s_present ? s_addr : 0; }

void lcd_clear(void)
{
    lcd_cmd(CMD_CLEAR);
}

void lcd_set_cursor(uint8_t col, uint8_t row)
{
    uint8_t base = (row & 1) ? 0x40 : 0x00;
    lcd_cmd((uint8_t)(CMD_SET_DDRAM | (base + col)));
}

void lcd_putc(char c)
{
    lcd_send((uint8_t)c, 1);
}

void lcd_line(uint8_t row, const char *s)
{
    uint8_t i = 0;
    lcd_set_cursor(0, row);
    while (i < LCD_COLS && s[i]) {
        lcd_putc(s[i]);
        i++;
    }
    while (i++ < LCD_COLS) {
        lcd_putc(' ');
    }
}

void lcd_line_P(uint8_t row, const char *s)
{
    uint8_t i = 0;
    char c;
    lcd_set_cursor(0, row);
    while (i < LCD_COLS && (c = (char)pgm_read_byte(s + i)) != 0) {
        lcd_putc(c);
        i++;
    }
    while (i++ < LCD_COLS) {
        lcd_putc(' ');
    }
}

void lcd_show_P(const char *l1, const char *l2)
{
    lcd_line_P(0, l1);
    lcd_line_P(1, l2);
}

void lcd_backlight(uint8_t on)
{
    s_bl = on ? LCD_BL : 0;
    if (s_present) {
        if (i2c_start(s_addr, 0) == I2C_OK) {
            i2c_write(s_bl);
        }
        i2c_stop();
    }
}
