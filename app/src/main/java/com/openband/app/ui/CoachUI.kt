package com.openband.app.ui

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openband.app.ai.AiClient
import com.openband.app.ai.AiProvider
import com.openband.app.ai.AiSettings
import com.openband.app.ai.Verify
import com.openband.app.data.Coach
import com.openband.app.data.Db
import com.openband.app.data.M
import com.openband.app.data.Planner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable
private fun rememberPlan(gu: Boolean): Planner.Plan? {
    val look = LocalRace.current
    val ctx = LocalContext.current
    val ver by Db.changes.collectAsState()
    var plan by remember { mutableStateOf<Planner.Plan?>(null) }
    LaunchedEffect(ver, gu, look.prefs.stepGoal, look.prefs.sleepGoalMin, look.prefs.age) {
        plan = withContext(Dispatchers.IO) { runCatching { Planner.plan(Db.get(ctx), look.prefs, gu) }.getOrNull() } ?: plan
    }
    return plan
}

@Composable
private fun levelColor(l: Planner.Level) = toneColor(when (l) { Planner.Level.REST -> 2; Planner.Level.EASY -> 1; Planner.Level.UNKNOWN -> -1; else -> 0 })

/** Home card: today's verdict and what to do. */
@Composable
fun CoachCard() {
    val look = LocalRace.current
    val ctx = LocalContext.current
    val open = LocalOpenMetric.current
    val gu = AiSettings(ctx).language == "Gujarati"
    val plan = rememberPlan(gu) ?: return
    SectionCard(null, Modifier.clickable { open("page:coach") }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MetricIcon(AppIcons.Coach, look.primary, 40.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(plan.headline, style = MaterialTheme.typography.titleMedium, color = look.text)
                Text(plan.doneToday ?: plan.move, style = MaterialTheme.typography.bodySmall, color = look.dim, maxLines = 2)
            }
            Icon(AppIcons.Chevron, null, Modifier.size(18.dp), tint = look.dim)
        }
    }
}

// ================================================================================================ chat memory

private class Msg(val mine: Boolean, val text: String, val note: String = "")

private fun loadChat(ctx: Context): List<Msg> = runCatching {
    val a = JSONArray(ctx.getSharedPreferences("ai", Context.MODE_PRIVATE).getString("chat", "[]"))
    (0 until a.length()).map { i -> a.getJSONObject(i).let { Msg(it.getBoolean("m"), it.getString("t"), it.optString("n")) } }
}.getOrDefault(emptyList())

private fun saveChat(ctx: Context, list: List<Msg>) {
    val a = JSONArray()
    list.takeLast(14).forEach { a.put(JSONObject().put("m", it.mine).put("t", it.text).put("n", it.note)) }
    ctx.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().putString("chat", a.toString()).apply()
}

private fun prompt(language: String, plan: Planner.Plan, data: String) =
    "You are \"Coach\", a warm and practical fitness coach chatting with the user inside their band app. The app has already analysed the data: " +
        "keep your advice consistent with its verdict and plan.\n" +
        "How to answer:\n- Answer the question directly in the first sentence, the way a person talks.\n" +
        "- Then at most 3 short bullet points, using the user's own numbers where they help (copy them exactly).\n" +
        "- General questions (food, exercise form, motivation): just answer helpfully with this user in mind.\n" +
        "- Never reply that data is missing unless the question truly cannot be answered, and never reply with only that.\n" +
        "- No diagnosis. Under 90 words. Plain text, no headings, no tables.\n" +
        "- Reply in " + (if (language == "Gujarati") "simple everyday Gujarati (Gujarati script), numbers in digits 0-9." else "$language, numbers in digits 0-9.") +
        "\n\nAPP VERDICT: ${plan.headline}. ${plan.reason}\nAPP PLAN: ${plan.move}. ${plan.moveDetail} Steps target ${plan.stepsTarget} (now ${plan.stepsNow}). " +
        "Bedtime ${plan.bedtime ?: "unknown"}.${plan.doneToday?.let { " $it." } ?: ""}\nAPP NOTES: ${plan.notes.joinToString(" ") { it.text }}\n\nDATA:\n$data"

// ================================================================================================ coach page

