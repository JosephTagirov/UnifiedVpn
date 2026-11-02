package org.olcbox.app.data.share
import io.ktor.http.Url

/** Only the owner's HTTPS import endpoint may be opened by an external app link. */
object SelectionImportLink {
 fun parse(raw:String?):String? {
  if(raw==null||raw.length>4096)return null
  return runCatching {
   val link=Url(raw)
   if(link.protocol.name!="unifiedvpn"||link.host!="import"||link.encodedPath !in listOf("","/")||link.parameters.names()!=setOf("url")||link.parameters.getAll("url")?.size!=1)return null
   val value=link.parameters["url"]?:return null
   val source=Url(value)
   if(source.protocol.name!="https"||source.host!="panel.meesharr.space"||source.port!=443||source.user!=null||source.password!=null||source.fragment.isNotEmpty()||source.parameters.names().isNotEmpty()||!Regex("/vpn-control/bundle/[A-Za-z0-9_-]{43}").matches(source.encodedPath))return null
   value
  }.getOrNull()
 }
}
