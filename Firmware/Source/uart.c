/*
 * uart.c - Interrupt-driven USART with RX and TX ring buffers.
 *
 * 9600 baud @ 8 MHz, U2X = 0: UBRR = 51 -> 9615 baud (+0.16 % error).
 *
 * Error handling: if a byte arrives with a framing/overrun/parity error,
 * a 0x00 byte is stored in its place. If the RX buffer is full, the newest
 * stored byte is overwritten with 0x00. The protocol parser treats 0x00 as
 * an illegal character, so the damaged line is discarded instead of being
 * misread as a different command.
 */
#include "config.h"
#include <avr/io.h>
#include <avr/interrupt.h>
#include <avr/pgmspace.h>
#include <util/atomic.h>
#include "uart.h"

#define UBRR_VALUE  (((F_CPU) + (UART_BAUD) * 8UL) / (16UL * (UART_BAUD)) - 1UL)
#define BAUD_REAL   ((F_CPU) / (16UL * (UBRR_VALUE + 1UL)))
#define BAUD_ERR_PERMILLE ((BAUD_REAL > UART_BAUD) ? \
        ((BAUD_REAL - UART_BAUD) * 1000UL / UART_BAUD) : ((UART_BAUD - BAUD_REAL) * 1000UL / UART_BAUD))
#if BAUD_ERR_PERMILLE > 20
#error "UART baud error > 2 % for this F_CPU / UART_BAUD"
#endif

#define RX_MASK (UART_RX_BUF_SIZE - 1)
#define TX_MASK (UART_TX_BUF_SIZE - 1)

static volatile uint8_t rx_buf[UART_RX_BUF_SIZE];
static volatile uint8_t rx_head, rx_tail;
static volatile uint8_t tx_buf[UART_TX_BUF_SIZE];
static volatile uint8_t tx_head, tx_tail;
static volatile uint8_t err_count;

ISR(USART_RXC_vect)
{
    uint8_t status = UCSRA;          /* must be read before UDR */
    uint8_t data   = UDR;
    uint8_t next   = (uint8_t)((rx_head + 1) & RX_MASK);

    if (status & ((1 << FE) | (1 << DOR) | (1 << PE))) {
        data = 0x00;                 /* poison the line */
        if (err_count < 255) err_count++;
    }

    if (next == rx_tail) {           /* buffer full: poison newest byte, drop this one */
        rx_buf[(uint8_t)((rx_head - 1) & RX_MASK)] = 0x00;
        if (err_count < 255) err_count++;
        return;
    }
    rx_buf[rx_head] = data;
    rx_head = next;
}

ISR(USART_UDRE_vect)
{
    if (tx_head != tx_tail) {
        UDR = tx_buf[tx_tail];
        tx_tail = (uint8_t)((tx_tail + 1) & TX_MASK);
    } else {
        UCSRB &= (uint8_t)~(1 << UDRIE);   /* nothing left: stop the interrupt */
    }
}

void uart_init(void)
{
    UBRRH = (uint8_t)(UBRR_VALUE >> 8);
    UBRRL = (uint8_t)(UBRR_VALUE);
    UCSRA = 0;                                            /* U2X = 0 */
    UCSRC = (1 << URSEL) | (1 << UCSZ1) | (1 << UCSZ0);   /* 8N1 (URSEL selects UCSRC) */
    UCSRB = (1 << RXEN) | (1 << TXEN) | (1 << RXCIE);
    rx_head = rx_tail = 0;
    tx_head = tx_tail = 0;
}

int16_t uart_getc(void)
{
    uint8_t c;
    if (rx_head == rx_tail) {
        return -1;
    }
    c = rx_buf[rx_tail];
    rx_tail = (uint8_t)((rx_tail + 1) & RX_MASK);
    return c;
}

void uart_flush_rx(void)
{
    ATOMIC_BLOCK(ATOMIC_RESTORESTATE) {
        rx_tail = rx_head;
    }
}

void uart_putc(char c)
{
    uint8_t next = (uint8_t)((tx_head + 1) & TX_MASK);
    while (next == tx_tail) {
        /* TX buffer full: the UDRE interrupt drains it (~1 ms per byte). */
    }
    tx_buf[tx_head] = (uint8_t)c;
    tx_head = next;
    ATOMIC_BLOCK(ATOMIC_RESTORESTATE) {
        UCSRB |= (1 << UDRIE);
    }
}

void uart_puts(const char *s)
{
    while (*s) {
        uart_putc(*s++);
    }
}

void uart_puts_P(const char *s)
{
    char c;
    while ((c = (char)pgm_read_byte(s++)) != 0) {
        uart_putc(c);
    }
}

uint8_t uart_error_count(void)
{
    return err_count;
}
