/*
 * uart.h - Interrupt-driven USART (PD0/RXD, PD1/TXD) for the HC-05.
 * 9600 baud, 8 data bits, no parity, 1 stop bit, no flow control.
 */
#ifndef UART_H_
#define UART_H_

#include <stdint.h>

void    uart_init(void);
int16_t uart_getc(void);              /* next received byte, or -1 if none (non-blocking) */
void    uart_flush_rx(void);          /* drop everything received so far */
void    uart_putc(char c);            /* queues a byte; waits only if the TX buffer is full */
void    uart_puts(const char *s);     /* string in RAM */
void    uart_puts_P(const char *s);   /* string in flash (PSTR) */
uint8_t uart_error_count(void);       /* framing/overrun/buffer-full events seen */

#endif /* UART_H_ */