/**
 * Two fixed views under one header: "Today" (verdict and plan on one screen) and "Chat" (messages in the middle, the
 * question box pinned to the bottom). After each question the list moves to it, so the answer is read without scrolling.
 */
@Composable
fun CoachScreen(modifier: Modifier, onBack: () -> Unit) {
    val look = LocalRace.current
    val p = look.prefs
    val ctx = LocalContext.current
    val open = LocalOpenMetric.current
    val scope = rememberCoroutineScope()
    val ai = remember { AiSettings(ctx) }
    var language by remember { mutableStateOf(ai.language) }
    val gu = language == "Gujarati"
    val t = Planner.L(gu)
    val plan = rememberPlan(gu)
    val chat = remember { mutableStateListOf<Msg>().apply { addAll(loadChat(ctx)) } }
    var question by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val connected = remember { ai.ready }
    var consent by remember { mutableStateOf(ai.consent) }
    // the newest message is the only one that is "typed out" in the console looks
    var fresh by remember { mutableIntStateOf(-1) }

    fun add(m: Msg) { chat.add(m); if (!m.mine) fresh = chat.lastIndex; saveChat(ctx, chat) }

    fun quick(q: Planner.Question) {
        tab = 1
        add(Msg(true, if (gu) q.gu else q.en))
        scope.launch {
            val a = withContext(Dispatchers.IO) { runCatching { Planner.answer(Db.get(ctx), p, q, gu) }.getOrElse { t("જવાબ બનાવી શકાયો નહીં.", "Could not work that out.") } }
            add(Msg(false, a))
        }
    }

    fun askAi(q: String) {
        val today = plan ?: return
        val history = chat.takeLast(6).map { AiClient.Turn(if (it.mine) "user" else "assistant", it.text) }
        add(Msg(true, q))
        busy = true
        scope.launch {
            try {
                val data = withContext(Dispatchers.IO) { Coach.summary(Db.get(ctx), p, ai.includeProfile) }
                val system = prompt(language, today, data)
                val turns = listOf(AiClient.Turn("system", system)) + history + AiClient.Turn("user", q)
                var reply = AiClient.chat(ai, turns).first
                var wrong = Verify.unsupported(reply, system + "\n" + q)
                if (wrong.isNotEmpty()) {
                    val second = AiClient.chat(ai, turns + AiClient.Turn("assistant", reply) +
                        AiClient.Turn("user", "These numbers are not in my data: ${wrong.joinToString()}. Answer again without them.")).first
                    val still = Verify.unsupported(second, system + "\n" + q)
                    if (still.size < wrong.size) { reply = second; wrong = still }
                }
                add(Msg(false, reply, if (wrong.isEmpty()) "AI" else t("AI · આ આંકડા તમારા ડેટામાં નથી: ${wrong.joinToString()}", "AI · not found in your data: ${wrong.joinToString()}")))
            } catch (e: Exception) {
                add(Msg(false, t("AI જવાબ ન આપી શક્યું. ", "The AI could not answer. ") + (e.message ?: "").lineSequence().first()))
            } finally {
                busy = false
            }
        }
    }

    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)).padding(horizontal = 14.dp)) {
        // ---------------------------------------------------------------- header
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            MetricIcon(AppIcons.Coach, look.primary, 40.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(look.style.prompt + t("કોચ", "Coach"), style = MaterialTheme.typography.titleLarge, color = look.text, maxLines = 1)
                Text(
                    if (connected && consent) t("AI જોડાયેલું", "AI connected") else t("તમારા ફોન પર", "On your phone"),
                    style = MaterialTheme.typography.labelSmall, color = look.dim, maxLines = 1,
                )
            }
            Row(Modifier.clip(look.pill()).background(look.panelHi).padding(3.dp)) {
                listOf("Gujarati" to "ગુ", "English" to "EN").forEach { (id, label) ->
                    val on = (id == "Gujarati") == gu
                    Text(
                        label, style = MaterialTheme.typography.labelMedium, color = if (on) look.bg else look.dim,
                        modifier = Modifier.clip(look.pill()).background(if (on) look.text else look.panelHi).clickable { language = id; ai.language = id }.padding(horizontal = 11.dp, vertical = 6.dp),
                    )
                }
            }
            Icon(AppIcons.Settings, "AI settings", Modifier.padding(start = 4.dp).size(38.dp).clip(CircleShape).clickable { open("page:aisetup") }.padding(8.dp), tint = look.dim)
        }
        // ---------------------------------------------------------------- Today | Chat
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(look.pill()).background(look.panelHi).padding(3.dp)) {
            listOf(t("આજે", "Today"), t("વાત કરો", "Chat")).forEachIndexed { i, label ->
                val on = tab == i
                Box(Modifier.weight(1f).clip(look.pill()).background(if (on) look.panel else look.panelHi).clickable { tab = i }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (on) look.text else look.dim)
                }
            }
        }

        if (tab == 0) TodayView(plan, t, Modifier.weight(1f)) { tab = 1 }
        else {
            val list = rememberLazyListState()
            // bring the latest question to the top; its answer appears right under it
            LaunchedEffect(chat.size, busy) {
                val last = chat.indexOfLast { it.mine }
                if (last >= 0) list.animateScrollToItem(last)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
                if (chat.isEmpty()) item { Welcome(t, gu) { quick(it) } }
                itemsIndexed(chat) { i, m -> Message(m, animate = i == fresh) }
                if (busy) item { Typing() }
                // room below so the last question can sit at the top
                if (chat.isNotEmpty()) item { Spacer(Modifier.fillParentMaxHeight(0.7f)) }
            }
            if (chat.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Planner.Question.entries.forEach { q -> FilterChip(selected = false, onClick = { if (!busy) quick(q) }, label = { Text(if (gu) q.gu else q.en) }) }
                Text(t("સાફ કરો", "Clear"), Modifier.clickable { chat.clear(); saveChat(ctx, chat) }.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = look.dim)
            }
            when {
                connected && consent -> AskBar(question, { question = it.take(400) }, t("કંઈ પણ પૂછો…", "Ask anything…"), !busy && question.isNotBlank() && plan != null) {
                    askAi(question.trim()); question = ""
                }
                connected -> Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(look.panelShape).background(look.panel).padding(12.dp)) {
                    Text(consentText(t, ai.provider), style = MaterialTheme.typography.labelSmall, color = look.text)
                    RaceButton(t("મંજૂર છે", "Allow"), Modifier.fillMaxWidth().padding(top = 8.dp)) { ai.consent = true; consent = true }
                }
                else -> RaceButton(t("ખુલ્લા સવાલ માટે AI જોડો", "Connect AI for open questions"), Modifier.fillMaxWidth().padding(bottom = 10.dp), primary = false) { open("page:aisetup") }
            }
        }
    }
}

