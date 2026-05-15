package com.vrca.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

// ✅ This matches: import com.vrca.data.dataStore
// ✅ Must be public top-level val, not private.
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vrca_prefs")
