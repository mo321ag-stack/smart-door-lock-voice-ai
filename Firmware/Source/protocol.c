/*
 * protocol.c - Line parser and response sender for the Bluetooth protocol.
 *
 * Bytes are taken from the UART RX ring buffer without blocking. Partial
 * lines are kept between calls, so commands split over several Bluetooth
 * packets are handled. Rules:
 *   '\n'                -> end of line, decode it
 *   '\r'                -> ignored (CRLF tolerant)
 *   byte < 0x20 / > 0x7E -> line marked bad (also catches UART error marker 0x00)
 *   more than PROTO_MAX_LINE_LEN chars -> line marked bad
 * Bad lines are reported as PROTO_CMD_INVALID; empty lines are ignored.
 */
#include "config.h"
#include <string.h>
#include <avr/pgmspace.h>
#include "uart.h"
#include "protocol.h"

static char    s_line[PROTO_MAX_LINE_LEN + 1];
static uint8_t s_len;
static uint8_t s_bad;

void protocol_init(void)
{
    s_len = 0;
    s_bad = 0;
}

static proto_cmd_t decode_line(void)
{
    s_line[s_len] = '\0';
    if (strcmp_P(s_line, PSTR("VOICE_OK")) == 0)   return PROTO_CMD_VOICE_OK;
    if (strcmp_P(s_line, PSTR("VOICE_FAIL")) == 0) return PROTO_CMD_VOICE_FAIL;
    if (strcmp_P(s_line, PSTR("LOCK")) == 0)       return PROTO_CMD_LOCK;
    if (strcmp_P(s_line, PSTR("STATUS")) == 0)     return PROTO_CMD_STATUS;
    return PROTO_CMD_INVALID;
}

proto_cmd_t protocol_poll(void)
{
    int16_t c;

    while ((c = uart_getc()) >= 0) {
        uint8_t b = (uint8_t)c;

        if (b == '\n') {
            proto_cmd_t cmd;
            if (s_bad) {
                cmd = PROTO_CMD_INVALID;
            } else if (s_len == 0) {
                cmd = PROTO_CMD_NONE;          /* empty line: ignore */
            } else {
                cmd = decode_line();
            }
            s_len = 0;
            s_bad = 0;
            if (cmd != PROTO_CMD_NONE) {
                return cmd;                    /* remaining bytes stay buffered */
            }
            continue;
        }
        if (b == '\r' || s_bad) {
            continue;
        }
        if (b < 0x20 || b > 0x7E || s_len >= PROTO_MAX_LINE_LEN) {
            s_bad = 1;
            continue;
        }
        s_line[s_len++] = (char)b;
    }
    return PROTO_CMD_NONE;
}

void protocol_send(proto_rsp_t rsp)
{
    switch (rsp) {
    case PROTO_RSP_LOCKED:        uart_puts_P(PSTR("LOCKED\n"));        break;
    case PROTO_RSP_WAITING_PIN:   uart_puts_P(PSTR("WAITING_PIN\n"));   break;
    case PROTO_RSP_UNLOCKED:      uart_puts_P(PSTR("UNLOCKED\n"));      break;
    case PROTO_RSP_ACCESS_DENIED: uart_puts_P(PSTR("ACCESS_DENIED\n")); break;
    default: break;
    }
}
