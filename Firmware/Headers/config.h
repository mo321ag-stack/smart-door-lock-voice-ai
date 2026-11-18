/*
 * config.h - Smart Door Lock (ATmega32 @ 8 MHz external crystal)
 *
 * ALL user-tunable settings live in this file. Nothing else should need
 * editing for a normal installation.
 *
 * Fixed hardware mapping (DIP-40) - do NOT change unless the wiring changes:
 *   HC-05 TXD -> PD0/RXD  (pin 14)      HC-05 RXD <- PD1/TXD (pin 15, via divider)
 *   Servo signal <- PD5/OC1A (pin 19)
 *   Keypad rows R1..R4 -> PA0..PA3 (pins 40..37)
 *   Keypad cols C1..C4 -> PA4..PA7 (pins 36..33)
 *   LCD (PCF8574) SCL -> PC0 (pin 22), SDA -> PC1 (pin 23)
 */
#ifndef CONFIG_H_
#define CONFIG_H_

#ifndef F_CPU
#define F_CPU 8000000UL                 /* 8 MHz external crystal (see README: fuses) */
#endif

/* =====================================================================
 * 1. PIN CODE  -  CHANGE THIS BEFORE INSTALLATION
 *    Digits '0'..'9' only. Length must be AUTH_PIN_MIN_LEN..AUTH_PIN_MAX_LEN.
 *    The PIN is checked only on the ATmega32 and is NEVER sent over UART.
 * ===================================================================== */
#define AUTH_PIN_CODE           "1234"
#define AUTH_PIN_MIN_LEN        4
#define AUTH_PIN_MAX_LEN        8

/* =====================================================================
 * 2. ATTEMPTS, LOCKOUT AND TIMEOUTS (milliseconds)
 * ===================================================================== */
#define AUTH_MAX_ATTEMPTS       3        /* consecutive wrong PINs before lockout   */
#define AUTH_LOCKOUT_MS         60000UL  /* temporary lockout duration              */
#define AUTH_PIN_TIMEOUT_MS     30000UL  /* time allowed for PIN entry after VOICE_OK */
#define DOOR_UNLOCK_MS          5000UL   /* door stays unlocked, then auto-relocks  */
#define UI_MSG_SHORT_MS         1500UL   /* short informational LCD messages        */
#define UI_MSG_DENIED_MS        2000UL   /* ACCESS DENIED message duration          */

/* =====================================================================
 * 3. KEYPAD  (standard 4x4 layout: 123A / 456B / 789C / *0#D)
 * ===================================================================== */
#define KEY_ENTER               '#'
#define KEY_CLEAR               '*'
#define KEY_BACKSPACE           'A'
#define KEYPAD_SCAN_INTERVAL_MS 5U
#define KEYPAD_DEBOUNCE_MS      30U

/* =====================================================================
 * 4. SERVO (SG90 on OC1A). Timer1 tick = 1 us, so all values are in us.
 *    The locked/unlocked pulse widths are STARTING VALUES ONLY.
 *    Calibrate them on the real mechanism (README: "Servo calibration").
 * ===================================================================== */
#define SERVO_PERIOD_US         20000U   /* 50 Hz */
#define SERVO_LOCKED_US         1000U
#define SERVO_UNLOCKED_US       2000U
#define SERVO_MIN_US            500U     /* hard clamp, protects the servo */
#define SERVO_MAX_US            2500U

/* =====================================================================
 * 5. LCD 16x2 via PCF8574
 *    Address not confirmed yet: with LCD_AUTO_DETECT=1 the firmware probes
 *    PRIMARY first, then SECONDARY. Set LCD_AUTO_DETECT=0 to force PRIMARY.
 *    Common: PCF8574T = 0x27, PCF8574AT = 0x3F.
 * ===================================================================== */
#define LCD_I2C_ADDR_PRIMARY    0x27
#define LCD_I2C_ADDR_SECONDARY  0x3F
#define LCD_AUTO_DETECT         1
#define LCD_COLS                16
/* PCF8574 bit -> LCD signal mapping (the common blue backpack). D4..D7 = P4..P7 */
#define LCD_BIT_RS              0
#define LCD_BIT_RW              1
#define LCD_BIT_EN              2
#define LCD_BIT_BL              3

/* =====================================================================
 * 6. I2C (TWI)
 * ===================================================================== */
#define I2C_SCL_HZ              100000UL
#define I2C_TIMEOUT_LOOPS       10000U   /* ~7 ms; prevents a hang if the bus is stuck */
#define I2C_INTERNAL_PULLUPS    1        /* weak backup; the PCF8574 board normally has pull-ups */

/* =====================================================================
 * 7. UART / Bluetooth (HC-05, Bluetooth Classic SPP) - 9600 8N1, no flow control
 * ===================================================================== */
#define UART_BAUD               9600UL
#define UART_RX_BUF_SIZE        64       /* power of two */
#define UART_TX_BUF_SIZE        64       /* power of two */
#define PROTO_MAX_LINE_LEN      16       /* longer lines are discarded */

/* =====================================================================
 * 8. SAFETY
 * ===================================================================== */
#define USE_WATCHDOG            1        /* 1 s watchdog, reset every main-loop pass */

/* EEPROM layout (failed-attempt counter survives reset/power loss).
 * Starts at 0x10 to stay away from address 0. */
#define EE_ADDR_MAGIC           ((uint8_t *)0x10)
#define EE_ADDR_FAILS           ((uint8_t *)0x11)
#define EE_ADDR_FAILS_INV       ((uint8_t *)0x12)
#define EE_MAGIC_VALUE          0xA5

/* =====================================================================
 * 9. DIAGNOSTICS / BRING-UP MODE
 *    1 = build a hardware test firmware (I2C scan, keypad echo, servo
 *        calibration, UART echo). The servo can be moved freely from the
 *        keypad in this mode, so NEVER install it on a real door.
 *    0 = normal lock firmware.
 * ===================================================================== */
#ifdef ENABLE_DIAGNOSTICS_OVERRIDE         /* set by "make diag" */
#define ENABLE_DIAGNOSTICS      ENABLE_DIAGNOSTICS_OVERRIDE
#else
#define ENABLE_DIAGNOSTICS      0
#endif

/* ------------------------- compile-time checks ------------------------ */
#if F_CPU != 8000000UL
#warning "F_CPU is not 8 MHz - UART, I2C and servo timing assume 8 MHz"
#endif
#if (UART_RX_BUF_SIZE & (UART_RX_BUF_SIZE - 1)) || (UART_TX_BUF_SIZE & (UART_TX_BUF_SIZE - 1))
#error "UART buffer sizes must be powers of two"
#endif
#if UART_RX_BUF_SIZE > 128 || UART_TX_BUF_SIZE > 128
#error "UART buffers use 8-bit indices: max 128"
#endif
#if SERVO_LOCKED_US < SERVO_MIN_US || SERVO_LOCKED_US > SERVO_MAX_US || \
    SERVO_UNLOCKED_US < SERVO_MIN_US || SERVO_UNLOCKED_US > SERVO_MAX_US
#error "Servo pulse widths outside SERVO_MIN_US..SERVO_MAX_US"
#endif
#if AUTH_PIN_MAX_LEN > 14
#error "AUTH_PIN_MAX_LEN must fit on one LCD line"
#endif
#if AUTH_MAX_ATTEMPTS < 1 || AUTH_MAX_ATTEMPTS > 9
#error "AUTH_MAX_ATTEMPTS must be 1..9"
#endif

#endif /* CONFIG_H_ */
