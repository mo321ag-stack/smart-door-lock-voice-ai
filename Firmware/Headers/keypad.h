/*
 * keypad.h - 4x4 matrix keypad on PORTA (rows PA0..PA3, columns PA4..PA7).
 */
#ifndef KEYPAD_H_
#define KEYPAD_H_

#include <stdint.h>

void keypad_init(void);
char keypad_scan_raw(void);        /* immediate scan: key char, or 0 (none / several keys) */
char keypad_poll(uint32_t now_ms); /* debounced: returns a key once per press, else 0 */

#endif /* KEYPAD_H_ */
