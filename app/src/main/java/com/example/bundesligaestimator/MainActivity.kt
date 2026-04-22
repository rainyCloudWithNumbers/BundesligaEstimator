package com.example.bundesligaestimator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import kotlin.random.Random

// --- Models ---

@Serializable
data class Team(
    val teamName: String,
    val points: Int,
    val goals: Int
)

@Serializable
data class MatchTeam(
    val teamName: String
)

@Serializable
data class Match(
    val team1: MatchTeam,
    val team2: MatchTeam,
    val matchIsFinished: Boolean
)

data class ConditionalResult(
    val points: Int,
    val probabilityReached: Float,
    val probabilityMeister: Float,
    val probabilityDirectPromotion: Float = 0f,
    val probabilityReleUp: Float,
    val probabilityReleDown: Float,
    val probabilitySafe: Float,
    val monteCarloFrequency: Float = 0f
)

// --- API ---

interface OpenLigaApi {
    @GET("getbltable/{league}/{season}")
    suspend fun getTable(@Path("league") league: String, @Path("season") season: String): List<Team>

    @GET("getmatchdata/{league}/{season}")
    suspend fun getMatches(@Path("league") league: String, @Path("season") season: String): List<Match>
}

object RetrofitClient {
    private val json = Json { ignoreUnknownKeys = true }
    val api: OpenLigaApi = Retrofit.Builder()
        .baseUrl("https://api.openligadb.de/")
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(OpenLigaApi::class.java)
}

// --- Logic & ViewModel ---

data class TeamSimulationResult(
    val rank: Int,
    val name: String,
    val currentPoints: Int,
    val probMeister: Float,
    val probDirectPromotion: Float = 0f,
    val probReleUp: Float,
    val probReleDown: Float,
    val probSafe: Float,
    val probAbstieg: Float
)

class MainViewModel(private val repository: SettingsRepository) : ViewModel() {
    var simulationResults by mutableStateOf<List<TeamSimulationResult>>(emptyList())
    var isLoading by mutableStateOf(false)
    var selectedLeague by mutableStateOf("bl1")
    
    var season by mutableStateOf("2024")
    var monteCarloIterations by mutableIntStateOf(5000)
    var detailSimulations by mutableIntStateOf(10000)
    var probHomeWin by mutableFloatStateOf(0.45f)
    var probDraw by mutableFloatStateOf(0.25f)

    init {
        viewModelScope.launch {
            season = repository.season.first()
            monteCarloIterations = repository.monteCarloIterations.first()
            detailSimulations = repository.detailSimulations.first()
            probHomeWin = repository.probHomeWin.first()
            probDraw = repository.probDraw.first()
        }
    }

    var selectedTeamDetails by mutableStateOf<List<ConditionalResult>?>(null)
    var detailedTeamResult by mutableStateOf<TeamSimulationResult?>(null)

    fun updateSettings(newSeason: String, mcIter: Int, detSim: Int, pWin: Float, pDraw: Float) {
        viewModelScope.launch {
            season = newSeason
            monteCarloIterations = mcIter
            detailSimulations = detSim
            probHomeWin = pWin
            probDraw = pDraw
            
            repository.updateSeason(newSeason)
            repository.updateMonteCarloIterations(mcIter)
            repository.updateDetailSimulations(detSim)
            repository.updateProbabilities(pWin, pDraw)
        }
    }

