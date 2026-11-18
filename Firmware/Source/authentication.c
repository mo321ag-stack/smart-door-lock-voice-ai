/*
 * authentication.c - Smart Door Lock state machine.
 *
 * Security rules implemented here:
 *  - The door only unlocks after VOICE_OK *and* a correct PIN typed on the
 *    physical keypad. No Bluetooth command can unlock the door by itself.
 *  - The PIN is compared locally (constant-time) and never transmitted.
 *  - Consecutive wrong PINs are counted in EEPROM, so a reset or power cycle
 *    cannot be used to bypass the lockout. A corrupted counter is treated as
 *    "max attempts reached" (fail secure).
 *  - Every path that leaves UNLOCKED drives the servo to LOCKED.
 *
 * STATUS mapping (protocol has only four response tokens):
 *   LOCKED, WAITING_VOICE            -> LOCKED
 *   VOICE_VERIFIED, WAITING_PIN      -> WAITING_PIN
 *   UNLOCKED                         -> UNLOCKED
 *   ACCESS_DENIED, TEMPORARY_LOCKOUT -> ACCESS_DENIED
 */
#include "config.h"
#include <stdint.h>
#include <stdlib.h>
#include <avr/pgmspace.h>
#include <avr/eeprom.h>
#include "timebase.h"
#include "lcd_i2c.h"
#include "servo.h"
#include "protocol.h"
#include "authentication.h"

#define STR_(x) #x
#define STR(x)  STR_(x)

static const char s_pin_ref[] PROGMEM = AUTH_PIN_CODE;

static auth_state_t s_state;
static uint32_t     s_state_since;     /* time the current state was entered */
static uint32_t     s_session_start;   /* VOICE_OK time; PIN timeout reference */
static char         s_pin[AUTH_PIN_MAX_LEN];
static uint8_t      s_pin_len;
static uint8_t      s_fails;           /* consecutive wrong PINs (mirrored in EEPROM) */
static uint8_t      s_overlay;         /* WAITING_PIN: temporary message on screen */
static uint8_t      s_overlay_resend;  /* send WAITING_PIN again when it ends */
static uint32_t     s_overlay_until;
static uint16_t     s_last_countdown;
static uint8_t      s_config_error;    /* invalid AUTH_PIN_CODE -> permanent lockout */

/* ------------------------------------------------------------------ */
/* EEPROM failed-attempt counter                                       */
/* ------------------------------------------------------------------ */
static void fails_store(uint8_t f)
{
    eeprom_update_byte(EE_ADDR_FAILS, f);
    eeprom_update_byte(EE_ADDR_FAILS_INV, (uint8_t)~f);
}

static uint8_t fails_load(void)
{
    uint8_t f, finv;

    if (eeprom_read_byte(EE_ADDR_MAGIC) != EE_MAGIC_VALUE) {
        /* First boot after programming: initialise the record. */
        fails_store(0);
        eeprom_update_byte(EE_ADDR_MAGIC, EE_MAGIC_VALUE);
        return 0;
    }
    f    = eeprom_read_byte(EE_ADDR_FAILS);
    finv = eeprom_read_byte(EE_ADDR_FAILS_INV);
    if ((uint8_t)(f ^ finv) != 0xFFu) {
        return AUTH_MAX_ATTEMPTS;          /* corrupted: fail secure */
    }
    return (f > AUTH_MAX_ATTEMPTS) ? AUTH_MAX_ATTEMPTS : f;
}

/* ------------------------------------------------------------------ */
/* PIN helpers                                                         */
/* ------------------------------------------------------------------ */
static void pin_clear(void)
{
    volatile char *p = s_pin;
    uint8_t i;
    for (i = 0; i < AUTH_PIN_MAX_LEN; i++) {
        p[i] = 0;
    }
    s_pin_len = 0;
}

