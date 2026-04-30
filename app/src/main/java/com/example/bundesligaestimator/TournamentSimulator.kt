package com.example.bundesligaestimator

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import kotlin.random.Random

// UI Internal Models
@Serializable
data class TournamentTeam(
    val id: String = "",
    val name: String = "",
    val logo: String = "",
    val group: String = "",
    var points: Int = 0,
    var goalsFor: Int = 0,
    var goalsAgainst: Int = 0,
    var rank: Int = 0,
    val isMock: Boolean = false
) {
    val goalsDiff: Int get() = goalsFor - goalsAgainst
}

@Serializable
data class SimulatedMatch(
    val team1: TournamentTeam, 
    val team2: TournamentTeam, 
    val winner: TournamentTeam,
    val score1: Int? = null,
    val score2: Int? = null,
    val isFinished: Boolean = false,
    val isThirdPlaceMatch: Boolean = false,
    val prob1: Float? = null,
    val prob2: Float? = null,
    val probDraw: Float? = null,
    val isOddsApi: Boolean = false
)

data class TournamentRound(val name: String, val matches: List<SimulatedMatch>)

class TournamentViewModel(private val repository: SettingsRepository) : ViewModel() {
    var standings by mutableStateOf<List<List<TournamentTeam>>>(emptyList())
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var debugInfo by mutableStateOf<String?>(null)
    
    var groupMatches by mutableStateOf<Map<String, List<SimulatedMatch>>>(emptyMap())
    var simulationRounds by mutableStateOf<List<TournamentRound>>(emptyList())

    // Odds API integration
    var useOdds by mutableStateOf(false)
    var oddsApiKey by mutableStateOf("")
    data class MatchProbabilities(val home: Float, val draw: Float, val isOddsApi: Boolean = false)
    var cachedOdds by mutableStateOf<Map<String, MatchProbabilities>>(emptyMap())
    var oddsQuotaUsed by mutableStateOf<String?>(null)
    var oddsQuotaRemaining by mutableStateOf<String?>(null)
    
    var probHomeWin by mutableFloatStateOf(0.45f)
    var probDraw by mutableFloatStateOf(0.25f)

    private val json = Json { ignoreUnknownKeys = true }

    private val teamNameTranslation = mapOf(
        "Deutschland" to "Germany",
        "Frankreich" to "France",
        "Spanien" to "Spain",
        "Italien" to "Italy",
        "Niederlande" to "Netherlands",
        "Brasilien" to "Brazil",
        "Argentinien" to "Argentina",
        "Mexiko" to "Mexico",
        "USA" to "USA",
        "Kanada" to "Canada",
        "England" to "England",
        "Portugal" to "Portugal",
        "Belgien" to "Belgium",
        "Kroatien" to "Croatia",
        "Marokko" to "Morocco",
        "Japan" to "Japan",
        "Südkorea" to "South Korea",
        "Schweiz" to "Switzerland",
        "Dänemark" to "Denmark",
        "Polen" to "Poland",
        "Senegal" to "Senegal",
        "Uruguay" to "Uruguay",
        "Kolumbien" to "Colombia",
        "Schweden" to "Sweden",
        "Türkei" to "Turkey",
        "Österreich" to "Austria",
        "Schottland" to "Scotland",
        "Wales" to "Wales",
        "Ukraine" to "Ukraine",
        "Serbien" to "Serbia",
        "Ungarn" to "Hungary",
        "Tschechien" to "Czech Republic",
        "Slowakei" to "Slovakia",
        "Slowenien" to "Slovenia",
        "Rumänien" to "Romania",
        "Bulgarien" to "Bulgaria",
        "Griechenland" to "Greece",
        "Norwegen" to "Norway",
        "Finnland" to "Finland",
        "Irland" to "Ireland",
        "Nordirland" to "Northern Ireland",
        "Island" to "Iceland",
        "Albanien" to "Albania",
        "Georgien" to "Georgia",
        "Kasachstan" to "Kazakhstan",
        "Chile" to "Chile",
        "Ecuador" to "Ecuador",
        "Paraguay" to "Paraguay",
        "Peru" to "Peru",
        "Venezuela" to "Venezuela",
        "Bolivien" to "Bolivia",
        "Costa Rica" to "Costa Rica",
        "Panama" to "Panama",
        "Jamaika" to "Jamaica",
        "Honduras" to "Honduras",
        "El Salvador" to "El Salvador",
        "Kamerun" to "Cameroon",
        "Nigeria" to "Nigeria",
        "Elfenbeinküste" to "Ivory Coast",
        "Ghana" to "Ghana",
        "Algerien" to "Algeria",
        "Tunesien" to "Tunisia",
        "Ägypten" to "Egypt",
        "Südafrika" to "South Africa",
        "Australien" to "Australia",
        "Saudi-Arabien" to "Saudi Arabia",
        "Iran" to "Iran",
        "Irak" to "Iraq",
        "Katar" to "Qatar",
        "Vier. Arab. Emirate" to "United Arab Emirates",
        "China" to "China",
        "Neuseeland" to "New Zealand"
    )