    suspend fun checkSeasonExists(league: String, season: String): Boolean {
        return try {
            val teams = RetrofitClient.api.getTable(league, season)
            teams.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    fun runSimulation() {
        viewModelScope.launch {
            isLoading = true
            try {
                val table = RetrofitClient.api.getTable(selectedLeague, season)
                val allMatches = RetrofitClient.api.getMatches(selectedLeague, season)
                val remainingMatches = allMatches.filter { !it.matchIsFinished }

                val results = withContext(Dispatchers.Default) {
                    performMonteCarlo(table, remainingMatches)
                }
                simulationResults = results
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    fun showDetails(teamResult: TeamSimulationResult) {
        viewModelScope.launch {
            detailedTeamResult = teamResult
            isLoading = true
            try {
                val table = RetrofitClient.api.getTable(selectedLeague, season)
                val allMatches = RetrofitClient.api.getMatches(selectedLeague, season)
                
                val results = withContext(Dispatchers.Default) {
                    calculateConditionalProbabilities(teamResult.name, table, allMatches)
                }
                selectedTeamDetails = results
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    private fun calculateConditionalProbabilities(
        teamName: String,
        table: List<Team>,
        allMatches: List<Match>
    ): List<ConditionalResult> {
        val remaining = allMatches.filter { !it.matchIsFinished }
        val teamMatches = remaining.filter { it.team1.teamName == teamName || it.team2.teamName == teamName }
        val otherMatches = remaining.filter { it.team1.teamName != teamName && it.team2.teamName != teamName }
        
        val currentPts = table.find { it.teamName == teamName }?.points ?: 0
        
        val pointCounts = mutableMapOf<Int, Int>()
        repeat(detailSimulations) {
            var pts = currentPts
            for (match in teamMatches) {
                val r = Random.nextFloat()
                if (r < probHomeWin) pts += 3
                else if (r < (probHomeWin + probDraw)) pts += 1
            }
            pointCounts[pts] = pointCounts.getOrDefault(pts, 0) + 1
        }

        val results = mutableListOf<ConditionalResult>()
        val minPossible = currentPts
        val maxPossible = currentPts + teamMatches.size * 3
        
        val isBl3 = selectedLeague == "bl3"

        for (finalPoints in minPossible..maxPossible) {
            val frequency = pointCounts.getOrDefault(finalPoints, 0).toFloat() / detailSimulations * 100
            
            if (frequency > 0 || (teamMatches.size <= 3)) { 
                var meisterCount = 0
                var directPromotionCount = 0
                var releUpCount = 0
                var releDownCount = 0
                var safeCount = 0
                val innerSimulations = 1000 
                
                repeat(innerSimulations) {
                    val tempPoints = table.associate { it.teamName to it.points }.toMutableMap()
                    tempPoints[teamName] = finalPoints
                    
                    for (match in otherMatches) {
                        val r = Random.nextFloat()
                        if (r < probHomeWin) {
                            tempPoints[match.team1.teamName] = (tempPoints[match.team1.teamName] ?: 0) + 3
                        } else if (r < (probHomeWin + probDraw)) {
                            tempPoints[match.team1.teamName] = (tempPoints[match.team1.teamName] ?: 0) + 1
                            tempPoints[match.team2.teamName] = (tempPoints[match.team2.teamName] ?: 0) + 1
                        } else {
                            tempPoints[match.team2.teamName] = (tempPoints[match.team2.teamName] ?: 0) + 3
                        }
                    }
                    
                    val sorted = tempPoints.toList().sortedByDescending { it.second }
                    val rank = sorted.indexOfFirst { it.first == teamName }
                    
                    if (rank == 0) meisterCount++
                    if (rank <= 1) directPromotionCount++
                    if (rank == 2) releUpCount++ 
                    if (rank == 15 && !isBl3) releDownCount++ 
                    if (rank < 15) safeCount++
                }

                results.add(ConditionalResult(
                    points = finalPoints,
                    probabilityReached = frequency, 
                    probabilityMeister = (meisterCount.toFloat() / innerSimulations) * 100,
                    probabilityDirectPromotion = (directPromotionCount.toFloat() / innerSimulations) * 100,
                    probabilityReleUp = (releUpCount.toFloat() / innerSimulations) * 100,
                    probabilityReleDown = (releDownCount.toFloat() / innerSimulations) * 100,
                    probabilitySafe = (safeCount.toFloat() / innerSimulations) * 100,
                    monteCarloFrequency = frequency
                ))
            }
        }
        return results.sortedBy { it.points }
    }

    private fun performMonteCarlo(table: List<Team>, remaining: List<Match>): List<TeamSimulationResult> {
        val teamStats = table.associate { it.teamName to mutableMapOf("meister" to 0, "directPromotion" to 0, "releUp" to 0, "releDown" to 0, "safe" to 0) }
        val isBl3 = selectedLeague == "bl3"
        val isBl1 = selectedLeague == "bl1"

        repeat(monteCarloIterations) {
            val tempPoints = table.associate { it.teamName to it.points }.toMutableMap()
            
            for (match in remaining) {
                val r = Random.nextFloat()
                if (r < probHomeWin) {
                    tempPoints[match.team1.teamName] = (tempPoints[match.team1.teamName] ?: 0) + 3
                } else if (r < (probHomeWin + probDraw)) {
                    tempPoints[match.team1.teamName] = (tempPoints[match.team1.teamName] ?: 0) + 1
                    tempPoints[match.team2.teamName] = (tempPoints[match.team2.teamName] ?: 0) + 1
                } else {
                    tempPoints[match.team2.teamName] = (tempPoints[match.team2.teamName] ?: 0) + 3
                }
            }

            val sorted = tempPoints.toList().sortedByDescending { it.second }
            sorted.forEachIndexed { index, (name, _) ->
                val stats = teamStats[name] ?: return@forEachIndexed
                if (index == 0) stats["meister"] = stats["meister"]!! + 1
                if (index <= 1) stats["directPromotion"] = stats["directPromotion"]!! + 1
                if (index == 2) stats["releUp"] = stats["releUp"]!! + 1
                if (index == 15 && !isBl3) stats["releDown"] = stats["releDown"]!! + 1
                if (index < 15) stats["safe"] = stats["safe"]!! + 1
            }
        }

        return table.map { team ->
            val stats = teamStats[team.teamName]!!
            val pMeister = (stats["meister"]!!.toFloat() / monteCarloIterations) * 100
            val pDirectPromotion = (stats["directPromotion"]!!.toFloat() / monteCarloIterations) * 100
            val pReleUp = (stats["releUp"]!!.toFloat() / monteCarloIterations) * 100
            val pReleDown = (stats["releDown"]!!.toFloat() / monteCarloIterations) * 100
            val pSafe = (stats["safe"]!!.toFloat() / monteCarloIterations) * 100
            val pAbstieg = 100f - pSafe - pReleDown

            TeamSimulationResult(
                rank = 0,
                name = team.teamName,
                currentPoints = team.points,
                probMeister = pMeister,
                probDirectPromotion = pDirectPromotion,
                probReleUp = pReleUp,
                probReleDown = pReleDown,
                probSafe = pSafe,
                probAbstieg = pAbstieg
            )
        }.sortedByDescending { it.probMeister * 1000 + it.probSafe }
         .mapIndexed { index, res -> res.copy(rank = index + 1) }
    }
}

class MainViewModelFactory(private val repository: SettingsRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// --- UI Components ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundesligaApp(viewModel: MainViewModel) {
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.largericonblstat),
                            contentDescription = null,
                            modifier = Modifier.size(66.dp).padding(end = 8.dp)
                        )
                        Text("Bundesliga Estimator", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    Button(onClick = { viewModel.runSimulation() }, enabled = !viewModel.isLoading) {
                        if (viewModel.isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text("Run")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            LeagueSelector(viewModel)
            TableHeader(viewModel.selectedLeague)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(viewModel.simulationResults) { result ->
                    TeamRow(result, viewModel.selectedLeague) { viewModel.showDetails(result) }
                }
            }
        }

        if (viewModel.selectedTeamDetails != null && viewModel.detailedTeamResult != null) {
            TeamDetailDialog(
                teamResult = viewModel.detailedTeamResult!!,
                details = viewModel.selectedTeamDetails!!,
                isBl1 = viewModel.selectedLeague == "bl1",
                onDismiss = { viewModel.selectedTeamDetails = null }
            )
        }

        if (showSettings) {
            SettingsDialog(viewModel, onDismiss = { showSettings = false })
        }
    }
}

@Composable
fun SettingsDialog(viewModel: MainViewModel, onDismiss: () -> Unit) {
    var seasonText by remember { mutableStateOf(viewModel.season) }
    var mcIterText by remember { mutableStateOf(viewModel.monteCarloIterations.toString()) }
    var detSimText by remember { mutableStateOf(viewModel.detailSimulations.toString()) }
    var probWinText by remember { mutableStateOf(viewModel.probHomeWin.toString()) }
    var probDrawText by remember { mutableStateOf(viewModel.probDraw.toString()) }
    
    var seasonError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = seasonText,
                    onValueChange = { seasonText = it; seasonError = null },
                    label = { Text("Season (e.g., 2024)") },
                    isError = seasonError != null,
                    supportingText = { seasonError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mcIterText,
                    onValueChange = { mcIterText = it },
                    label = { Text("Main MC Iterations") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = detSimText,
                    onValueChange = { detSimText = it },
                    label = { Text("Detail MC Iterations") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = probWinText,
                        onValueChange = { probWinText = it },
                        label = { Text("P(Win)") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = probDrawText,
                        onValueChange = { probDrawText = it },
                        label = { Text("P(Draw)") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    val exists = viewModel.checkSeasonExists(viewModel.selectedLeague, seasonText)
                    if (exists) {
                        viewModel.updateSettings(
                            seasonText,
                            mcIterText.toIntOrNull() ?: 5000,
                            detSimText.toIntOrNull() ?: 10000,
                            probWinText.toFloatOrNull() ?: 0.45f,
                            probDrawText.toFloatOrNull() ?: 0.25f
                        )
                        onDismiss()
                    } else {
                        seasonError = "Season not found for this league."
                    }
                }
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun TeamDetailDialog(teamResult: TeamSimulationResult, details: List<ConditionalResult>, isBl1: Boolean, onDismiss: () -> Unit) {
    val maxFrequency = details.maxOfOrNull { it.monteCarloFrequency } ?: 100f
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Analyse: ${teamResult.name}", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Wahrscheinlichkeiten nach Endpunktestand:", fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                Row(modifier = Modifier.fillMaxWidth().background(Color.LightGray).padding(4.dp)) {
                    Text("Pkt", modifier = Modifier.weight(0.6f), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    Text("Distr.", modifier = Modifier.weight(1.2f), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    Text(if (isBl1) "Mst/Sich" else "Auf/Sich", modifier = Modifier.weight(1.3f), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    Text("Rel +/-", modifier = Modifier.weight(1.1f), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                }
                LazyColumn(modifier = Modifier.heightIn(max = 450.dp)) {
                    items(details) { row ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${row.points}", modifier = Modifier.weight(0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            
                            // Distribution Bar (Relative to MAX frequency)
                            Column(modifier = Modifier.weight(1.2f).padding(end = 4.dp)) {
                                LinearProgressIndicator(
                                    progress = { if (maxFrequency > 0) row.monteCarloFrequency / maxFrequency else 0f },
                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                    color = Color(0xFF2196F3),
                                    trackColor = Color(0xFF2196F3).copy(alpha = 0.1f)
                                )
                                Text("${String.format("%.1f", row.monteCarloFrequency)}%", fontSize = 9.sp)
                            }
                            
                            // Main Metric logic
                            val threshold = (teamResult.currentPoints + (details.last().points - teamResult.currentPoints) / 2)
                            val useHighGoal = if (isBl1) {
                                row.probabilityMeister > 0.5f && row.points > threshold
                            } else {
                                row.probabilityDirectPromotion > 0.5f && row.points > threshold
                            }

                            val mainVal = if (useHighGoal) (if (isBl1) row.probabilityMeister else row.probabilityDirectPromotion) else row.probabilitySafe
                            val mainLabel = if (useHighGoal) (if (isBl1) "M:" else "A:") else "S:"
                            val asterisk = if (!isBl1 && useHighGoal && row.probabilityMeister > 50f) "*" else ""
                            
                            val mainColor = if (useHighGoal) (if (isBl1) Color(0xFFFFD700) else Color(0xFF2196F3)) 
                                            else (if (mainVal > 80f) Color(0xFF4CAF50) else if (mainVal > 30f) Color(0xFFFFC107) else Color(0xFFF44336))
                            
                            Column(modifier = Modifier.weight(1.3f).padding(end = 4.dp)) {
                                LinearProgressIndicator(
                                    progress = { mainVal / 100f },
                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                    color = mainColor,
                                    trackColor = mainColor.copy(alpha = 0.1f)
                                )
                                Text("$mainLabel ${String.format("%.1f", mainVal)}%$asterisk", fontSize = 9.sp, fontWeight = FontWeight.Medium)
                            }

                            // Relegation Decision: Show either Upward or Downward Relegation per row
                            val useReleUp = row.probabilityReleUp >= row.probabilityReleDown
                            val releVal = if (useReleUp) row.probabilityReleUp else row.probabilityReleDown
                            val releLabel = if (useReleUp) (if (isBl1) "3.P" else "Rel") else "Ab"
                            val releColor = if (useReleUp) Color(0xFF9C27B0) else Color(0xFFFF9800)

                            Column(modifier = Modifier.weight(1.1f)) {
                                LinearProgressIndicator(
                                    progress = { releVal / 100f },
                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                    color = releColor,
                                    trackColor = releColor.copy(alpha = 0.1f)
                                )
                                Text("$releLabel ${String.format("%.1f", releVal)}%", fontSize = 9.sp)
                            }
                        }
                    }
                }
                Text(
                    if (isBl1) "Distr: Rel. Häufigkeit. M: Meister, S: Sicher, 3.P: Rele Auf, Ab: Rele Ab."
                    else "Distr: Rel. Häufigkeit. A: Aufstieg (*=Mst >50%), S: Sicher, Rel: Rele Auf, Ab: Rele Ab.",
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 8.dp),
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Schließen") }
        }
    )
}

@Composable
fun ProgressBarWithText(value: Float, color: Color, modifier: Modifier) {
    Column(modifier = modifier.padding(horizontal = 2.dp)) {
        LinearProgressIndicator(
            progress = { value / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = color,
            trackColor = color.copy(alpha = 0.1f)
        )
        Text("${String.format("%.1f", value)}%", fontSize = 8.sp)
    }
}

@Composable
fun LeagueSelector(viewModel: MainViewModel) {
    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf("bl1" to "1. BL", "bl2" to "2. BL", "bl3" to "3. BL").forEach { (id, label) ->
            FilterChip(
                selected = viewModel.selectedLeague == id,
                onClick = { viewModel.selectedLeague = id; viewModel.runSimulation() },
                label = { Text(label) }
            )
        }
    }
}

@Composable
fun TableHeader(league: String) {
    val isBl1 = league == "bl1"
    Row(modifier = Modifier.fillMaxWidth().background(Color.LightGray).padding(8.dp)) {
        Text("#", modifier = Modifier.weight(0.4f), fontWeight = FontWeight.Bold)
        Text("Team", modifier = Modifier.weight(1.6f), fontWeight = FontWeight.Bold)
        Text("Pts", modifier = Modifier.weight(0.5f), fontWeight = FontWeight.Bold)
        Text(if (isBl1) "Meister" else "Aufst.", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Text("Safe", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Text("Abst.", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
    }
}

@Composable
fun TeamRow(result: TeamSimulationResult, league: String, onClick: () -> Unit) {
    val isBl1 = league == "bl1"
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        onClick = onClick
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${result.rank}.", modifier = Modifier.weight(0.4f), fontWeight = FontWeight.Bold)
            Text(result.name, modifier = Modifier.weight(1.6f), maxLines = 1)
            Text(result.currentPoints.toString(), modifier = Modifier.weight(0.5f))
            
            val topProb = if (isBl1) result.probMeister else result.probDirectPromotion
            val topColor = if (isBl1) Color(0xFFFFD700) else Color(0xFF2196F3)
            
            ProbabilityBar(topProb, topColor, Modifier.weight(1f))
            ProbabilityBar(result.probSafe, Color(0xFF4CAF50), Modifier.weight(1f))
            ProbabilityBar(result.probAbstieg, Color(0xFFF44336), Modifier.weight(1f))
        }
    }
}

@Composable
fun ProbabilityBar(prob: Float, color: Color, modifier: Modifier) {
    Column(modifier = modifier.padding(horizontal = 2.dp)) {
        LinearProgressIndicator(
            progress = { prob / 100f },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = color,
            trackColor = color.copy(alpha = 0.2f)
        )
        Text("${prob.toInt()}%", fontSize = 10.sp)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = SettingsRepository(applicationContext)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel(factory = MainViewModelFactory(repository))
            MaterialTheme {
                BundesligaApp(viewModel)
            }
        }
    }
}