/* Constant-time comparison: always walks the full buffer. */
static uint8_t pin_matches(void)
{
    uint8_t ref_len = (uint8_t)(sizeof(s_pin_ref) - 1U);
    uint8_t diff    = (uint8_t)(ref_len ^ s_pin_len);
    uint8_t i;

    for (i = 0; i < AUTH_PIN_MAX_LEN; i++) {
        uint8_t a = (i < s_pin_len) ? (uint8_t)s_pin[i] : 0;
        uint8_t b = (i < ref_len) ? pgm_read_byte(&s_pin_ref[i]) : 0;
        diff |= (uint8_t)(a ^ b);
    }
    return diff == 0;
}

static uint8_t pin_config_valid(void)
{
    uint8_t len = (uint8_t)(sizeof(s_pin_ref) - 1U);
    uint8_t i;
    if (len < AUTH_PIN_MIN_LEN || len > AUTH_PIN_MAX_LEN) {
        return 0;
    }
    for (i = 0; i < len; i++) {
        char c = (char)pgm_read_byte(&s_pin_ref[i]);
        if (c < '0' || c > '9') {
            return 0;
        }
    }
    return 1;
}

/* ------------------------------------------------------------------ */
/* Display helpers                                                     */
/* ------------------------------------------------------------------ */
static void show_pin_entry(void)
{
    char line[LCD_COLS + 1];
    uint8_t i;
    for (i = 0; i < s_pin_len; i++) {
        line[i] = '*';                 /* masked: digits are never displayed */
    }
    line[i] = '\0';
    lcd_line_P(0, PSTR("ENTER PIN:"));
    lcd_line(1, line);
}

static void show_overlay_P(const char *l1, const char *l2, uint32_t now,
                           uint32_t duration, uint8_t resend)
{
    lcd_show_P(l1, l2);
    s_overlay        = 1;
    s_overlay_resend = resend;
    s_overlay_until  = now + duration;
}

static void show_lockout_countdown(uint32_t now)
{
    char line[LCD_COLS + 1];
    char num[6];
    uint32_t left_ms;
    uint16_t secs;
    uint8_t i = 0, j = 0;

    if (s_config_error) {
        return;
    }
    left_ms = AUTH_LOCKOUT_MS - (uint32_t)(now - s_state_since);
    secs = (uint16_t)((left_ms + 999UL) / 1000UL);
    if (secs == s_last_countdown) {
        return;
    }
    s_last_countdown = secs;

    utoa(secs, num, 10);
    line[i++] = 'W'; line[i++] = 'A'; line[i++] = 'I'; line[i++] = 'T'; line[i++] = ' ';
    while (num[j] && i < LCD_COLS - 2) {
        line[i++] = num[j++];
    }
    line[i++] = ' ';
    line[i++] = 's';
    line[i]   = '\0';
    lcd_line(1, line);
}

/* ------------------------------------------------------------------ */
/* State transitions                                                   */
/* ------------------------------------------------------------------ */
/* 'reason' is shown on line 2 for LOCKED / ACCESS_DENIED (PSTR, may be NULL). */
static void enter_state(auth_state_t st, uint32_t now, const char *reason)
{
    s_state       = st;
    s_state_since = now;
    s_overlay     = 0;

    switch (st) {
    case AUTH_LOCKED:
        servo_lock();
        pin_clear();
        lcd_show_P(PSTR("SMART DOOR LOCK"), reason ? reason : PSTR("DOOR LOCKED"));
        protocol_send(PROTO_RSP_LOCKED);
        break;

    case AUTH_WAITING_VOICE:
        servo_lock();
        pin_clear();
        lcd_show_P(PSTR("WAITING VOICE"), PSTR("AUTHENTICATION"));
        break;

    case AUTH_VOICE_VERIFIED:
        servo_lock();
        pin_clear();
        s_session_start = now;
        lcd_show_P(PSTR("VOICE VERIFIED"), PSTR("ENTER PIN"));
        protocol_send(PROTO_RSP_WAITING_PIN);
        break;

    case AUTH_WAITING_PIN:
        show_pin_entry();
        break;

    case AUTH_UNLOCKED:
        pin_clear();
        servo_unlock();
        lcd_show_P(PSTR("ACCESS GRANTED"), PSTR("DOOR UNLOCKED"));
        protocol_send(PROTO_RSP_UNLOCKED);
        break;

    case AUTH_ACCESS_DENIED:
        servo_lock();
        pin_clear();
        lcd_show_P(PSTR("ACCESS DENIED"), reason ? reason : PSTR(""));
        break;

    case AUTH_TEMPORARY_LOCKOUT:
        servo_lock();
        pin_clear();
        if (s_config_error) {
            lcd_show_P(PSTR("CONFIG ERROR"), PSTR("CHECK PIN CODE"));
        } else {
            lcd_line_P(0, PSTR("SYSTEM LOCKED"));
            s_last_countdown = 0xFFFF;
            show_lockout_countdown(now);
        }
        break;

    default:
        break;
    }
}

