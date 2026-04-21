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
}
