/*
 * protocol.h - Text protocol between the Android app and the ATmega32.
 *
 *   Android -> MCU : VOICE_OK\n  VOICE_FAIL\n  LOCK\n  STATUS\n
 *   MCU -> Android : LOCKED\n    WAITING_PIN\n UNLOCKED\n ACCESS_DENIED\n
 *
 * Case-sensitive. '\r' is ignored so CRLF senders also work. Lines that are
 * too long, contain non-printable bytes, or were hit by a UART error are
 * discarded silently. The PIN is never part of this protocol.
 */
#ifndef PROTOCOL_H_
#define PROTOCOL_H_

typedef enum {
    PROTO_CMD_NONE = 0,     /* no complete line yet */
    PROTO_CMD_VOICE_OK,
    PROTO_CMD_VOICE_FAIL,
    PROTO_CMD_LOCK,
    PROTO_CMD_STATUS,
    PROTO_CMD_INVALID       /* complete line that is not a valid command */
} proto_cmd_t;

typedef enum {
    PROTO_RSP_LOCKED = 0,
    PROTO_RSP_WAITING_PIN,
    PROTO_RSP_UNLOCKED,
    PROTO_RSP_ACCESS_DENIED
} proto_rsp_t;

void        protocol_init(void);
proto_cmd_t protocol_poll(void);           /* non-blocking; returns at most one command */
void        protocol_send(proto_rsp_t rsp);

#endif /* PROTOCOL_H_ */
