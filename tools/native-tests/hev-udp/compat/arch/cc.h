#ifndef HEV_UDP_TEST_ARCH_CC_H
#define HEV_UDP_TEST_ARCH_CC_H
/* Freestanding host-test port only. Packet/address/PCB types come from the real lwIP headers. */
#define BYTE_ORDER 1234
#define LWIP_NO_INTTYPES_H 1
#define LWIP_NO_UNISTD_H 1
#define LWIP_NO_CTYPE_H 1
#define LWIP_PLATFORM_DIAG(message) do { } while (0)
#define LWIP_PLATFORM_ASSERT(message) __builtin_trap()
#define PACK_STRUCT_STRUCT __attribute__((packed))
#endif