static proto_rsp_t status_token(void)
{
    switch (s_state) {
    case AUTH_VOICE_VERIFIED:
    case AUTH_WAITING_PIN:
        return PROTO_RSP_WAITING_PIN;
    case AUTH_UNLOCKED:
        return PROTO_RSP_UNLOCKED;
    case AUTH_ACCESS_DENIED:
    case AUTH_TEMPORARY_LOCKOUT:
        return PROTO_RSP_ACCESS_DENIED;
    case AUTH_LOCKED:
    case AUTH_WAITING_VOICE:
    default:
        return PROTO_RSP_LOCKED;
    }
}

static void check_pin(uint32_t now)
{
    uint8_t ok = pin_matches();
    pin_clear();

    if (ok) {
        s_fails = 0;
        fails_store(0);
        enter_state(AUTH_UNLOCKED, now, 0);
        return;
    }

    if (s_fails < AUTH_MAX_ATTEMPTS) {
        s_fails++;
    }
    fails_store(s_fails);
    protocol_send(PROTO_RSP_ACCESS_DENIED);

    if (s_fails >= AUTH_MAX_ATTEMPTS) {
        enter_state(AUTH_TEMPORARY_LOCKOUT, now, 0);
    } else {
        show_overlay_P(PSTR("WRONG PIN"), PSTR("TRY AGAIN"), now, UI_MSG_SHORT_MS, 1);
    }
}

/* ------------------------------------------------------------------ */
/* Public API                                                          */
/* ------------------------------------------------------------------ */
void auth_init(uint32_t now)
{
    pin_clear();
    s_config_error = !pin_config_valid();
    s_fails = fails_load();

    if (s_config_error || s_fails >= AUTH_MAX_ATTEMPTS) {
        protocol_send(PROTO_RSP_LOCKED);   /* tell the app the door is locked */
        enter_state(AUTH_TEMPORARY_LOCKOUT, now, 0);
    } else {
        enter_state(AUTH_LOCKED, now, PSTR("SYSTEM READY"));
    }
}

auth_state_t auth_state(void)
{
    return s_state;
}

void auth_on_command(proto_cmd_t cmd, uint32_t now)
{
    switch (cmd) {
    case PROTO_CMD_VOICE_OK:
        switch (s_state) {
        case AUTH_LOCKED:
        case AUTH_WAITING_VOICE:
        case AUTH_ACCESS_DENIED:
            enter_state(AUTH_VOICE_VERIFIED, now, 0);
            break;
        case AUTH_VOICE_VERIFIED:
        case AUTH_WAITING_PIN:
            /* Session already running: do NOT restart timer or attempts. */
            protocol_send(PROTO_RSP_WAITING_PIN);
            break;
        case AUTH_UNLOCKED:
            protocol_send(PROTO_RSP_UNLOCKED);
            break;
        case AUTH_TEMPORARY_LOCKOUT:
        default:
            protocol_send(PROTO_RSP_ACCESS_DENIED);
            break;
        }
        break;

    case PROTO_CMD_VOICE_FAIL:
        if (s_state == AUTH_UNLOCKED) {
            protocol_send(PROTO_RSP_UNLOCKED);          /* already authorised; ignore */
        } else if (s_state == AUTH_TEMPORARY_LOCKOUT) {
            protocol_send(PROTO_RSP_ACCESS_DENIED);
        } else {
            protocol_send(PROTO_RSP_ACCESS_DENIED);
            enter_state(AUTH_ACCESS_DENIED, now, PSTR("VOICE REJECTED"));
        }
        break;

    case PROTO_CMD_LOCK:
        if (s_state == AUTH_TEMPORARY_LOCKOUT) {
            /* Door is forced locked, but LOCK must not cancel a lockout. */
            servo_lock();
            protocol_send(PROTO_RSP_LOCKED);
        } else {
            enter_state(AUTH_LOCKED, now, PSTR("LOCK COMMAND"));
        }
        break;

    case PROTO_CMD_STATUS:
        protocol_send(status_token());
        break;

    case PROTO_CMD_INVALID:
    case PROTO_CMD_NONE:
    default:
        /* Invalid commands are ignored: no state change, no response. */
        break;
    }
}

