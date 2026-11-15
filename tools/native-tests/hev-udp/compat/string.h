#ifndef HEV_UDP_TEST_STRING_H
#define HEV_UDP_TEST_STRING_H
#include <stddef.h>
void *memcpy(void *destination, const void *source, size_t count);
void *memset(void *destination, int value, size_t count);
int memcmp(const void *first, const void *second, size_t count);
size_t strlen(const char *value);
#endif
