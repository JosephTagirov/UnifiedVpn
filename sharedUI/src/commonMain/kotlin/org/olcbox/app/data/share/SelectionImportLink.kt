package org.olcbox.app.data.share
import io.ktor.http.Url

/** Accept a strict HTTPS subscription directly (QR) or in the application's import link. */
object SelectionImportLink {
 fun parse(raw:String?):String? {
  if(raw==null||raw.length>4096)return null
  return runCatching {
   if(raw.any { it.isWhitespace() || it.code<32 })return null
   if(raw.startsWith("https://",true))return validSubscription(raw)
   if(!raw.startsWith("unifiedvpn://",true)||!authority(raw).equals("import",true))return null
   val link=Url(raw)
   if(link.protocol.name!="unifiedvpn"||link.host!="import"||link.user!=null||link.password!=null||link.fragment.isNotEmpty()||link.specifiedPort!=0||link.encodedPath !in listOf("","/")||link.parameters.names()!=setOf("url")||link.parameters.getAll("url")?.size!=1)return null
   val value=link.parameters["url"]?:return null
   validSubscription(value)
  }.getOrNull()
 }
 private fun validSubscription(value:String):String? {
  if(value.length>4096||value.any { it.isWhitespace()||it.code<32 })return null
  val source=Url(value)
  return value.takeIf { source.protocol.name=="https"&&source.host.isNotBlank()&&source.port in 1..65535&&hasValidAuthorityPort(value)&&source.user==null&&source.password==null&&source.fragment.isEmpty() }
 }
 private fun authority(value:String)=value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
 internal fun hasValidAuthorityPort(value:String):Boolean {
  val host=authority(value)
  val suffix=if(host.startsWith('['))host.substringAfter(']',"invalid") else host.indexOf(':').let { if(it<0)"" else host.substring(it) }
  return suffix.isEmpty()||(suffix.startsWith(':')&&suffix.drop(1).toIntOrNull()?.let { it in 1..65535 }==true)
 }
}
