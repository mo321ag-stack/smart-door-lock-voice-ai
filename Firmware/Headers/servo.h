/*
 * servo.h - SG90 on PD5/OC1A, Timer1 Fast PWM (mode 14), 50 Hz, 1 us resolution.
 */
#ifndef SERVO_H_
#define SERVO_H_

#include <stdint.h>

void     servo_init(void);                 /* starts PWM already at the LOCKED position */
void     servo_set_pulse_us(uint16_t us);  /* clamped to SERVO_MIN_US..SERVO_MAX_US */
uint16_t servo_get_pulse_us(void);
void     servo_lock(void);
void     servo_unlock(void);
uint8_t  servo_is_unlocked(void);

#endif /* SERVO_H_ */
