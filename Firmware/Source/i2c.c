/*
 * i2c.c - TWI master, polled, with bounded waits.
 * SCL = F_CPU / (16 + 2*TWBR*prescaler) -> 8 MHz, TWBR = 32, prescaler 1 = 100 kHz.
 */
#include "config.h"
#include <avr/io.h>
#include <util/twi.h>
#include "i2c.h"

#define TWBR_VALUE (((F_CPU / I2C_SCL_HZ) - 16UL) / 2UL)
#if TWBR_VALUE < 10 || TWBR_VALUE > 255
#error "TWBR out of recommended range for this F_CPU / I2C_SCL_HZ"
#endif

/* Wait for TWINT. Returns 1 when set, 0 on timeout. */
static uint8_t wait_twint(void)
{
    uint16_t n = I2C_TIMEOUT_LOOPS;
    while (!(TWCR & (1 << TWINT))) {
        if (--n == 0) {
            return 0;
        }
    }
    return 1;
}

/* Reset the TWI peripheral after a timeout (stuck bus / missing pull-ups). */
static void bus_recover(void)
{
    TWCR = 0;
    TWCR = (1 << TWEN);
}

void i2c_init(void)
{
#if I2C_INTERNAL_PULLUPS
    DDRC  &= (uint8_t)~((1 << PC0) | (1 << PC1));
    PORTC |= (1 << PC0) | (1 << PC1);
#endif
    TWSR = 0;                            /* prescaler = 1 */
    TWBR = (uint8_t)TWBR_VALUE;
    TWCR = (1 << TWEN);
}

uint8_t i2c_start(uint8_t addr7, uint8_t read)
{
    uint8_t st;

    TWCR = (1 << TWINT) | (1 << TWSTA) | (1 << TWEN);
    if (!wait_twint()) {
        bus_recover();
        return I2C_ERR_TIMEOUT;
    }
    st = TW_STATUS;
    if (st != TW_START && st != TW_REP_START) {
        return I2C_ERR_START;
    }

    TWDR = (uint8_t)((addr7 << 1) | (read ? 1 : 0));
    TWCR = (1 << TWINT) | (1 << TWEN);
    if (!wait_twint()) {
        bus_recover();
        return I2C_ERR_TIMEOUT;
    }
    st = TW_STATUS;
    if (st != TW_MT_SLA_ACK && st != TW_MR_SLA_ACK) {
        return I2C_ERR_NACK;
    }
    return I2C_OK;
}

uint8_t i2c_write(uint8_t data)
{
    TWDR = data;
    TWCR = (1 << TWINT) | (1 << TWEN);
    if (!wait_twint()) {
        bus_recover();
        return I2C_ERR_TIMEOUT;
    }
    return (TW_STATUS == TW_MT_DATA_ACK) ? I2C_OK : I2C_ERR_NACK;
}

void i2c_stop(void)
{
    uint16_t n = I2C_TIMEOUT_LOOPS;
    TWCR = (1 << TWINT) | (1 << TWSTO) | (1 << TWEN);
    while ((TWCR & (1 << TWSTO)) && --n) {
        /* wait until STOP has been sent */
    }
}

uint8_t i2c_probe(uint8_t addr7)
{
    uint8_t r = i2c_start(addr7, 0);
    i2c_stop();
    return r == I2C_OK;
}