/** Verdict, plan and notes, sized to fit one screen. */
@Composable
private fun TodayView(plan: Planner.Plan?, t: Planner.L, modifier: Modifier, onAsk: () -> Unit) {
    val look = LocalRace.current
    if (plan == null) { Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(t("તમારો ડેટા વાંચું છું…", "Reading your data…"), color = look.dim) }; return }
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionCard(null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (plan.score != null) StudioRing(plan.score / 100f, levelColor(plan.level), 66.dp, 7.dp) {
                        Text("${plan.score}", style = MaterialTheme.typography.titleLarge, color = look.text)
                    }
                    Column(Modifier.padding(start = if (plan.score != null) 14.dp else 0.dp).weight(1f)) {
                        Text(plan.headline, style = MaterialTheme.typography.titleLarge, color = look.text)
                        Text(plan.reason, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = look.dim)
                        if (plan.provisional) Text(t("શરૂઆતનો અંદાજ", "Early estimate"), style = MaterialTheme.typography.labelSmall, color = look.warn)
                    }
                }
            }
            SectionCard(null) {
                PlanRow(AppIcons.Run, plan.move, plan.doneToday ?: plan.moveDetail, plan.doneToday != null)
                HorizontalDivider()
                PlanRow(AppIcons.Steps, t("પગલાં ", "Steps ") + "%,d / %,d".format(plan.stepsNow, plan.stepsTarget), plan.stepsDetail, plan.stepsNow >= plan.stepsTarget)
                HorizontalDivider()
                PlanRow(AppIcons.Moon, t("સૂવાનો સમય ", "Bedtime ") + (plan.bedtime ?: "--"), plan.bedDetail, false)
            }
            // notes sit side by side: swipe sideways instead of scrolling down
            if (plan.notes.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                plan.notes.forEach { n ->
                    Column(Modifier.width(232.dp).fillMaxHeight().reveal().clip(look.panelShape).background(look.panel).border(1.dp, look.border, look.panelShape).padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).clip(CircleShape).background(toneColor(n.tone)))
                            Text(n.title, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = look.text)
                        }
                        Text(n.text, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = look.dim)
                    }
                }
            }
        }
        RaceButton(t("કોચ ને પૂછો", "Ask your coach"), Modifier.fillMaxWidth().padding(vertical = 10.dp), onClick = onAsk)
    }
}