    init {
        viewModelScope.launch {
            useOdds = repository.useOdds.first()
            oddsApiKey = repository.oddsApiKey.first()
            probHomeWin = repository.probHomeWin.first()
            probDraw = repository.probDraw.first()
        }
    }

    fun updateUseOdds(value: Boolean) {
        useOdds = value
        viewModelScope.launch {
            repository.updateOddsSettings(oddsApiKey, value)
        }
    }

    private suspend fun fetchOdds() {
        if (!useOdds || oddsApiKey.isBlank()) {
            cachedOdds = emptyMap()
            return
        }

        val sport = "soccer_fifa_world_cup" // Assuming this for World Cup

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
                        key to MatchProbabilities(pHome / sum, pDraw / sum, true)
                    } else {
                        "" to MatchProbabilities(0.45f, 0.25f, false)
                    }
                }.filterKeys { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getMatchProbabilities(t1: String, t2: String): MatchProbabilities {
        if (useOdds) {
            val name1 = (teamNameTranslation[t1] ?: t1).lowercase()
            val name2 = (teamNameTranslation[t2] ?: t2).lowercase()
            
            val key = cachedOdds.keys.find { oddsKey ->
                val ok = oddsKey.lowercase()
                (ok.contains(name1) || name1.contains(ok.split("-")[0].trim().lowercase())) && 
                (ok.contains(name2) || name2.contains(ok.split("-")[1].trim().lowercase()))
            }
            if (key != null) return cachedOdds[key]!!
        }
        return MatchProbabilities(probHomeWin, probDraw, false)
    }

    @Serializable
    data class SavedGroup(val teams: List<SavedTeam>, val firstMatchTime: String?)
    @Serializable
    data class SavedTeam(val name: String, val logo: String)

    fun loadAndSimulate() {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            debugInfo = null
            try {
                if (useOdds) fetchOdds()
                
                val leagueId = "wm26"
                val season = "2026"
                
                // Try to load cached groups first
                val cachedJson = repository.savedGroups.first()
                val groupsFromCache = if (cachedJson != null) {
                    try {
                        json.decodeFromString<List<SavedGroup>>(cachedJson)
                    } catch (e: Exception) { null }
                } else null

                val finalGroupStructure: List<List<TournamentTeam>>
                
                if (groupsFromCache != null && groupsFromCache.size == 12) {
                    finalGroupStructure = groupsFromCache.mapIndexed { gIdx, sg ->
                        sg.teams.map { st ->
                            TournamentTeam(
                                id = st.name,
                                name = st.name,
                                logo = st.logo,
                                group = ('A' + gIdx).toString()
                            )
                        }
                    }
                    debugInfo = "Loaded ${finalGroupStructure.size} groups from cache."
                } else {
                    val matches = RetrofitClient.api.getMatches(leagueId, season)
                    if (matches.isEmpty()) {
                        errorMessage = "No match data found for $leagueId $season"
                        return@launch
                    }

                    // Group stage is usually the first 3 matchdays
                    val groupMatches = matches.filter { (it.group?.groupOrderID ?: 0) <= 3 }
                    val teamsInGroup = mutableMapOf<String, MutableSet<String>>()
                    val teamLogos = mutableMapOf<String, String>()
                    val teamFirstMatch = mutableMapOf<String, String?>()

                    groupMatches.forEach { m ->
                        val t1 = m.team1.teamName
                        val t2 = m.team2.teamName
                        teamsInGroup.getOrPut(t1) { mutableSetOf() }.add(t2)
                        teamsInGroup.getOrPut(t2) { mutableSetOf() }.add(t1)
                        teamLogos[t1] = m.team1.teamIconUrl?.replace("http://", "https://") ?: ""
                        teamLogos[t2] = m.team2.teamIconUrl?.replace("http://", "https://") ?: ""
                        
                        if (teamFirstMatch[t1] == null || (m.matchDateTime != null && m.matchDateTime < teamFirstMatch[t1]!!)) {
                            teamFirstMatch[t1] = m.matchDateTime
                        }
                        if (teamFirstMatch[t2] == null || (m.matchDateTime != null && m.matchDateTime < teamFirstMatch[t2]!!)) {
                            teamFirstMatch[t2] = m.matchDateTime
                        }
                    }

                    // User's specific group assignments
                    val manualSeed = mapOf(
                        "Mexico" to 0, "Mexiko" to 0,
                        "Canada" to 1, "Kanada" to 1,
                        "Brazil" to 2, "Brasilien" to 2,
                        "USA" to 3,
                        "Germany" to 4, "Deutschland" to 4,
                        "Netherlands" to 5, "Niederlande" to 5,
                        "Belgium" to 6, "Belgien" to 6,
                        "Spain" to 7, "Spanien" to 7,
                        "France" to 8, "Frankreich" to 8,
                        "Argentina" to 9, "Argentinien" to 9,
                        "Portugal" to 10,
                        "England" to 11
                    )

                    val processedTeams = mutableSetOf<String>()
                    val detectedGroups = MutableList(12) { mutableSetOf<String>() }
                    val groupFirstTimes = MutableList<String?>(12) { null }

                    // First, place teams according to manual seed
                    teamsInGroup.keys.forEach { t ->
                        val seedIdx = manualSeed.entries.find { t.contains(it.key, ignoreCase = true) || it.key.contains(t, ignoreCase = true) }?.value
                        if (seedIdx != null && t !in processedTeams) {
                            val group = mutableSetOf(t)
                            group.addAll(teamsInGroup[t] ?: emptySet())
                            detectedGroups[seedIdx].addAll(group)
                            processedTeams.addAll(group)
                            groupFirstTimes[seedIdx] = group.mapNotNull { teamFirstMatch[it] }.minOrNull()
                        }
                    }

                    // Then, place remaining teams
                    teamsInGroup.keys.filter { it !in processedTeams }.forEach { t ->
                        val group = mutableSetOf(t)
                        group.addAll(teamsInGroup[t] ?: emptySet())
                        processedTeams.addAll(group)
                        
                        val firstEmptyIdx = detectedGroups.indexOfFirst { it.isEmpty() }
                        if (firstEmptyIdx != -1) {
                            detectedGroups[firstEmptyIdx].addAll(group)
                            groupFirstTimes[firstEmptyIdx] = group.mapNotNull { teamFirstMatch[it] }.minOrNull()
                        }
                    }

                    val finalGroups = detectedGroups.mapIndexed { idx, teams ->
                        SavedGroup(
                            teams = teams.map { SavedTeam(it, teamLogos[it] ?: "") },
                            firstMatchTime = groupFirstTimes[idx]
                        )
                    }
                    
                    repository.saveGroups(json.encodeToString<List<SavedGroup>>(finalGroups))

                    finalGroupStructure = finalGroups.mapIndexed { gIdx, sg ->
                        sg.teams.map { st ->
                            TournamentTeam(st.name, st.name, st.logo, ('A' + gIdx).toString())
                        }
                    }
                    debugInfo = "Detected 12 groups based on pairings and manual seeds."
                }

                withContext(Dispatchers.Default) {
                    runFullTournamentSimulationFromGroups(finalGroupStructure)
                }
            } catch (e: Exception) {
                errorMessage = "Error: ${e.message}"
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    private fun runFullTournamentSimulationFromGroups(groupStructure: List<List<TournamentTeam>>) {
        // 1. Ensure 48 Teams (12 groups of 4)
        val numGroups = 12
        val mutableGroups = groupStructure.map { it.toMutableList() }.toMutableList()
        
        while (mutableGroups.size < numGroups) {
            mutableGroups.add(mutableListOf())
        }

        var mockCount = 1
        mutableGroups.forEachIndexed { gIdx, group ->
            while (group.size < 4) {
                group.add(TournamentTeam(
                    id = "mock_$mockCount",
                    name = "Qualifier Slot $mockCount",
                    logo = "https://www.thesportsdb.com/images/media/team/badge/small/mock.png",
                    group = ('A' + gIdx).toString(),
                    isMock = true
                ))
                mockCount++
            }
        }
        
        // 2. Simulate Group Stage
        val groupMatchesMap = mutableMapOf<String, List<SimulatedMatch>>()
        mutableGroups.forEach { group ->
            val gName = group.firstOrNull()?.group ?: "?"
            val matches = mutableListOf<SimulatedMatch>()
            group.forEach { it.points = 0; it.goalsFor = 0; it.goalsAgainst = 0 }
            for (i in 0 until group.size) {
                for (j in i + 1 until group.size) {
                    matches.add(simulateGroupMatch(group[i], group[j]))
                }
            }
            groupMatchesMap[gName] = matches
            val sorted = group.sortedWith(
                compareByDescending<TournamentTeam> { it.points }
                    .thenByDescending { it.goalsDiff }
                    .thenByDescending { it.goalsFor }
            )
            sorted.forEachIndexed { index, team -> team.rank = index + 1 }
        }
        groupMatches = groupMatchesMap
        standings = mutableGroups.map { it.sortedBy { t -> t.rank } }

        // 3. Determine Knockout Participants (Top 2 from 12 groups + 8 best 3rds = 32 teams)
        val qualifiers = mutableMapOf<String, TournamentTeam>() // Key: "1A", "2B", etc.
        val thirdPlacedTeams = mutableListOf<TournamentTeam>()

        standings.forEachIndexed { idx, group ->
            val gName = ('A' + idx).toString()
            qualifiers["1$gName"] = group[0]
            qualifiers["2$gName"] = group[1]
            if (group.size >= 3) thirdPlacedTeams.add(group[2])
        }

        val bestThirds = thirdPlacedTeams.sortedWith(
            compareByDescending<TournamentTeam> { it.points }
                .thenByDescending { it.goalsDiff }
                .thenByDescending { it.goalsFor }
        ).take(8)
        
        // --- 4. Round of 32 (Matches 73-88) ---
        // Distribute 8 best thirds to specific matches (M74, M77, M79, M80, M81, M82, M85, M88)
        val assignedThirds = mutableMapOf<String, TournamentTeam>()
        val availableThirds = bestThirds.toMutableList()
        val thirdPlaceSlots = listOf("M74", "M77", "M79", "M80", "M81", "M82", "M85", "M88")
        
        thirdPlaceSlots.forEach { slot ->
            if (availableThirds.isNotEmpty()) {
                assignedThirds[slot] = availableThirds.removeAt(0)
            }
        }

        val m73 = simulateKnockoutMatch(qualifiers["2A"]!!, qualifiers["2B"]!!)
        val m74 = simulateKnockoutMatch(qualifiers["1E"]!!, assignedThirds["M74"] ?: qualifiers["2C"]!!)
        val m75 = simulateKnockoutMatch(qualifiers["1F"]!!, qualifiers["2C"]!!)
        val m76 = simulateKnockoutMatch(qualifiers["1C"]!!, qualifiers["2F"]!!)
        val m77 = simulateKnockoutMatch(qualifiers["1I"]!!, assignedThirds["M77"] ?: qualifiers["2D"]!!)
        val m78 = simulateKnockoutMatch(qualifiers["2E"]!!, qualifiers["2I"]!!)
        val m79 = simulateKnockoutMatch(qualifiers["1A"]!!, assignedThirds["M79"] ?: qualifiers["2E"]!!)
        val m80 = simulateKnockoutMatch(qualifiers["1L"]!!, assignedThirds["M80"] ?: qualifiers["2H"]!!)
        val m81 = simulateKnockoutMatch(qualifiers["1D"]!!, assignedThirds["M81"] ?: qualifiers["2G"]!!)
        val m82 = simulateKnockoutMatch(qualifiers["1G"]!!, assignedThirds["M82"] ?: qualifiers["2A"]!!)
        val m83 = simulateKnockoutMatch(qualifiers["2K"]!!, qualifiers["2L"]!!)
        val m84 = simulateKnockoutMatch(qualifiers["1H"]!!, qualifiers["2J"]!!)
        val m85 = simulateKnockoutMatch(qualifiers["1B"]!!, assignedThirds["M85"] ?: qualifiers["2I"]!!)
        val m86 = simulateKnockoutMatch(qualifiers["2D"]!!, qualifiers["2G"]!!)
        val m87 = simulateKnockoutMatch(qualifiers["1J"]!!, qualifiers["2H"]!!)
        val m88 = simulateKnockoutMatch(qualifiers["1K"]!!, assignedThirds["M88"] ?: qualifiers["2K"]!!)

        val r32 = TournamentRound("Round of 32", listOf(m73, m74, m75, m76, m77, m78, m79, m80, m81, m82, m83, m84, m85, m86, m87, m88))

        // --- 5. Round of 16 (Matches 89-96) ---
        val m89 = simulateKnockoutMatch(m74.winner, m77.winner)
        val m90 = simulateKnockoutMatch(m73.winner, m75.winner)
        val m91 = simulateKnockoutMatch(m76.winner, m78.winner)
        val m92 = simulateKnockoutMatch(m79.winner, m80.winner)
        val m93 = simulateKnockoutMatch(m83.winner, m84.winner)
        val m94 = simulateKnockoutMatch(m81.winner, m82.winner)
        val m95 = simulateKnockoutMatch(m86.winner, m88.winner)
        val m96 = simulateKnockoutMatch(m85.winner, m87.winner)
        
        val r16 = TournamentRound("Round of 16", listOf(m89, m90, m91, m92, m93, m94, m95, m96))

        // --- 6. Quarter-Finals (Matches 97-100) ---
        val m97 = simulateKnockoutMatch(m89.winner, m90.winner)
        val m98 = simulateKnockoutMatch(m93.winner, m94.winner)
        val m99 = simulateKnockoutMatch(m91.winner, m92.winner)
        val m100 = simulateKnockoutMatch(m95.winner, m96.winner)
        
        val qf = TournamentRound("Quarter-Final", listOf(m97, m98, m99, m100))

        // --- 7. Semi-Finals (Matches 101-102) ---
        val m101 = simulateKnockoutMatch(m97.winner, m98.winner)
        val m102 = simulateKnockoutMatch(m99.winner, m100.winner)
        
        val sf = TournamentRound("Semi-Final", listOf(m101, m102))

        // --- 8. Finals ---
        val finalMatch = simulateKnockoutMatch(m101.winner, m102.winner)
        val m101Loser = if (m101.winner == m101.team1) m101.team2 else m101.team1
        val m102Loser = if (m102.winner == m102.team1) m102.team2 else m102.team1
        val thirdPlaceMatch = simulateKnockoutMatch(m101Loser, m102Loser)
        
        val finals = TournamentRound("Finals", listOf(
            finalMatch,
            thirdPlaceMatch.copy(isThirdPlaceMatch = true)
        ))

        simulationRounds = listOf(r32, r16, qf, sf, finals)
    }

    private fun simulateGroupMatch(t1: TournamentTeam, t2: TournamentTeam): SimulatedMatch {
        val probs = getMatchProbabilities(t1.name, t2.name)
        val r = Random.nextFloat()
        
        val s1: Int
        val s2: Int
        
        if (r < probs.home) {
            // Team 1 wins
            s1 = Random.nextInt(1, 4)
            s2 = Random.nextInt(0, s1)
        } else if (r < (probs.home + probs.draw)) {
            // Draw
            s1 = Random.nextInt(0, 3)
            s2 = s1
        } else {
            // Team 2 wins
            s2 = Random.nextInt(1, 4)
            s1 = Random.nextInt(0, s2)
        }
        
        t1.goalsFor += s1; t1.goalsAgainst += s2
        t2.goalsFor += s2; t2.goalsAgainst += s1
        if (s1 > s2) t1.points += 3 else if (s2 > s1) t2.points += 3 else { t1.points += 1; t2.points += 1 }

        return SimulatedMatch(
            team1 = t1.copy(), 
            team2 = t2.copy(), 
            winner = if (s1 > s2) t1.copy() else if (s2 > s1) t2.copy() else t1.copy(), // dummy winner for draw
            score1 = s1, 
            score2 = s2, 
            isFinished = true,
            prob1 = probs.home,
            prob2 = 1f - probs.home - probs.draw,
            probDraw = probs.draw,
            isOddsApi = probs.isOddsApi
        )
    }

    private fun simulateKnockoutMatch(t1: TournamentTeam, t2: TournamentTeam): SimulatedMatch {
        val probs = getMatchProbabilities(t1.name, t2.name)
        val r = Random.nextFloat()
        
        val pHomeWinOnly = probs.home / (probs.home + (1f - probs.home - probs.draw))
        
        var s1: Int
        var s2: Int
        
        if (r < pHomeWinOnly) {
            s1 = Random.nextInt(1, 5)
            s2 = Random.nextInt(0, s1)
        } else {
            s2 = Random.nextInt(1, 5)
            s1 = Random.nextInt(0, s2)
        }

        return SimulatedMatch(
            team1 = t1, 
            team2 = t2, 
            winner = if (s1 > s2) t1 else t2, 
            score1 = s1, 
            score2 = s2, 
            isFinished = true,
            prob1 = pHomeWinOnly,
            prob2 = 1f - pHomeWinOnly,
            isOddsApi = probs.isOddsApi
        )
    }
}

class TournamentViewModelFactory(private val repository: SettingsRepository) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TournamentViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TournamentViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TournamentView(viewModel: TournamentViewModel) {
    var showOddsDebug by remember { mutableStateOf(false) }
    var selectedGroupInfo by remember { mutableStateOf<Pair<String, List<SimulatedMatch>>?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(16.dp).align(Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { viewModel.loadAndSimulate() },
                enabled = !viewModel.isLoading
            ) {
                if (viewModel.isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                else Text("Run Full World Cup 2026 Simulation")
            }
        }

        if (viewModel.oddsApiKey.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Checkbox(
                    checked = viewModel.useOdds,
                    onCheckedChange = { viewModel.updateUseOdds(it) }
                )
                Text("Use Odds API for better probabilities", fontSize = 13.sp)
                if (viewModel.useOdds) {
                    IconButton(onClick = { showOddsDebug = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Info, contentDescription = "Show Odds", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        if (viewModel.errorMessage != null) {
            Text(text = viewModel.errorMessage!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp).fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 12.sp)
        }

        if (viewModel.debugInfo != null) {
            Text(text = viewModel.debugInfo!!, style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), textAlign = TextAlign.Center)
        }

        if (viewModel.standings.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Vertical Group Stack
                Column(modifier = Modifier.width(220.dp).fillMaxHeight()) {
                    Text("Group Stage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.weight(1f)) {
                        items(viewModel.standings.size) { index ->
                            val gLetter = ('A' + index).toString()
                            GroupColumn(
                                name = "Group $gLetter", 
                                teams = viewModel.standings[index],
                                onClick = { 
                                    viewModel.groupMatches[gLetter]?.let { 
                                        selectedGroupInfo = "Group $gLetter" to it 
                                    }
                                }
                            )
                        }
                    }
                }

                // Knockout Rounds
                viewModel.simulationRounds.forEach { round ->
                    RoundColumn(round)
                }
            }
        }
    }

    if (showOddsDebug) {
        TournamentOddsDebugDialog(viewModel, onDismiss = { showOddsDebug = false })
    }

    if (selectedGroupInfo != null) {
        GroupMatchesDialog(
            groupName = selectedGroupInfo!!.first,
            matches = selectedGroupInfo!!.second,
            onDismiss = { selectedGroupInfo = null }
        )
    }
}

@Composable
fun GroupMatchesDialog(groupName: String, matches: List<SimulatedMatch>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$groupName Matches") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                items(matches) { match ->
                    MatchCard(match)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun TournamentOddsDebugDialog(viewModel: TournamentViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gelesene Quoten (World Cup)") },
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
fun RoundColumn(round: TournamentRound) {
    Column(modifier = Modifier.width(260.dp)) {
        Text(text = round.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(round.matches) { match -> MatchCard(match) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupColumn(name: String, teams: List<TournamentTeam>, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(), 
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(text = name, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
            teams.forEach { team ->
                Row(modifier = Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${team.rank}.", modifier = Modifier.width(20.dp), fontSize = 11.sp)
                    AsyncImage(model = team.logo, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 4.dp))
                    Text(team.name, modifier = Modifier.weight(1f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (team.isMock) Color.Gray else Color.Unspecified)
                    Text("${team.goalsDiff} GD", fontSize = 10.sp, modifier = Modifier.padding(horizontal = 4.dp))
                    Text("${team.points} pts", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchCard(match: SimulatedMatch) {
    val containerColor = if (match.isThirdPlaceMatch) MaterialTheme.colorScheme.tertiaryContainer 
                         else MaterialTheme.colorScheme.surfaceVariant
    
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    if (match.isThirdPlaceMatch) {
                        Text("Third Place Play-off", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp), color = MaterialTheme.colorScheme.primary)
                    }
                    TeamRowSim(match.team1, match.winner == match.team1, match.score1, match.prob1)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.3f))
                    TeamRowSim(match.team2, match.winner == match.team2, match.score2, match.prob2)
                }
                
                if (match.probDraw != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 8.dp)) {
                        Text("Draw", fontSize = 9.sp, color = Color.Gray)
                        Text("${(match.probDraw * 100).toInt()}%", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                
                if (match.isOddsApi) {
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(12.dp)
                            .background(MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.extraSmall),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("O", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun TeamRowSim(team: TournamentTeam, isWinner: Boolean, score: Int?, prob: Float?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(model = team.logo, contentDescription = null, modifier = Modifier.size(24.dp).padding(end = 8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = team.name,
                fontWeight = if (isWinner) FontWeight.Bold else FontWeight.Normal,
                color = if (isWinner) MaterialTheme.colorScheme.primary else if (team.isMock) Color.Gray else MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (prob != null) {
                Text(text = "${(prob * 100).toInt()}% win prob", fontSize = 9.sp, color = Color.Gray)
            }
        }
        if (score != null) {
            Text(text = score.toString(), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp), fontSize = 14.sp)
        }
        if (isWinner && score != null) { // score != null to avoid W on group matches without result in some cases
            Text("W", color = Color(0xFF4CAF50), fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
        }
    }
}
