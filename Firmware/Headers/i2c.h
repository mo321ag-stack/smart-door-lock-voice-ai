/*
 * i2c.h - Polled TWI master (PC0/SCL, PC1/SDA) with timeouts.
 */
#ifndef I2C_H_
#define I2C_H_

#include <stdint.h>

#define I2C_OK          0
#define I2C_ERR_TIMEOUT 1
#define I2C_ERR_START   2
#define I2C_ERR_NACK    3

void    i2c_init(void);
uint8_t i2c_start(uint8_t addr7, uint8_t read);   /* START + address; returns I2C_OK on ACK */
uint8_t i2c_write(uint8_t data);                  /* returns I2C_OK on ACK */
void    i2c_stop(void);
uint8_t i2c_probe(uint8_t addr7);                 /* 1 if a device ACKs this address */

#endif /* I2C_H_ */