@Composable
private fun PlanRow(icon: ImageVector, main: String, detail: String, done: Boolean) {
    val look = LocalRace.current
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        MetricIcon(if (done) AppIcons.Check else icon, if (done) look.ok else look.primary, 34.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(main, style = MaterialTheme.typography.titleSmall, color = look.text)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = if (done) look.ok else look.dim, maxLines = 3)
        }
    }
}

@Composable
private fun Welcome(t: Planner.L, gu: Boolean, onPick: (Planner.Question) -> Unit) {
    val look = LocalRace.current
    Column(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MetricIcon(AppIcons.Coach, look.primary, 72.dp)
        Text(look.style.prompt + themedText(t("શું જાણવું છે?", "What do you want to know?")), Modifier.padding(top = 14.dp, bottom = 18.dp), style = MaterialTheme.typography.titleLarge, color = look.text, textAlign = TextAlign.Center)
        Planner.Question.entries.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { q ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight().reveal().clip(look.panelShape).background(look.panel).border(1.dp, look.border, look.panelShape).clickable { onPick(q) }.padding(16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { Text(if (gu) q.gu else q.en, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = look.text) }
                }
            }
        }
    }
}

/** Your words in a bubble on the right; the coach's answer as plain, well-spaced text on the left. */
@Composable
private fun Message(m: Msg, animate: Boolean) {
    val look = LocalRace.current
    if (m.mine) {
        Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), contentAlignment = Alignment.CenterEnd) {
            Text(
                (if (look.isConsole) look.style.prompt else "") + m.text, style = MaterialTheme.typography.bodyMedium, color = onColor(look.primary),
                modifier = Modifier.widthIn(max = 300.dp).clip(look.tile(20)).background(look.primary).padding(horizontal = 15.dp, vertical = 10.dp),
            )
        }
        return
    }
    // console looks type the newest answer out
    var shown by remember(m) { mutableIntStateOf(if (animate && look.isConsole && look.prefs.animate) 0 else m.text.length) }
    LaunchedEffect(m) { while (shown < m.text.length) { delay(12); shown = (shown + 2).coerceAtMost(m.text.length) } }
    val text = m.text.take(shown) + if (shown < m.text.length) "▌" else ""
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        MetricIcon(AppIcons.Coach, look.primary, 28.dp)
        Column(Modifier.weight(1f).padding(start = 8.dp).clip(look.tile(18)).background(look.panel).border(1.dp, look.border, look.tile(18)).padding(horizontal = 14.dp, vertical = 11.dp)) {
            SelectionContainer {
                Column {
                    text.lines().filter { it.isNotBlank() }.forEachIndexed { i, line ->
                        val bullet = line.trimStart().startsWith("•") || line.trimStart().startsWith("- ")
                        if (bullet) Row(Modifier.padding(top = 6.dp)) {
                            Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(look.primary))
                            Text(line.trimStart().removePrefix("•").removePrefix("-").trim(), Modifier.padding(start = 9.dp), style = MaterialTheme.typography.bodyMedium, color = look.text)
                        } else Text(line, Modifier.padding(top = if (i == 0) 0.dp else 6.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal, color = look.text)
                    }
                }
            }
            if (m.note.isNotEmpty()) Text(m.note, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = if (m.note == "AI") look.dim else look.warn)
        }
    }
}

@Composable
private fun Typing() {
    val look = LocalRace.current
    var n by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(320); n = (n + 1) % 3 } }
    Row(Modifier.padding(vertical = 8.dp, horizontal = 36.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { Box(Modifier.size(8.dp).clip(CircleShape).background(look.primary.copy(alpha = if (it == n) 1f else 0.3f))) }
    }
}

