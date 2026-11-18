/*
 * authentication.h - Door lock state machine (voice stage + local PIN stage).
 */
#ifndef AUTHENTICATION_H_
#define AUTHENTICATION_H_

#include <stdint.h>
#include "protocol.h"

typedef enum {
    AUTH_LOCKED = 0,          /* servo locked, showing a lock message; -> WAITING_VOICE */
    AUTH_WAITING_VOICE,       /* idle, waiting for VOICE_OK from Android */
    AUTH_VOICE_VERIFIED,      /* VOICE_OK accepted, PIN session started */
    AUTH_WAITING_PIN,         /* collecting PIN on the keypad */
    AUTH_UNLOCKED,            /* servo unlocked for DOOR_UNLOCK_MS */
    AUTH_ACCESS_DENIED,       /* VOICE_FAIL received, message shown */
    AUTH_TEMPORARY_LOCKOUT    /* too many wrong PINs */
} auth_state_t;

void         auth_init(uint32_t now);
void         auth_on_command(proto_cmd_t cmd, uint32_t now);
void         auth_on_key(char key, uint32_t now);
void         auth_task(uint32_t now);      /* call every main-loop pass: timeouts */
auth_state_t auth_state(void);

#endif /* AUTHENTICATION_H_ */
