/*
 * timebase.h - 1 ms system tick on Timer0 (CTC mode).
 */
#ifndef TIMEBASE_H_
#define TIMEBASE_H_

#include <stdint.h>

void     timebase_init(void);
uint32_t millis(void);

/* Wrap-safe check: has 'duration' ms passed since 'start'? */
static inline uint8_t time_elapsed(uint32_t now, uint32_t start, uint32_t duration)
{
    return (uint32_t)(now - start) >= duration;
}

#endif /* TIMEBASE_H_ */