/** The question box: one rounded field with a send button inside the same row. */
@Composable
private fun AskBar(value: String, onChange: (String) -> Unit, hint: String, canSend: Boolean, onSend: () -> Unit) {
    val look = LocalRace.current
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        BasicTextField(
            value = value, onValueChange = onChange, maxLines = 4,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = look.text), cursorBrush = SolidColor(look.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().clip(look.tile(26)).background(look.panel).border(1.dp, look.border, look.tile(26)).padding(horizontal = 18.dp, vertical = 14.dp)) {
                    if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = look.dim)
                    inner()
                }
            },
        )
        Box(
            Modifier.padding(start = 8.dp).size(52.dp).clip(look.pill()).background(if (canSend) look.primary else look.panelHi).clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) { Icon(AppIcons.Send, "Send", Modifier.size(24.dp), tint = if (canSend) onColor(look.primary) else look.dim) }
    }
}

private fun consentText(t: Planner.L, provider: AiProvider) = t(
    "તમે પૂછો ત્યારે તમારા 14 દિવસના આંકડા (નામ, ફોટો કે location વગર) ${provider.label} ને મોકલાશે. ",
    "When you ask, your last 14 days of numbers (no name, photo or location) are sent to ${provider.label}. ",
) + provider.privacy

// ================================================================================================ connecting an AI

private fun detect(key: String): AiProvider? = when {
    key.startsWith("AIza") || key.startsWith("AQ.") -> AiProvider.GEMINI
    key.startsWith("gsk_") -> AiProvider.GROQ
    key.startsWith("sk-or-") -> AiProvider.OPENROUTER
    else -> null
}

