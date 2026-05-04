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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.LocalImageLoader
import coil.decode.SvgDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import kotlin.random.Random

// --- Models ---

@Serializable
data class Team(
    val teamName: String,
    val points: Int,
    val goals: Int,
    val opponentGoals: Int? = 0,
    val goalDiff: Int? = 0,
    val teamIconUrl: String? = null
)

@Serializable
data class MatchTeam(
    val teamName: String,
    val teamIconUrl: String? = null
)

@Serializable
data class Match(
    val team1: MatchTeam,
    val team2: MatchTeam,
    val matchIsFinished: Boolean,
    val group: Group? = null,
    val matchDateTime: String? = null
)

@Serializable
data class Group(
    val groupName: String? = null,
    val groupID: Int? = null,
    val groupOrderID: Int? = null
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

@Serializable
data class OddsResponse(
    val home_team: String,
    val away_team: String,
    val bookmakers: List<Bookmaker>
)

@Serializable
data class Bookmaker(
    val markets: List<Market>
)

@Serializable
data class Market(
    val outcomes: List<Outcome>
)

@Serializable
data class Outcome(
    val name: String,
    val price: Float
)

// --- API ---

interface OpenLigaApi {
    @GET("getbltable/{league}/{season}")
    suspend fun getTable(@Path("league") league: String, @Path("season") season: String): List<Team>

    @GET("getmatchdata/{league}/{season}")
    suspend fun getMatches(@Path("league") league: String, @Path("season") season: String): List<Match>
}

interface OddsApi {
    @GET("v4/sports/{sport}/odds/")
    suspend fun getOdds(
        @Path("sport") sport: String,
        @Query("apiKey") apiKey: String,
        @Query("regions") regions: String = "eu",
        @Query("markets") markets: String = "h2h"
    ): Response<List<OddsResponse>>
}

object RetrofitClient {
    private val json = Json { ignoreUnknownKeys = true }
    val api: OpenLigaApi = Retrofit.Builder()
        .baseUrl("https://api.openligadb.de/")
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(OpenLigaApi::class.java)

    val oddsApi: OddsApi = Retrofit.Builder()
        .baseUrl("https://api.the-odds-api.com/")
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(OddsApi::class.java)
}

// --- Logic & ViewModel ---

data class TeamSimulationResult(
    val rank: Int,
    val name: String,
    val iconUrl: String?,
    val currentPoints: Int,
    val probMeister: Float,
    val probDirectPromotion: Float = 0f,
    val probReleUp: Float,
    val probReleDown: Float,
    val probSafe: Float,
    val probAbstieg: Float
)

enum class AppMode { BUNDESLIGA, TOURNAMENT }

class MainViewModel(private val repository: SettingsRepository) : ViewModel() {
    var appMode by mutableStateOf(AppMode.BUNDESLIGA)
    var simulationResults by mutableStateOf<List<TeamSimulationResult>>(emptyList())
    var isLoading by mutableStateOf(false)
    var selectedLeague by mutableStateOf("bl1")
    
    var season by mutableStateOf("2024")
    var monteCarloIterations by mutableIntStateOf(5000)
    var detailSimulations by mutableIntStateOf(10000)
    var probHomeWin by mutableFloatStateOf(0.45f)
    var probDraw by mutableFloatStateOf(0.25f)

    var oddsApiKey by mutableStateOf("")
    var useOdds by mutableStateOf(false)
    var cachedOdds by mutableStateOf<Map<String, MatchProbabilities>>(emptyMap())
    
    var oddsQuotaUsed by mutableStateOf<String?>(null)
    var oddsQuotaRemaining by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch {
            season = repository.season.first()
            monteCarloIterations = repository.monteCarloIterations.first()
            detailSimulations = repository.detailSimulations.first()
            probHomeWin = repository.probHomeWin.first()
            probDraw = repository.probDraw.first()
            oddsApiKey = repository.oddsApiKey.first()
            useOdds = repository.useOdds.first()
        }
    }

    var selectedTeamDetails by mutableStateOf<List<ConditionalResult>?>(null)
    var detailedTeamResult by mutableStateOf<TeamSimulationResult?>(null)

    fun updateSettings(newSeason: String, mcIter: Int, detSim: Int, pWin: Float, pDraw: Float, apiKey: String, useO: Boolean) {
        viewModelScope.launch {
            season = newSeason
            monteCarloIterations = mcIter
            detailSimulations = detSim
            probHomeWin = pWin
            probDraw = pDraw
            oddsApiKey = apiKey
            useOdds = useO
            
            repository.updateSeason(newSeason)
            repository.updateMonteCarloIterations(mcIter)
            repository.updateDetailSimulations(detSim)
            repository.updateProbabilities(pWin, pDraw)
            repository.updateOddsSettings(apiKey, useO)
        }
    }

    fun updateUseOdds(value: Boolean) {
        useOdds = value
        viewModelScope.launch {
            repository.updateOddsSettings(oddsApiKey, value)
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

    data class MatchProbabilities(val home: Float, val draw: Float)

    private suspend fun fetchOdds() {
        if (!useOdds || oddsApiKey.isBlank()) {
            cachedOdds = emptyMap()
            return
        }

        val sport = when (selectedLeague) {
            "bl1" -> "soccer_germany_bundesliga"
            "bl2" -> "soccer_germany_bundesliga_2"
            else -> null
        } ?: return

        try {
            val response = RetrofitClient.oddsApi.getOdds(sport, oddsApiKey)
            if (response.isSuccessful) {
                oddsQuotaUsed = response.headers()["x-requests-used"]
                oddsQuotaRemaining = response.headers()["x-requests-remaining"]
                
                val body = response.body() ?: emptyList()
                cachedOdds = body.associate { odds ->
                    val h2h = odds.bookmakers.firstOrNull()?.markets?.find { it.outcomes.size == 3 }
                    if (h2h != null) {
                        val pHome = 1f / (h2h.outcomes.find { it.name == odds.home_team }?.price ?: 2f)
                        val pAway = 1f / (h2h.outcomes.find { it.name == odds.away_team }?.price ?: 3f)
                        val pDraw = 1f / (h2h.outcomes.find { it.name == "Draw" }?.price ?: 3f)
                        val sum = pHome + pAway + pDraw
                        val key = "${odds.home_team} - ${odds.away_team}"
                        key to MatchProbabilities(pHome / sum, pDraw / sum)
                    } else {
                        "" to MatchProbabilities(0.45f, 0.25f)
                    }
                }.filterKeys { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getMatchProbabilities(match: Match): MatchProbabilities {
        if (useOdds) {
            // Improved matching logic for team names
            val t1 = match.team1.teamName.lowercase()
            val t2 = match.team2.teamName.lowercase()
            
            val key = cachedOdds.keys.find { oddsKey ->
                val ok = oddsKey.lowercase()
                (ok.contains(t1) || t1.contains(ok.split("-")[0].lowercase())) && 
                (ok.contains(t2) || t2.contains(ok.split("-")[1].lowercase()))
            }
            if (key != null) return cachedOdds[key]!!
        }
        return MatchProbabilities(probHomeWin, probDraw)
    }

    fun runSimulation() {
        viewModelScope.launch {
            isLoading = true
            try {
                if (useOdds) fetchOdds()
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
        val currentTeamStats = table.associate { it.teamName to (it.points to (it.goalDiff ?: 0)) }
        val isBl3 = selectedLeague == "bl3"
        val safeThreshold = if (isBl3) 16 else 15

        val iterations = detailSimulations
        val resultsByPoints = mutableMapOf<Int, MutableList<Int>>() 

        repeat(iterations) {
            val tempPoints = currentTeamStats.mapValues { it.value.first }.toMutableMap()
            val tempGD = currentTeamStats.mapValues { it.value.second }

            for (match in remaining) {
                val probs = getMatchProbabilities(match)
                val r = Random.nextFloat()
                val t1 = match.team1.teamName
                val t2 = match.team2.teamName
                
                if (!tempPoints.containsKey(t1) || !tempPoints.containsKey(t2)) continue

                if (r < probs.home) {
                    tempPoints[t1] = (tempPoints[t1] ?: 0) + 3
                } else if (r < (probs.home + probs.draw)) {
                    tempPoints[t1] = (tempPoints[t1] ?: 0) + 1
                    tempPoints[t2] = (tempPoints[t2] ?: 0) + 1
                } else {
                    tempPoints[t2] = (tempPoints[t2] ?: 0) + 3
                }
            }

            val finalPts = tempPoints[teamName] ?: 0
            val sorted = tempPoints.toList().sortedWith(
                compareByDescending<Pair<String, Int>> { it.second }
                    .thenByDescending { tempGD[it.first] ?: 0 }
            )
            val rank = sorted.indexOfFirst { it.first == teamName }
            resultsByPoints.getOrPut(finalPts) { mutableListOf() }.add(rank)
        }

        return resultsByPoints.map { (pts, ranks) ->
            val count = ranks.size.toFloat()
            ConditionalResult(
                points = pts,
                probabilityReached = (count / iterations) * 100,
                probabilityMeister = (ranks.count { it == 0 } / count) * 100,
                probabilityDirectPromotion = (ranks.count { it <= 1 } / count) * 100,
                probabilityReleUp = (ranks.count { it == 2 } / count) * 100,
                probabilityReleDown = (ranks.count { it == 15 && !isBl3 } / count) * 100,
                probabilitySafe = (ranks.count { it < safeThreshold } / count) * 100,
                monteCarloFrequency = (count / iterations) * 100
            )
        }.sortedBy { it.points }
    }

    private fun performMonteCarlo(table: List<Team>, remaining: List<Match>): List<TeamSimulationResult> {
        val teamStats = table.associate { it.teamName to mutableMapOf("meister" to 0, "directPromotion" to 0, "releUp" to 0, "releDown" to 0, "safe" to 0) }
        val isBl3 = selectedLeague == "bl3"
        val safeThreshold = if (isBl3) 16 else 15
        val teamGDs = table.associate { it.teamName to (it.goalDiff ?: 0) }

        repeat(monteCarloIterations) {
            val tempPoints = table.associate { it.teamName to it.points }.toMutableMap()
            
            for (match in remaining) {
                val probs = getMatchProbabilities(match)
                val r = Random.nextFloat()
                
                val t1Name = match.team1.teamName
                val t2Name = match.team2.teamName
                
                if (!tempPoints.containsKey(t1Name) || !tempPoints.containsKey(t2Name)) continue

                if (r < probs.home) {
                    tempPoints[t1Name] = (tempPoints[t1Name] ?: 0) + 3
                } else if (r < (probs.home + probs.draw)) {
                    tempPoints[t1Name] = (tempPoints[t1Name] ?: 0) + 1
                    tempPoints[t2Name] = (tempPoints[t2Name] ?: 0) + 1
                } else {
                    tempPoints[t2Name] = (tempPoints[t2Name] ?: 0) + 3
                }
            }

            // Tie-break by current Goal Difference
            val sorted = tempPoints.toList().sortedWith(
                compareByDescending<Pair<String, Int>> { it.second }
                    .thenByDescending { teamGDs[it.first] ?: 0 }
            )
            
            sorted.forEachIndexed { index, (name, _) ->
                val stats = teamStats[name] ?: return@forEachIndexed
                if (index == 0) stats["meister"] = stats["meister"]!! + 1
                if (index <= 1) stats["directPromotion"] = stats["directPromotion"]!! + 1
                if (index == 2) stats["releUp"] = stats["releUp"]!! + 1
                if (index == 15 && !isBl3) stats["releDown"] = stats["releDown"]!! + 1
                if (index < safeThreshold) stats["safe"] = stats["safe"]!! + 1
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
                iconUrl = team.teamIconUrl?.replace("http://", "https://"),
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
    var showOddsDebug by remember { mutableStateOf(false) }
    val tournamentViewModel: TournamentViewModel = viewModel(factory = TournamentViewModelFactory(SettingsRepository(LocalContext.current)))

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
                        Text(if (viewModel.appMode == AppMode.BUNDESLIGA) "Bundesliga Estimator" else "WC 2026 Sim", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { 
                        viewModel.appMode = if (viewModel.appMode == AppMode.BUNDESLIGA) AppMode.TOURNAMENT else AppMode.BUNDESLIGA 
                    }) {
                        Icon(
                            painter = if (viewModel.appMode == AppMode.BUNDESLIGA) painterResource(id = android.R.drawable.ic_menu_today) else painterResource(id = android.R.drawable.ic_menu_sort_by_size),
                            contentDescription = "Switch Mode"
                        )
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    if (viewModel.appMode == AppMode.BUNDESLIGA) {
                        Button(onClick = { viewModel.runSimulation() }, enabled = !viewModel.isLoading) {
                            if (viewModel.isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            else Text("Run")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (viewModel.appMode == AppMode.BUNDESLIGA) {
                LeagueSelector(viewModel)
                if (viewModel.oddsApiKey.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = viewModel.useOdds,
                            onCheckedChange = { viewModel.updateUseOdds(it) }
                        )
                        Text("Use Odds API for next games", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (viewModel.useOdds) {
                            IconButton(onClick = { showOddsDebug = true }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Info, contentDescription = "Show Odds", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                TableHeader(viewModel.selectedLeague)
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(viewModel.simulationResults) { result ->
                        TeamRow(result, viewModel.selectedLeague) { viewModel.showDetails(result) }
                    }
                }
            } else {
                TournamentView(tournamentViewModel)
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

        if (showOddsDebug) {
            OddsDebugDialog(viewModel, onDismiss = { showOddsDebug = false })
        }
    }
}

@Composable
fun OddsDebugDialog(viewModel: MainViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gelesene Quoten (Next Games)") },
        text = {
            Column {
                if (viewModel.oddsQuotaUsed != null || viewModel.oddsQuotaRemaining != null) {
                    Text(
                        text = "API Quota: ${viewModel.oddsQuotaUsed ?: "?"} genutzt, ${viewModel.oddsQuotaRemaining ?: "?"} verbleibend",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                
                if (viewModel.cachedOdds.isEmpty()) {
                    Text("Keine Quoten geladen. Drücke 'Run' um sie zu aktualisieren.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                        items(viewModel.cachedOdds.toList()) { (match, probs) ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(match, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Home: ${(probs.home * 100).toInt()}%", fontSize = 11.sp)
                                        Text("Draw: ${(probs.draw * 100).toInt()}%", fontSize = 11.sp)
                                        Text("Away: ${((1f - probs.home - probs.draw) * 100).toInt()}%", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}

@Composable
fun SettingsDialog(viewModel: MainViewModel, onDismiss: () -> Unit) {
    var seasonText by remember { mutableStateOf(viewModel.season) }
    var mcIterText by remember { mutableStateOf(viewModel.monteCarloIterations.toString()) }
    var detSimText by remember { mutableStateOf(viewModel.detailSimulations.toString()) }
    var probWinText by remember { mutableStateOf(viewModel.probHomeWin.toString()) }
    var probDrawText by remember { mutableStateOf(viewModel.probDraw.toString()) }
    var oddsApiKeyText by remember { mutableStateOf(viewModel.oddsApiKey) }
    var useOddsValue by remember { mutableStateOf(viewModel.useOdds) }
    
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
                    value = oddsApiKeyText,
                    onValueChange = { oddsApiKeyText = it },
                    label = { Text("Odds API Key") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useOddsValue, onCheckedChange = { useOddsValue = it })
                    Text("Use Odds by default")
                }
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
                            probDrawText.toFloatOrNull() ?: 0.25f,
                            oddsApiKeyText,
                            useOddsValue
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
        title = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = teamResult.iconUrl,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp).padding(end = 8.dp),
                    error = painterResource(id = android.R.drawable.ic_menu_help)
                )
                Text(teamResult.name, fontWeight = FontWeight.Bold)
            }
        },
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
                                Text("${String.format(java.util.Locale.US, "%.1f", row.monteCarloFrequency)}%", fontSize = 9.sp)
                            }
                            
                            // Selection of the most relevant metric to display (Meister, Aufstieg, or Safe)
                            val threshold = (teamResult.currentPoints + (details.last().points - teamResult.currentPoints) / 2)
                            
                            val mainLabel: String
                            val mainVal: Float
                            
                            // 1. If Meister is guaranteed or very likely (>80%), show M
                            // 2. Otherwise, if Promotion is guaranteed or we are in "success territory" (>threshold), show A
                            // 3. Otherwise show S (Safe)
                            
                            if (row.probabilityMeister > 80f || (isBl1 && row.probabilityMeister > 0.5f && (row.points > threshold || row.probabilitySafe > 99f))) {
                                mainLabel = "M:"
                                mainVal = row.probabilityMeister
                            } else if (!isBl1 && (row.probabilityDirectPromotion > 99.9f || (row.probabilityDirectPromotion > 0.5f && row.points > threshold))) {
                                mainLabel = "A:"
                                mainVal = row.probabilityDirectPromotion
                            } else {
                                mainLabel = "S:"
                                mainVal = row.probabilitySafe
                            }

                            val asterisk = if (!isBl1 && mainLabel == "A:" && row.probabilityMeister > 50f) "*" else ""
                            
                            val mainColor = when (mainLabel) {
                                "M:" -> Color(0xFFFFD700) // Gold for Meister
                                "A:" -> Color(0xFF2196F3) // Blue for Aufstieg
                                else -> if (mainVal > 80f) Color(0xFF4CAF50) else if (mainVal > 30f) Color(0xFFFFC107) else Color(0xFFF44336)
                            }
                            
                            Column(modifier = Modifier.weight(1.3f).padding(end = 4.dp)) {
                                LinearProgressIndicator(
                                    progress = { mainVal / 100f },
                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                    color = mainColor,
                                    trackColor = mainColor.copy(alpha = 0.1f)
                                )
                                Text("$mainLabel ${String.format(java.util.Locale.US, "%.1f", mainVal)}%$asterisk", fontSize = 9.sp, fontWeight = FontWeight.Medium)
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
                                Text("$releLabel ${String.format(java.util.Locale.US, "%.1f", releVal)}%", fontSize = 9.sp)
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), 
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf("bl1" to "1. BL", "bl2" to "2. BL", "bl3" to "3. BL").forEach { (id, label) ->
            FilterChip(
                selected = viewModel.selectedLeague == id,
                onClick = { viewModel.selectedLeague = id; viewModel.runSimulation() },
                label = { Text(label, fontWeight = if (viewModel.selectedLeague == id) FontWeight.Bold else FontWeight.Normal) },
                shape = MaterialTheme.shapes.medium
            )
        }
    }
}

@Composable
fun TableHeader(league: String) {
    val isBl1 = league == "bl1"
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp), 
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("#", modifier = Modifier.width(28.dp), fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
            Spacer(modifier = Modifier.width(28.dp)) // Space for icon
            Text("Team", modifier = Modifier.weight(1f), fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
            Text("Pts", modifier = Modifier.width(32.dp), textAlign = TextAlign.End, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isBl1) "Meister" else "Aufst.", modifier = Modifier.width(60.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
            Text("Safe", modifier = Modifier.width(60.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
            Text("Abst.", modifier = Modifier.width(60.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
        }
    }
}

@Composable
fun TeamRow(result: TeamSimulationResult, league: String, onClick: () -> Unit) {
    val isBl1 = league == "bl1"
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        onClick = onClick,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp), 
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${result.rank}.", 
                modifier = Modifier.width(28.dp), 
                fontWeight = FontWeight.Bold, 
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
            
            AsyncImage(
                model = result.iconUrl,
                contentDescription = null,
                modifier = Modifier.size(28.dp).padding(end = 8.dp),
                contentScale = ContentScale.Fit,
                error = painterResource(id = android.R.drawable.ic_menu_help),
                placeholder = painterResource(id = android.R.drawable.ic_menu_gallery)
            )
            
            Text(
                result.name, 
                modifier = Modifier.weight(1f), 
                maxLines = 1, 
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
            
            Text(
                result.currentPoints.toString(), 
                modifier = Modifier.width(32.dp),
                textAlign = TextAlign.End,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1
            )
            
            Spacer(modifier = Modifier.width(8.dp))

            val topProb = if (isBl1) result.probMeister else result.probDirectPromotion
            val topColor = if (isBl1) Color(0xFFFFD700) else Color(0xFF2196F3)
            
            ProbabilityBar(topProb, topColor, Modifier.width(60.dp))
            ProbabilityBar(result.probSafe, Color(0xFF4CAF50), Modifier.width(60.dp))
            ProbabilityBar(result.probAbstieg, Color(0xFFF44336), Modifier.width(60.dp))
        }
    }
}

@Composable
fun ProbabilityBar(prob: Float, color: Color, modifier: Modifier) {
    Column(modifier = modifier.padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.extraSmall),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(prob / 100f)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .background(color, shape = MaterialTheme.shapes.extraSmall)
            )
            Text(
                "${prob.toInt()}%", 
                fontSize = 10.sp, 
                fontWeight = FontWeight.Bold,
                color = if (prob > 50) Color.White else Color.Black,
                maxLines = 1
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = SettingsRepository(applicationContext)

        val imageLoader = ImageLoader.Builder(this)
            .components {
                add(SvgDecoder.Factory())
            }
            .okHttpClient {
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:100.0) Gecko/100.0 Firefox/100.0")
                            .build()
                        chain.proceed(request)
                    }
                    .build()
            }
            .crossfade(true)
            .build()

        // Coil als Singleton konfigurieren, damit alle AsyncImage-Aufrufe diesen Loader nutzen
        coil.Coil.setImageLoader(imageLoader)

        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel(factory = MainViewModelFactory(repository))
            CompositionLocalProvider(LocalImageLoader provides imageLoader) {
                MaterialTheme {
                    BundesligaApp(viewModel)
                }
            }
        }
    }
}
