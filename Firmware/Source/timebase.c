/*
 * timebase.c - 1 ms tick.
 * Timer0 CTC, prescaler 64: 8 MHz / 64 = 125 kHz -> OCR0 = 124 gives exactly 1.000 ms.
 */
#include "config.h"
#include <avr/io.h>
#include <avr/interrupt.h>
#include <util/atomic.h>
#include "timebase.h"

#define T0_PRESCALER 64UL
#define T0_OCR       ((F_CPU / T0_PRESCALER / 1000UL) - 1UL)

#if T0_OCR > 255
#error "Timer0 OCR value out of range for this F_CPU"
#endif

static volatile uint32_t s_ms;

ISR(TIMER0_COMP_vect)
{
    s_ms++;
}

void timebase_init(void)
{
    TCCR0 = 0;
    TCNT0 = 0;
    OCR0  = (uint8_t)T0_OCR;
    TCCR0 = (1 << WGM01) | (1 << CS01) | (1 << CS00);   /* CTC, clk/64 */
    TIMSK |= (1 << OCIE0);
}

uint32_t millis(void)
{
    uint32_t v;
    ATOMIC_BLOCK(ATOMIC_RESTORESTATE) {
        v = s_ms;
    }
    return v;
}
