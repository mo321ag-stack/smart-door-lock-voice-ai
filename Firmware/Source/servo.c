/*
 * servo.c - SG90 control with Timer1 hardware PWM on OC1A (PD5, pin 19).
 *
 * Mode 14 (Fast PWM, TOP = ICR1), non-inverting on OC1A, prescaler 8:
 *   tick   = 8 / 8 MHz = 1 us
 *   period = (ICR1 + 1) ticks = 20000 us -> 50 Hz
 *   pulse  = (OCR1A + 1) ticks
 * OCR1A is double-buffered in this mode, so a new pulse width takes effect
 * at the start of the next period (no glitches).
 */
#include "config.h"
#include <avr/io.h>
#include <util/atomic.h>
#include "servo.h"

static uint16_t s_pulse_us;

static void write_pulse(uint16_t us)
{
    if (us < SERVO_MIN_US) us = SERVO_MIN_US;
    if (us > SERVO_MAX_US) us = SERVO_MAX_US;
    s_pulse_us = us;
    ATOMIC_BLOCK(ATOMIC_RESTORESTATE) {
        OCR1A = (uint16_t)(us - 1U);
    }
}

void servo_init(void)
{
    /* Configure the timer and the LOCKED pulse BEFORE enabling the pin,
       so the very first pulse on PD5 is already the locked position. */
    TCCR1B = 0;
    TCCR1A = 0;
    TCNT1  = 0;
    ICR1   = (uint16_t)(SERVO_PERIOD_US - 1U);
    write_pulse(SERVO_LOCKED_US);
    TCCR1A = (1 << COM1A1) | (1 << WGM11);
    TCCR1B = (1 << WGM13) | (1 << WGM12) | (1 << CS11);
    DDRD  |= (1 << PD5);
}

void servo_set_pulse_us(uint16_t us) { write_pulse(us); }
uint16_t servo_get_pulse_us(void)    { return s_pulse_us; }
void servo_lock(void)                { write_pulse(SERVO_LOCKED_US); }
void servo_unlock(void)              { write_pulse(SERVO_UNLOCKED_US); }
uint8_t servo_is_unlocked(void)      { return s_pulse_us == SERVO_UNLOCKED_US; }
