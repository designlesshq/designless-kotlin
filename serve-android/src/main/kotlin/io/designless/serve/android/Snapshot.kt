package io.designless.serve.android

import android.content.Context
import android.content.SharedPreferences
import io.designless.serve.SnapshotStore

/**
 * Keeps the last payload between launches, so a cold start shows the brand
 * rather than a blank screen while a request is in flight.
 *
 * `SharedPreferences` rather than a file or a database, because the read has
 * to happen before the first view is inflated. `getString` off an already-open
 * preferences instance is a map lookup; anything that touches disk on the main
 * thread at that moment has already lost the race it was meant to win.
 *
 * The write is `apply()`, not `commit()`: it is a cache, the next launch can
 * survive losing it, and blocking the caller to fsync a copy of something the
 * network just served is a cost with nothing on the other side.
 */
public class PreferencesSnapshotStore(
    context: Context,
    name: String = "designless.snapshots",
) : SnapshotStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
