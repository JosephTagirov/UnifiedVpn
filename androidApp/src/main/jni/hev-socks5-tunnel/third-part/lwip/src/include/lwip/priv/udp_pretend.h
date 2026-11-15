/*
 * Copyright (c) 2001-2004 Swedish Institute of Computer Science.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT
 * OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING
 * IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
 * OF SUCH DAMAGE.
 */
#ifndef LWIP_HDR_UDP_PRETEND_H
#define LWIP_HDR_UDP_PRETEND_H

#include "lwip/udp.h"

#if LWIP_UDP
/* A SOCKS UDP association has one routing decision. Keep every destination in
 * its own pretend PCB even when one application UDP socket talks to many peers. */
static inline int
udp_pretend_matches_flow(const struct udp_pcb *pcb, const ip_addr_t *source,
                        u16_t source_port, const ip_addr_t *destination,
                        u16_t destination_port)
{
  return pcb->remote_port == source_port && ip_addr_cmp(&pcb->remote_ip, source) &&
         pcb->local_port == destination_port && ip_addr_cmp(&pcb->local_ip, destination);
}

static inline void
udp_pretend_set_flow(struct udp_pcb *pcb, const ip_addr_t *source,
                     u16_t source_port, const ip_addr_t *destination,
                     u16_t destination_port)
{
  ip_addr_set_ipaddr(&pcb->remote_ip, source);
  pcb->remote_port = source_port;
  ip_addr_set_ipaddr(&pcb->local_ip, destination);
  pcb->local_port = destination_port;
}
#endif /* LWIP_UDP */
#endif /* LWIP_HDR_UDP_PRETEND_H */
