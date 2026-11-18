/*
 * lcd_i2c.h - HD44780 16x2 LCD through a PCF8574 I2C backpack (4-bit mode).
 * If no LCD answers at init, every call becomes a no-op so the lock still works.
 */
#ifndef LCD_I2C_H_
#define LCD_I2C_H_

#include <stdint.h>

uint8_t lcd_init(void);                          /* returns detected 7-bit address, 0 if none */
uint8_t lcd_present(void);
uint8_t lcd_address(void);
void    lcd_clear(void);
void    lcd_set_cursor(uint8_t col, uint8_t row);
void    lcd_putc(char c);
void    lcd_line(uint8_t row, const char *s);    /* writes a full row, padded with spaces (RAM) */
void    lcd_line_P(uint8_t row, const char *s);  /* same, string in flash */
void    lcd_show_P(const char *l1, const char *l2);
void    lcd_backlight(uint8_t on);

#endif /* LCD_I2C_H_ */
