/* Compile the production pretend-PCB helpers against the production lwIP address and PCB types. */
#include "lwip/priv/udp_pretend.h"

void *memcpy(void *destination, const void *source, size_t count)
{
  unsigned char *out = destination;
  const unsigned char *in = source;
  while (count--) *out++ = *in++;
  return destination;
}
void *memset(void *destination, int value, size_t count)
{
  unsigned char *out = destination;
  while (count--) *out++ = (unsigned char)value;
  return destination;
}
int memcmp(const void *first, const void *second, size_t count)
{
  const unsigned char *a = first, *b = second;
  while (count--) { if (*a != *b) return (int)*a - (int)*b; ++a; ++b; }
  return 0;
}
size_t strlen(const char *value)
{
  size_t count = 0;
  while (value[count]) ++count;
  return count;
}

static struct udp_pcb sessions[8];
static unsigned int session_count;
static unsigned int checks;

/* This models dispatch ownership only. The predicate and tuple initialization are the production functions. */
static struct udp_pcb *dispatch(const ip_addr_t *source, u16_t source_port,
                                const ip_addr_t *destination, u16_t destination_port)
{
  unsigned int index;
  for (index = 0; index < session_count; ++index) {
    if (udp_pretend_matches_flow(&sessions[index], source, source_port, destination, destination_port))
      return &sessions[index];
  }
  if (session_count >= 8) return NULL;
  udp_pretend_set_flow(&sessions[session_count], source, source_port, destination, destination_port);
  return &sessions[session_count++];
}

#define CHECK(condition, code) do { ++checks; if (!(condition)) return -(code); } while (0)

static int one_socket_multiple_destinations(const ip_addr_t *source, const ip_addr_t *other_source,
                                           const ip_addr_t *white, const ip_addr_t *foreign)
{
  struct udp_pcb *original, *other_destination, *other_port;
  session_count = 0;
  original = dispatch(source, 45000, white, 3478);
  CHECK(original != NULL, 1);
  CHECK(dispatch(source, 45000, white, 3478) == original, 2);
  CHECK(session_count == 1, 3);
  other_destination = dispatch(source, 45000, foreign, 3478);
  CHECK(other_destination != NULL && other_destination != original, 4);
  CHECK(session_count == 2, 5);
  other_port = dispatch(source, 45000, white, 3479);
  CHECK(other_port != NULL && other_port != original && other_port != other_destination, 6);
  CHECK(dispatch(source, 45001, white, 3478) != original, 7);
  CHECK(dispatch(other_source, 45000, white, 3478) != original, 8);
  CHECK(session_count == 5, 9);
  CHECK(dispatch(source, 45000, white, 3478) == original, 10);
  CHECK(dispatch(source, 45000, foreign, 3478) == other_destination, 11);
  CHECK(ip_addr_cmp(&original->local_ip, white) && original->local_port == 3478, 12);
  return 0;
}

__declspec(dllexport) int hev_udp_isolation_regression(void)
{
  int result;
  checks = 0;
#if LWIP_IPV4
  ip_addr_t source4, other_source4, white4, foreign4;
  IP_ADDR4(&source4, 10, 0, 0, 2);
  IP_ADDR4(&other_source4, 10, 0, 0, 3);
  IP_ADDR4(&white4, 198, 19, 0, 9);
  IP_ADDR4(&foreign4, 203, 0, 113, 9);
  result = one_socket_multiple_destinations(&source4, &other_source4, &white4, &foreign4);
  if (result) return result;
#endif
#if LWIP_IPV6
  ip_addr_t source6, other_source6, white6, foreign6;
  IP_ADDR6(&source6, PP_HTONL(0x20010db8), 0, 0, PP_HTONL(2));
  IP_ADDR6(&other_source6, PP_HTONL(0x20010db8), 0, 0, PP_HTONL(3));
  IP_ADDR6(&white6, PP_HTONL(0x20010db8), 0, PP_HTONL(1), PP_HTONL(9));
  IP_ADDR6(&foreign6, PP_HTONL(0x20010db8), 0, PP_HTONL(2), PP_HTONL(9));
  result = one_socket_multiple_destinations(&source6, &other_source6, &white6, &foreign6);
  if (result) return result;
#endif
#if LWIP_IPV4 && LWIP_IPV6
  udp_pretend_set_flow(&sessions[0], &source4, 45000, &white4, 3478);
  CHECK(!udp_pretend_matches_flow(&sessions[0], &source6, 45000, &white6, 3478), 30);
  udp_pretend_set_flow(&sessions[0], &source6, 45000, &white6, 3478);
  CHECK(!udp_pretend_matches_flow(&sessions[0], &source4, 45000, &white4, 3478), 31);
#endif
  return (int)checks;
}
