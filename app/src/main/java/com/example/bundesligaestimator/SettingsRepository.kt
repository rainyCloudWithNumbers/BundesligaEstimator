package com.example.bundesligaestimator

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object PreferencesKeys {
        val MONTE_CARLO_ITERATIONS = intPreferencesKey("monte_carlo_iterations")
        val DETAIL_SIMULATIONS = intPreferencesKey("detail_simulations")
        val SEASON = stringPreferencesKey("season")
        val PROB_HOME_WIN = floatPreferencesKey("prob_home_win")
        val PROB_DRAW = floatPreferencesKey("prob_draw")
        val ODDS_API_KEY = stringPreferencesKey("odds_api_key")
        val USE_ODDS = booleanPreferencesKey("use_odds")
    }

    val monteCarloIterations: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.MONTE_CARLO_ITERATIONS] ?: 5000
    }

    val detailSimulations: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.DETAIL_SIMULATIONS] ?: 10000
    }

    val season: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.SEASON] ?: "2024"
    }

    val probHomeWin: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PROB_HOME_WIN] ?: 0.45f
    }

    val probDraw: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PROB_DRAW] ?: 0.25f
    }

    val oddsApiKey: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.ODDS_API_KEY] ?: ""
    }

    val useOdds: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.USE_ODDS] ?: false
    }

    suspend fun updateMonteCarloIterations(iterations: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MONTE_CARLO_ITERATIONS] = iterations
        }
    }

    suspend fun updateDetailSimulations(simulations: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DETAIL_SIMULATIONS] = simulations
        }
    }

    suspend fun updateSeason(season: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SEASON] = season
        }
    }

    suspend fun updateProbabilities(homeWin: Float, draw: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PROB_HOME_WIN] = homeWin
            preferences[PreferencesKeys.PROB_DRAW] = draw
        }
    }

    suspend fun updateOddsSettings(apiKey: String, useOdds: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ODDS_API_KEY] = apiKey
            preferences[PreferencesKeys.USE_ODDS] = useOdds
        }
    }
}