/** One field and one button: the service is recognised from the key and a working model is picked automatically. */
@Composable
fun ConnectAi(gu: Boolean, onDone: () -> Unit) {
    val look = LocalRace.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ai = remember { AiSettings(ctx) }
    val t = Planner.L(gu)
    var key by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<AiProvider?>(null) }
    var url by remember { mutableStateOf(ai.customUrl) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val provider = detect(key) ?: chosen

    Text(t("મફત key લો, અહીં paste કરો. બાકી બધું આપોઆપ.", "Get a free key and paste it here. The rest is automatic."), style = MaterialTheme.typography.bodySmall, color = look.dim)
    RaceButton(t("Google થી મફત key લો", "Get a free key from Google"), Modifier.fillMaxWidth().padding(top = 8.dp), primary = false) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AiProvider.GEMINI.keyPage))) }
    }
    OutlinedTextField(
        value = key, onValueChange = { key = it.trim() }, label = { Text(t("Key અહીં paste કરો", "Paste your key")) }, singleLine = true,
        visual = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    )
    if (key.length > 8 && detect(key) == null) {
        Text(t("આ કઈ service ની key છે?", "Which service is this key from?"), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = look.text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AiProvider.entries.forEach { pr -> FilterChip(selected = chosen == pr, onClick = { chosen = pr }, label = { Text(pr.label) }) }
        }
        if (chosen == AiProvider.CUSTOM) OutlinedTextField(value = url, onValueChange = { url = it.trim() }, label = { Text("https://…/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
    if (provider != null) Text(consentText(t, provider), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = look.dim)
    RaceButton(if (busy) t("જોડું છું…", "Connecting…") else t("જોડો અને મંજૂરી આપો", "Connect and allow"), Modifier.fillMaxWidth().padding(top = 10.dp), enabled = !busy && provider != null && key.length > 8) {
        val pr = provider ?: return@RaceButton
        busy = true; failed = false
        scope.launch {
            try {
                ai.provider = pr
                if (pr == AiProvider.CUSTOM) ai.customUrl = url
                ai.apiKey = key
                ai.manualModel = false
                AiClient.findWorking(ai, want = 4) { message = it }
                ai.consent = true
                key = ""; message = null
                onDone()
            } catch (e: Exception) {
                failed = true
                message = e.message ?: t("જોડાઈ શકાયું નહીં.", "Could not connect.")
            } finally {
                busy = false
            }
        }
    }
    message?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = if (failed) look.danger else look.dim) }
}

@Composable
fun AiSetupScreen(modifier: Modifier, onBack: () -> Unit) {
    val look = LocalRace.current
    val p = look.prefs
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ai = remember { AiSettings(ctx) }
    var version by remember { mutableIntStateOf(0) }
    val connected = remember(version) { ai.apiKey.isNotEmpty() && ai.working.isNotEmpty() }
    var language by remember { mutableStateOf(ai.language) }
    var profile by remember { mutableStateOf(ai.includeProfile) }
    var advanced by remember { mutableStateOf(false) }
    var replace by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val gu = language == "Gujarati"
    val t = Planner.L(gu)

    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        PageHeader(t("AI કોચ", "AI coach"), t("વૈકલ્પિક: ખુલ્લા સવાલો માટે", "Optional: for free-form questions"), onBack)
        if (connected && !replace) SectionCard(null) {
            LinkRow(AppIcons.Check, look.ok, t("${ai.provider.label} જોડાયેલું છે", "${ai.provider.label} is connected"), t("${ai.working.size} model ચાલે છે. સારો model આપોઆપ વપરાય છે.", "${ai.working.size} working models. The best one is used automatically."), null, null)
            RaceButton(t("બીજી key નાખો", "Use another key"), Modifier.fillMaxWidth().padding(top = 8.dp), primary = false) { replace = true }
        } else SectionCard(t("AI જોડો", "Connect AI")) { ConnectAi(gu) { replace = false; version++ } }

        SectionCard(t("જવાબ", "Answers")) {
            Text(t("ભાષા", "Language"), style = MaterialTheme.typography.labelMedium, color = look.dim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                listOf("Gujarati" to "ગુજરાતી", "English" to "English", "Hindi" to "हिन्दी").forEach { (id, label) ->
                    FilterChip(selected = language == id, onClick = { language = id; ai.language = id }, label = { Text(label) })
                }
            }
            Toggle(t("ઉંમર, લિંગ, ઊંચાઈ, વજન પણ મોકલો", "Also send age, sex, height, weight"), profile) { profile = it; ai.includeProfile = it }
            Text(t("AI ને શું મોકલાય છે તે જુઓ", "See what is sent to the AI"), Modifier.clickable { scope.launch { preview = withContext(Dispatchers.IO) { Coach.summary(Db.get(ctx), p, ai.includeProfile) } } }.padding(vertical = 10.dp), style = MaterialTheme.typography.labelMedium, color = look.primary)
        }
        if (connected) {
            Text(t(if (advanced) "વધુ વિકલ્પ છુપાવો" else "વધુ વિકલ્પ", if (advanced) "Hide advanced" else "Advanced"), Modifier.clickable { advanced = !advanced }.padding(vertical = 12.dp, horizontal = 4.dp), style = MaterialTheme.typography.labelMedium, color = look.dim)
            if (advanced) SectionCard(null) {
                val models = remember(version) { listOf(t("આપોઆપ (સૂચવેલ)", "Automatic (recommended)")) + AiClient.rank(ai.provider, ai.working) }
                var pick by remember(version) { mutableIntStateOf(if (ai.manualModel) models.indexOf(ai.model).coerceAtLeast(0) else 0) }
                AppDropdown(models, pick, { i -> pick = i; ai.manualModel = i > 0; if (i > 0) ai.model = models[i] }, label = "Model")
                RaceButton(if (busy) "…" else t("Model ફરી ચકાસો", "Test models again"), Modifier.fillMaxWidth().padding(top = 10.dp), primary = false, enabled = !busy) {
                    busy = true
                    scope.launch {
                        message = try { AiClient.findWorking(ai) { message = it }.size.let { t("$it model ચાલે છે.", "$it models work.") } } catch (e: Exception) { e.message }
                        busy = false; version++
                    }
                }
                message?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = look.dim) }
                Text(t("આ key કાઢી નાખો", "Remove this key"), Modifier.clickable { ai.apiKey = ""; ai.working = emptyList(); version++; advanced = false }.padding(vertical = 10.dp), style = MaterialTheme.typography.labelMedium, color = look.danger)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    preview?.let { text ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(t("આ મોકલાય છે", "This is what is sent")) },
            text = { Column(Modifier.height(360.dp).verticalScroll(rememberScrollState())) { Text(text, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { preview = null }) { Text("OK", color = look.primary) } },
        )
    }
}