void auth_on_key(char key, uint32_t now)
{
    if (s_state == AUTH_VOICE_VERIFIED) {
        enter_state(AUTH_WAITING_PIN, now, 0);   /* first key skips the message */
    }
    if (s_state != AUTH_WAITING_PIN || s_overlay) {
        return;                                  /* keypad inactive in other states */
    }

    if (key >= '0' && key <= '9') {
        if (s_pin_len < AUTH_PIN_MAX_LEN) {
            s_pin[s_pin_len++] = key;
            show_pin_entry();
        }
    } else if (key == KEY_CLEAR) {
        pin_clear();
        show_pin_entry();
    } else if (key == KEY_BACKSPACE) {
        if (s_pin_len > 0) {
            s_pin[--s_pin_len] = 0;
            show_pin_entry();
        }
    } else if (key == KEY_ENTER) {
        if (s_pin_len < AUTH_PIN_MIN_LEN) {
            show_overlay_P(PSTR("PIN TOO SHORT"), PSTR("MIN " STR(AUTH_PIN_MIN_LEN) " DIGITS"),
                           now, 1000UL, 0);
        } else {
            check_pin(now);
        }
    }
    /* B, C, D: unused */
}

void auth_task(uint32_t now)
{
    switch (s_state) {
    case AUTH_LOCKED:
        if (time_elapsed(now, s_state_since, UI_MSG_SHORT_MS)) {
            enter_state(AUTH_WAITING_VOICE, now, 0);
        }
        break;

    case AUTH_VOICE_VERIFIED:
        if (time_elapsed(now, s_session_start, AUTH_PIN_TIMEOUT_MS)) {
            enter_state(AUTH_LOCKED, now, PSTR("PIN TIMEOUT"));
        } else if (time_elapsed(now, s_state_since, UI_MSG_SHORT_MS)) {
            enter_state(AUTH_WAITING_PIN, now, 0);
        }
        break;

    case AUTH_WAITING_PIN:
        if (time_elapsed(now, s_session_start, AUTH_PIN_TIMEOUT_MS)) {
            enter_state(AUTH_LOCKED, now, PSTR("PIN TIMEOUT"));
        } else if (s_overlay && (int32_t)(now - s_overlay_until) >= 0) {
            s_overlay = 0;
            show_pin_entry();
            if (s_overlay_resend) {
                protocol_send(PROTO_RSP_WAITING_PIN);
            }
        }
        break;

    case AUTH_UNLOCKED:
        if (time_elapsed(now, s_state_since, DOOR_UNLOCK_MS)) {
            enter_state(AUTH_LOCKED, now, PSTR("AUTO RELOCK"));
        }
        break;

    case AUTH_ACCESS_DENIED:
        if (time_elapsed(now, s_state_since, UI_MSG_DENIED_MS)) {
            enter_state(AUTH_WAITING_VOICE, now, 0);
        }
        break;

    case AUTH_TEMPORARY_LOCKOUT:
        if (s_config_error) {
            break;                                /* never leaves: fix config.h */
        }
        if (time_elapsed(now, s_state_since, AUTH_LOCKOUT_MS)) {
            s_fails = 0;
            fails_store(0);
            enter_state(AUTH_LOCKED, now, PSTR("LOCKOUT ENDED"));
        } else {
            show_lockout_countdown(now);
        }
        break;

    case AUTH_WAITING_VOICE:
    default:
        break;
    }
}
