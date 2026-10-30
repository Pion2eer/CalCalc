package ltd.pion.calcalc.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ltd.pion.calcalc.sync.FriendFood
import ltd.pion.calcalc.sync.Link
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun kindName(family: Boolean) = if (family) "Семья" else "Друг"

/** The «Друзья и семья» part of the profile: invites to answer, people to open, a way to invite someone new. */
@Composable
fun FriendsBlock(
    links: List<Link>,
    busy: Boolean,
    error: String?,
    onInvite: (String, Boolean) -> Unit,
    onAnswer: (String, Boolean) -> Unit,
    onOpen: (Link) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    Text(
        "Найдите близких по e-mail. Когда человек примет приглашение, вы будете видеть еду друг друга и сможете добавить себе блюдо со своей порцией.",
        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    links.filter { it.incoming && !it.accepted }.forEach { l ->
        Surface(shape = RoundedCornerShape(16.dp), color = Accent.copy(alpha = 0.10f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("${l.email} приглашает вас: ${kindName(l.family).lowercase()}", fontSize = 15.sp)
                Text("Вы будете видеть еду друг друга", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ onAnswer(l.email, true) }, enabled = !busy) { Text("Принять") }
                    OutlinedButton({ onAnswer(l.email, false) }, enabled = !busy) { Text("Отклонить") }
                }
            }
        }
    }
    links.filter { it.accepted }.sortedBy { !it.family }.forEach { l ->
        Row(
            Modifier.fillMaxWidth().clickable { onOpen(l) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(l.email)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(l.email.substringBefore('@'), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(kindName(l.family), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Еда ›", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
    links.filter { !it.incoming && !it.accepted }.forEach { l ->
        Text("${l.email}: ждём ответа", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    error?.let { Text(it, color = Warn, fontSize = 13.sp) }
    OutlinedButton({ adding = true }, Modifier.fillMaxWidth(), enabled = !busy) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Добавить по e-mail")
    }
    if (adding) InviteDialog(onDismiss = { adding = false }, onInvite = { e, f -> onInvite(e, f); adding = false })
}

@Composable
private fun Avatar(email: String) {
    Surface(shape = CircleShape, color = HeroBg, contentColor = Color.White, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(email.take(1).uppercase(), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun InviteDialog(onDismiss: () -> Unit, onInvite: (String, Boolean) -> Unit) {
    var email by remember { mutableStateOf("") }
    var family by remember { mutableStateOf(true) }
    val valid = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""").matches(email.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Пригласить") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    email, { email = it.take(100) }, label = { Text("E-mail в Google") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(family, { family = true }, { Text("Семья") })
                    FilterChip(!family, { family = false }, { Text("Друг") })
                }
                Text(
                    "Человек должен хоть раз войти в CalCalc. Он увидит приглашение у себя в профиле.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton({ onInvite(email.trim(), family) }, enabled = valid) { Text("Пригласить") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun FriendScreen(vm: AppViewModel, link: Link, onClose: () -> Unit, modifier: Modifier) {
    val feed by vm.feed.collectAsState()
    val error by vm.feedError.collectAsState()
    LaunchedEffect(link.email) { vm.loadFeed(link.email) }
    BackHandler(onBack = onClose)
    FriendContent(
        link, feed, error, LocalDate.now(),
        onCopy = vm::copyFood, onRemove = { vm.removeFriend(link.email); onClose() }, onClose = onClose, modifier = modifier,
    )
}

private val ru = Locale("ru")
private val dayFmt = DateTimeFormatter.ofPattern("d MMMM, EEEE", ru)
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

/** A friend's food by day; a tap on a dish asks for the portion and adds it to my today. */
@Composable
fun FriendContent(
    link: Link,
    feed: List<FriendFood>?,
    error: String?,
    today: LocalDate,
    onCopy: (FriendFood, Double) -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picked by remember { mutableStateOf<FriendFood?>(null) }
    var removing by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Column(Modifier.weight(1f)) {
                Text(link.email.substringBefore('@'), fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${kindName(link.family)} · ${link.email}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        added?.let {
            Text("Добавлено в сегодня: $it", color = Accent, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        when {
            feed == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            feed.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    error ?: "Пока пусто. Еда появится, когда приложение друга синхронизируется.",
                    color = if (error != null) Warn else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp,
                )
            }
            else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                feed.groupBy { it.day }.forEach { (day, list) ->
                    item(key = "d$day") {
                        val d = runCatching { LocalDate.parse(day) }.getOrNull()
                        val name = when (d) {
                            today -> "Сегодня"
                            today.minusDays(1) -> "Вчера"
                            null -> day
                            else -> d.format(dayFmt)
                        }
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp)) {
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${list.sumOf { it.kcal }} ккал", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                    }
                    items(list, key = { "${it.at}${it.title}" }) { f -> FoodCard(f) { picked = f } }
                }
            }
        }
        TextButton({ removing = true }, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)) {
            Text("Убрать из друзей и семьи", color = Danger)
        }
    }
    picked?.let { f ->
        PortionDialog(f, onDismiss = { picked = null }, onAdd = { k -> onCopy(f, k); added = f.title; picked = null })
    }
    if (removing) AlertDialog(
        onDismissRequest = { removing = false },
        title = { Text("Убрать ${link.email.substringBefore('@')}?") },
        text = { Text("Вы перестанете видеть еду друг друга. Пригласить снова можно в любой момент.") },
        confirmButton = { TextButton({ removing = false; onRemove() }) { Text("Убрать", color = Danger) } },
        dismissButton = { TextButton({ removing = false }) { Text("Отмена") } },
    )
}

@Composable
private fun FoodCard(f: FriendFood, onClick: () -> Unit) {
    Surface(
        onClick, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = CardShadow,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    buildAnnotatedString {
                        append(Instant.ofEpochMilli(f.at).atZone(ZoneId.systemDefault()).format(timeFmt))
                        if (f.protein != null || f.fat != null || f.carbs != null) {
                            append("   ")
                            withStyle(SpanStyle(color = Color(0xFF42A5F5))) { append("${Math.round(f.protein ?: 0.0)}") }
                            append(" | ")
                            withStyle(SpanStyle(color = Color(0xFFEC407A))) { append("${Math.round(f.fat ?: 0.0)}") }
                            append(" | ")
                            withStyle(SpanStyle(color = Color(0xFFF9A825))) { append("${Math.round(f.carbs ?: 0.0)}") }
                        }
                    },
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("${f.kcal}", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(" ккал", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** How much of the friend's dish: a share of their portion, or grams when the AI weighed it. */
@Composable
fun PortionDialog(f: FriendFood, onDismiss: () -> Unit, onAdd: (Double) -> Unit) {
    val baseGrams = remember(f.items) {
        runCatching {
            val a = org.json.JSONArray(f.items ?: "[]")
            (0 until a.length()).sumOf { a.getJSONObject(it).optInt("grams") }
        }.getOrDefault(0).takeIf { it > 0 }
    }
    var factor by remember { mutableStateOf(1.0) }
    var grams by remember { mutableStateOf(baseGrams?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(f.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Сколько вы съели?", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5, 1.0, 1.5, 2.0).forEach { k ->
                        FilterChip(
                            factor == k,
                            { factor = k; baseGrams?.let { grams = Math.round(it * k).toString() } },
                            { Text("×" + if (k % 1.0 == 0.0) k.toInt().toString() else k.toString().replace('.', ',')) },
                        )
                    }
                }
                if (baseGrams != null) OutlinedTextField(
                    grams,
                    { v ->
                        grams = v.filter { it.isDigit() }.take(5)
                        grams.toIntOrNull()?.takeIf { it > 0 }?.let { factor = it.toDouble() / baseGrams }
                    },
                    label = { Text("Вес, г (у друга $baseGrams г)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text("${Math.round(f.kcal * factor)} ккал", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Accent)
            }
        },
        confirmButton = { TextButton({ onAdd(factor) }, enabled = factor > 0) { Text("Добавить себе") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
}
