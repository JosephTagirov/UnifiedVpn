package org.olcbox.app.desktop
import org.olcbox.app.data.share.SelectionImportLink
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Private, per-user import queue. Secondary URI launches never start another VPN engine. */
class DesktopImportBridge private constructor(private val channel:FileChannel,private val lock:FileLock,private val queue:Path):AutoCloseable {
 fun take():List<String> {
  if(!Files.isDirectory(queue))return emptyList()
  return Files.list(queue).use { stream ->
   stream.filter { it.fileName.toString().endsWith(".link")&&!Files.isSymbolicLink(it) }.sorted().limit(16).toList().mapNotNull { file ->
    try {
     if(Files.size(file)>4096)return@mapNotNull null
     val raw=Files.readString(file).trim()
     if(raw=="show")"show" else SelectionImportLink.parse(raw)
    } finally { Files.deleteIfExists(file) }
   }
  }
 }
 override fun close(){lock.release();channel.close()}
 companion object {
  fun open(args:Array<String>):DesktopImportBridge? {
   val base=DesktopPaths.appDataDir();val queue=DesktopPaths.ensurePrivateDirectory(base.resolve("pending-imports"))
   val channel=FileChannel.open(base.resolve("application.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)
   val lock=runCatching { channel.tryLock() }.getOrNull()
   val link=args.firstOrNull { SelectionImportLink.parse(it)!=null }
   if(link!=null||lock==null){
    val stage=queue.resolve(UUID.randomUUID().toString()+".tmp")
    DesktopPaths.writePrivateString(stage,link?:"show")
    Files.move(stage,stage.resolveSibling(stage.fileName.toString().removeSuffix(".tmp")+".link"),StandardCopyOption.ATOMIC_MOVE)
   }
   if(lock==null){channel.close();return null}
   registerWindowsHandler()
   return DesktopImportBridge(channel,lock,queue)
  }
  private fun registerWindowsHandler(){
   if(DesktopPaths.os!=DesktopOs.Windows)return
   val path=System.getProperty("jpackage.app-path")?.let(Path::of)?:return
   if(!Files.isRegularFile(path)||!path.fileName.toString().equals("UnifiedVPN.exe",true))return
   val key="HKCU\\Software\\Classes\\unifiedvpn"
   val command="\"${path.toAbsolutePath()}\" \"%1\""
   runCatching {
    listOf(listOf("reg.exe","add",key,"/ve","/d","URL:UnifiedVPN import","/f"),
     listOf("reg.exe","add",key,"/v","URL Protocol","/d","","/f"),
     listOf("reg.exe","add",key+"\\shell\\open\\command","/ve","/d",command,"/f"))
     .forEach { args -> val process=ProcessBuilder(args).redirectErrorStream(true).start();process.inputStream.use { it.readBytes() };check(process.waitFor()==0) }
   }
  }
 }
}
