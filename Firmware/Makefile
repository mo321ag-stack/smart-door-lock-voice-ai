# Smart Door Lock - ATmega32 @ 8 MHz - command-line build (alternative to Microchip Studio)
#
#   make                 build Debug/SmartDoorLock.hex
#   make diag            build Debug/SmartDoorLock_diag.hex (hardware test firmware)
#   make size            show flash / RAM usage
#   make flash           program the HEX         (PROGRAMMER=usbasp by default)
#   make fuses           write clock/BOD/JTAG fuses (read README first!)
#   make readfuses       read the current fuses
#   make clean
#
# Override the toolchain path if avr-gcc is not on PATH, e.g.
#   make TOOLCHAIN="C:/Program Files (x86)/Atmel/Studio/7.0/toolchain/avr8/avr8-gnu-toolchain/bin/"

MCU        = atmega32
F_CPU      = 8000000UL
TARGET     = SmartDoorLock
SRC_DIR    = Source
INC_DIR    = Headers
OUT_DIR    = Debug

TOOLCHAIN ?=
CC        = "$(TOOLCHAIN)avr-gcc"
OBJCOPY   = "$(TOOLCHAIN)avr-objcopy"
SIZE      = "$(TOOLCHAIN)avr-size"

PROGRAMMER ?= usbasp
PORT       ?= usb
AVRDUDE    ?= avrdude

# Fuses (see README "Clock and fuse configuration")
LFUSE = 0x3F   # ext. crystal (CKSEL=1111, SUT=11), BOD enabled @ 4.0 V
HFUSE = 0xD1   # JTAG off, SPI programming on, EESAVE on, CKOPT unprogrammed

SRCS   = $(wildcard $(SRC_DIR)/*.c)
CFLAGS = -mmcu=$(MCU) -DF_CPU=$(F_CPU) -I$(INC_DIR) -Os -std=gnu99 \
         -Wall -Wextra -Wstrict-prototypes -funsigned-char -funsigned-bitfields \
         -fshort-enums -ffunction-sections -fdata-sections
LDFLAGS = -mmcu=$(MCU) -Wl,--gc-sections -Wl,-Map=$(OUT_DIR)/$(TARGET).map

.PHONY: all diag size flash fuses readfuses clean

all: $(OUT_DIR)/$(TARGET).hex size

$(OUT_DIR):
	mkdir -p $(OUT_DIR)

$(OUT_DIR)/$(TARGET).elf: $(SRCS) $(wildcard $(INC_DIR)/*.h) | $(OUT_DIR)
	$(CC) $(CFLAGS) $(SRCS) $(LDFLAGS) -o $@

$(OUT_DIR)/$(TARGET).hex: $(OUT_DIR)/$(TARGET).elf
	$(OBJCOPY) -O ihex -R .eeprom $< $@

diag: | $(OUT_DIR)
	$(CC) $(CFLAGS) -DENABLE_DIAGNOSTICS_OVERRIDE=1 $(SRCS) -mmcu=$(MCU) -Wl,--gc-sections -o $(OUT_DIR)/$(TARGET)_diag.elf
	$(OBJCOPY) -O ihex -R .eeprom $(OUT_DIR)/$(TARGET)_diag.elf $(OUT_DIR)/$(TARGET)_diag.hex
	$(SIZE) --format=avr --mcu=$(MCU) $(OUT_DIR)/$(TARGET)_diag.elf

size: $(OUT_DIR)/$(TARGET).elf
	$(SIZE) --format=avr --mcu=$(MCU) $<

flash: $(OUT_DIR)/$(TARGET).hex
	$(AVRDUDE) -c $(PROGRAMMER) -P $(PORT) -p m32 -U flash:w:$<:i

fuses:
	$(AVRDUDE) -c $(PROGRAMMER) -P $(PORT) -p m32 -B 10 -U lfuse:w:$(LFUSE):m -U hfuse:w:$(HFUSE):m

readfuses:
	$(AVRDUDE) -c $(PROGRAMMER) -P $(PORT) -p m32 -U lfuse:r:-:h -U hfuse:r:-:h

clean:
	rm -f $(OUT_DIR)/*.elf $(OUT_DIR)/*.hex $(OUT_DIR)/*.map
