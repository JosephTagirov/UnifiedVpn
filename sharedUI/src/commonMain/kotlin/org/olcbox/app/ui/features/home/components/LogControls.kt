package org.olcbox.app.ui.features.home.components
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.olcbox.app.data.logging.SupportLogs
import kotlinx.coroutines.launch
import org.olcbox.app.data.logging.LogPresentation
import org.olcbox.app.ui.localization.AppText as Text

@Composable
fun LogControls(logs: List<String>) {
    val selection by LogPresentation.selection.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val clipboard=LocalClipboardManager.current
    val scope=rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var deliveryMessage by remember { mutableStateOf<String?>(null) }
    val target by SupportLogs.target.collectAsState()
    var telegramReady by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(target) { telegramReady=if(target==null) null else SupportLogs.ready().getOrNull() }
    val shown=LogPresentation.lines(logs,selection)
    Column(Modifier.fillMaxWidth().padding(bottom=8.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()) { Text(if(selection.lastLines==0) "Все строки" else "Последние ${selection.lastLines} строк") }
                DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                    listOf(50,100,200,500,1000,0).forEach { count -> DropdownMenuItem(text={Text(if(count==0) "Все строки" else "Последние $count строк")},onClick={LogPresentation.select(selection.copy(lastLines=count));expanded=false}) }
                }
            }
            IconButton(enabled=shown.isNotEmpty(),onClick={clipboard.setText(AnnotatedString(LogPresentation.export(logs)))}) {
                Icon(Icons.Outlined.ContentCopy,contentDescription="Скопировать выбранные строки")
            }
        }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Checkbox(checked=selection.problemsOnly,onCheckedChange={LogPresentation.select(selection.copy(problemsOnly=it))})
            Text("Только ошибки и предупреждения",style=MaterialTheme.typography.labelMedium)
        }
        OutlinedButton(enabled=shown.isNotEmpty()&&!sending&&target!=null&&telegramReady!=false,onClick={
            sending=true;deliveryMessage=null
            scope.launch {
                SupportLogs.send(logs).onSuccess { deliveryMessage="Отправлено владельцу в Telegram: $it строк" }
                    .onFailure { deliveryMessage=it.message ?: "Не удалось отправить лог" }
                sending=false
            }
        },modifier=Modifier.fillMaxWidth()) { Text(if(sending) "Отправляю…" else "Отправить владельцу в Telegram") }
        Text(if(target==null) "Для отправки добавь или обнови набор из своего кабинета. Можно использовать обычную кнопку «Поделиться»." else if(telegramReady==false) "Владелец ещё не подключил отдельного бота для логов." else "Только выбранные строки. Ключи, пароли и личные ссылки скрываются.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        deliveryMessage?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
        Text("Показано ${shown.size} из ${logs.size}. Копирование, сохранение и отправка используют этот выбор.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
