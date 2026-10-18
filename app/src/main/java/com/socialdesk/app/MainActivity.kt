package com.socialdesk.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val Context.ds by preferencesDataStore("socialdesk_phase2")
private val URL_KEY = stringPreferencesKey("api_url")
private val KEY_KEY = stringPreferencesKey("api_key")

data class RemotePage(val id: String, val name: String)
data class RemoteJob(val id: Int, val pageName: String, val message: String, val scheduledAt: String, val status: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { SocialDeskApp() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SocialDeskApp() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var apiUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var connection by remember { mutableStateOf("Not connected") }
    var pages by remember { mutableStateOf(listOf<RemotePage>()) }
    var jobs by remember { mutableStateOf(listOf<RemoteJob>()) }
    var selectedPage by remember { mutableStateOf("") }
    var postText by remember { mutableStateOf("") }
    var postLink by remember { mutableStateOf("") }
    var scheduleAt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        apiUrl = ctx.ds.data.first()[URL_KEY] ?: ""
        apiKey = ctx.ds.data.first()[KEY_KEY] ?: ""
        loaded = true
    }
    LaunchedEffect(notice) { if (notice.isNotBlank()) { snackbar.showSnackbar(notice); notice = "" } }

    suspend fun call(path: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val base = apiUrl.trim().trimEnd('/')
            require(base.startsWith("https://")) { "Backend URL must start with https:// for secure connections." }
            val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("X-API-Key", apiKey)
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            try {
                if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
                val result = JSONObject(text)
                if (code !in 200..299) throw IllegalStateException(result.optString("detail", "HTTP $code"))
                result
            } finally { conn.disconnect() }
        }

    fun refresh() {
        scope.launch {
            busy = true
            try {
                val p = call("/api/pages").optJSONArray("pages") ?: JSONArray()
                pages = (0 until p.length()).map { val o = p.getJSONObject(it); RemotePage(o.optString("id"), o.optString("name")) }
                val j = call("/api/jobs").optJSONArray("jobs") ?: JSONArray()
                jobs = (0 until j.length()).map { val o = j.getJSONObject(it); RemoteJob(o.optInt("id"), o.optString("page_name"), o.optString("message"), o.optString("scheduled_at"), o.optString("status")) }
                connection = "Connected · ${pages.size} Page(s)"
                if (selectedPage.isBlank() && pages.isNotEmpty()) selectedPage = pages.first().id
            } catch (e: Exception) { connection = "Connection failed"; notice = e.message ?: "Could not connect" }
            busy = false
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF3157C8), secondary = Color(0xFF0D9488), background = Color(0xFFF5F7FB))) {
        Scaffold(
            topBar = { TopAppBar(title = { Column { Text("SocialDesk", fontWeight = FontWeight.Bold); Text("Page publishing workspace", style = MaterialTheme.typography.labelSmall) } }) },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                    NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.Send, null) }, label = { Text("Publish") })
                    NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.CalendarMonth, null) }, label = { Text("Queue") })
                    NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Default.Settings, null) }, label = { Text("Setup") })
                }
            }
        ) { padding ->
            if (!loaded) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else when (tab) {
                0 -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Cloud, null, tint = Color.White, modifier = Modifier.size(34.dp))
                                Text("SocialDesk Phase 2", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text("Mobile control panel for your authorized Facebook Pages.", color = Color.White)
                                Text(connection, color = Color.White, style = MaterialTheme.typography.labelLarge)
                                Button(onClick = { refresh() }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = MaterialTheme.colorScheme.primary)) { Text(if (busy) "Connecting…" else "Connect / refresh") }
                            }
                        }
                    }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Metric("Pages", pages.size.toString(), Modifier.weight(1f)); Metric("Queued jobs", jobs.count { it.status == "scheduled" }.toString(), Modifier.weight(1f)) } }
                    item { Text("Recent jobs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                    if (jobs.isEmpty()) item { EmptyState("No server jobs yet", "Connect your backend, then schedule or publish a post.") }
                    items(jobs.take(5), key = { it.id }) { JobCard(it) }
                }
                1 -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Create Page post", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                    item { Text("Publishing uses the official Meta API and requires a valid server-side Page token with the right permissions.") }
                    item {
                        Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            var expanded by remember { mutableStateOf(false) }
                            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(pages.firstOrNull { it.id == selectedPage }?.name ?: "Select a connected Page") }
                            DropdownMenu(expanded, { expanded = false }) {
                                pages.forEach { page -> DropdownMenuItem(text = { Text(page.name) }, onClick = { selectedPage = page.id; expanded = false }) }
                            }
                            OutlinedTextField(postText, { postText = it }, label = { Text("Post text") }, modifier = Modifier.fillMaxWidth(), minLines = 4)
                            OutlinedTextField(postLink, { postLink = it }, label = { Text("Link to share (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Button(onClick = {
                                scope.launch {
                                    if (selectedPage.isBlank()) { notice = "No Page selected. Check your backend setup."; return@launch }
                                    if (postText.isBlank() && postLink.isBlank()) { notice = "Enter post text or a link."; return@launch }
                                    busy = true
                                    try {
                                        val body = JSONObject().put("page_id", selectedPage).put("message", postText).put("link", postLink)
                                        val result = call("/api/publish", "POST", body)
                                        notice = "Meta accepted the post. ID: " + result.optString("post_id", "returned")
                                        postText = ""; postLink = ""; refresh()
                                    } catch (e: Exception) { notice = e.message ?: "Publishing failed" }
                                    busy = false
                                }
                            }, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Icon(Icons.Default.Send, null); Spacer(Modifier.width(8.dp)); Text(if (busy) "Working…" else "Publish now") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    if (selectedPage.isBlank() || postText.isBlank() || scheduleAt.isBlank()) { notice = "Select a Page, enter post text, and set schedule time."; return@launch }
                                    busy = true
                                    try {
                                        val body = JSONObject().put("page_id", selectedPage).put("message", postText).put("link", postLink).put("scheduled_at", scheduleAt)
                                        val result = call("/api/jobs", "POST", body)
                                        notice = "Scheduled job created: " + result.optString("id")
                                        postText = ""; postLink = ""; scheduleAt = ""; refresh()
                                    } catch (e: Exception) { notice = e.message ?: "Scheduling failed" }
                                    busy = false
                                }
                            }, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Icon(Icons.Default.CalendarMonth, null); Spacer(Modifier.width(8.dp)); Text("Schedule post") }
                            OutlinedTextField(scheduleAt, { scheduleAt = it }, label = { Text("Schedule time (ISO 8601 with timezone)") }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("2026-11-01T10:30:00+03:00") })
                        } }
                    }
                    item { Text("Note: this app sends only the selected Page post to the backend. It does not automate reactions or attempt to evade platform enforcement.") }
                }
                2 -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) { Text("Publishing queue", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); OutlinedButton(onClick = { refresh() }) { Text("Refresh") } } }
                    if (jobs.isEmpty()) item { EmptyState("Queue is empty", "Scheduled jobs from your backend appear here.") }
                    items(jobs, key = { it.id }) { JobCard(it) }
                }
                else -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Backend connection", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                    item { Text("Deploy the included backend to an HTTPS host. The API key is stored in this app's private local preferences. Never put your Meta Page tokens in the mobile app.") }
                    item { Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(apiUrl, { apiUrl = it }, label = { Text("Backend HTTPS URL") }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("https://your-service.example.com") }, singleLine = true)
                        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("SocialDesk API key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Button(onClick = { scope.launch { ctx.ds.edit { it[URL_KEY] = apiUrl.trim().trimEnd('/'); it[KEY_KEY] = apiKey.trim() }; notice = "Connection settings saved." } }, modifier = Modifier.fillMaxWidth()) { Text("Save settings") }
                        Button(onClick = { refresh() }, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text("Test connection") }
                    } } }
                    item { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Meta setup checklist", fontWeight = FontWeight.Bold)
                        Text("1. Create a Meta developer app.\n2. Configure Facebook Login / Pages API.\n3. Grant the required Page permissions.\n4. Put Page IDs and Page access tokens in backend environment variables only.\n5. Test with a Page you administer. App review may be required for other users.")
                    } } }
                }
            }
        }
    }
}

@Composable private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) { Column(Modifier.padding(16.dp)) { Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Text(label) } }
}
@Composable private fun EmptyState(title: String, body: String) {
    Card { Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(body, style = MaterialTheme.typography.bodyMedium) } }
}
@Composable private fun JobCard(job: RemoteJob) {
    Card { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(job.pageName.ifBlank { "Page ${job.id}" }, fontWeight = FontWeight.SemiBold)
        Text(job.message, maxLines = 3)
        Text("${job.status} · ${job.scheduledAt}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
    } }
}
