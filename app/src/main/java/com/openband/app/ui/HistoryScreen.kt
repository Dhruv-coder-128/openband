package com.openband.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openband.app.data.Db
import com.openband.app.data.M
import com.openband.app.data.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val SLEEP = "sleep_total"

@Composable
fun HistoryScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val ver by Db.changes.collectAsState()
    val metrics = remember {
        listOf(M.STEPS, SLEEP, M.HR, M.RESTING_HR, M.SPO2, M.HRV, M.TEMP, M.STRESS, M.CALORIES, M.TOTAL_CAL, M.DISTANCE, M.RESP, M.WEIGHT, M.EXERCISE)
    }
    var metric by remember { mutableStateOf(M.STEPS) }
    var days by remember { mutableIntStateOf(14) }
    var values by remember { mutableStateOf<List<Pair<LocalDate, Double?>>>(emptyList()) }

    LaunchedEffect(ver, metric, days) {
        values = withContext(Dispatchers.IO) {
            val today = LocalDate.now()
            (days - 1 downTo 0).map { back ->
                val d = today.minusDays(back.toLong())
                d to if (metric == SLEEP) Stats.sleepFor(db, d)?.total else Stats.dayValue(db, metric, d)
            }
        }
    }

    fun fmt(v: Double?): String = if (metric == SLEEP) v?.let { Stats.fmtMinutes(it) } ?: "--" else M.format(metric, v)

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        ScreenTitle(LocalRace.current.names.history, "Your history, day by day")
        val open = LocalOpenMetric.current
        SectionCard(null) {
            LinkRow(AppIcons.Trends, LocalRace.current.primary, "Weekly and monthly story", "Your week or month, card by card", null) { open("page:report") }
            LinkRow(AppIcons.Heart, LocalRace.current.primary, "Recovery", "Readiness score and its last 14 days", null) { open("page:recovery") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            metrics.forEach { m ->
                FilterChip(selected = metric == m, onClick = { metric = m }, label = { Text(if (m == SLEEP) "Sleep" else M.label(m)) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(7, 14, 30, 90).forEach { d -> FilterChip(selected = days == d, onClick = { days = d }, label = { Text("${d}d", maxLines = 1, softWrap = false) }) }
        }
        SectionCard(if (metric == SLEEP) "Sleep" else M.label(metric)) {
            val present = values.mapNotNull { it.second }
            if (present.isNotEmpty()) {
                Text("Average: ${fmt(present.average())}", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            BarChart(values.map { it.second }, color = if (metric == SLEEP) metricColor(INST_SLEEP, LocalRace.current) else metricColor(metric, LocalRace.current), tip = { i -> fmt(values[i].second) to values[i].first.format(DateTimeFormatter.ofPattern("EEE d MMM")) })
        }
        SectionCard(LocalRace.current.pick("Lap times · by day", "By day")) {
            val f = DateTimeFormatter.ofPattern("EEE d MMM")
            values.reversed().forEach { (d, v) -> KeyValue(d.format(f), fmt(v)) }
        }
        Spacer(Modifier.height(16.dp))
    }
}
