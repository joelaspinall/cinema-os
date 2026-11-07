package com.joelaspinall.cinemaos.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

/** One diary entry logged in Cinema OS. */
@Entity(tableName = "diary")
data class DiaryRow(
    @PrimaryKey val id: String,
    val position: Int,
    val tmdbId: Int?,
    val title: String,
    val year: Int?,
    val watchedDate: String,
    val rating: Double?,
    val rewatch: Boolean,
    val review: String,
    val source: String,
    val exported: Boolean,
)

/** A film in one of Joel's lists: Letterboxd history, watchlist, recent RSS, pending Apple import. */
@Entity(tableName = "list_items")
data class ListItemRow(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val list: String,
    val position: Int,
    val title: String,
    val year: Int?,
    val rating: Double?,
    val tmdbId: Int?,
    val date: String?,
)

/** A film Joel owns on Apple TV (stored as JSON so new Film fields need no migration). */
@Entity(tableName = "owned")
data class OwnedRow(@PrimaryKey val key: String, val position: Int, val json: String)

/** Title|year → TMDB id, so each film is only looked up once. */
@Entity(tableName = "resolved")
data class ResolvedRow(@PrimaryKey val key: String, val tmdbId: Int)

/** Small single values (pending watch, import dates). */
@Entity(tableName = "meta")
data class MetaRow(@PrimaryKey val key: String, val value: String)

/** Last good response for each TMDB request, used when the TV is offline. */
@Entity(tableName = "api_cache")
data class ApiCacheRow(@PrimaryKey val url: String, val body: String, val savedAt: Long)

@Dao
interface CinemaDao {
    @Query("SELECT * FROM diary ORDER BY position") fun diary(): List<DiaryRow>
    @Query("SELECT * FROM list_items WHERE list = :list ORDER BY position") fun list(list: String): List<ListItemRow>
    @Query("SELECT * FROM owned ORDER BY position") fun owned(): List<OwnedRow>
    @Query("SELECT * FROM resolved") fun resolved(): List<ResolvedRow>
    @Query("SELECT * FROM meta") fun meta(): List<MetaRow>

    @Query("DELETE FROM diary") fun clearDiary()
    @Query("DELETE FROM list_items WHERE list = :list") fun clearList(list: String)
    @Query("DELETE FROM owned") fun clearOwned()
    @Query("DELETE FROM meta WHERE `key` = :key") fun clearMeta(key: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertDiary(rows: List<DiaryRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertList(rows: List<ListItemRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertOwned(rows: List<OwnedRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertResolved(rows: List<ResolvedRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putMeta(row: MetaRow)

    @Transaction fun replaceDiary(rows: List<DiaryRow>) { clearDiary(); insertDiary(rows) }
    @Transaction fun replaceList(list: String, rows: List<ListItemRow>) { clearList(list); insertList(rows) }
    @Transaction fun replaceOwned(rows: List<OwnedRow>) { clearOwned(); insertOwned(rows) }

    @Query("SELECT * FROM api_cache WHERE url = :url") fun cached(url: String): ApiCacheRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun cache(row: ApiCacheRow)
    @Query("DELETE FROM api_cache WHERE savedAt < :before") fun pruneCache(before: Long)
}

@Database(
    entities = [DiaryRow::class, ListItemRow::class, OwnedRow::class, ResolvedRow::class, MetaRow::class, ApiCacheRow::class],
    version = 1,
    exportSchema = false,
)
abstract class CinemaDatabase : RoomDatabase() {
    abstract fun dao(): CinemaDao

    companion object {
        fun open(context: Context): CinemaDatabase =
            Room.databaseBuilder(context, CinemaDatabase::class.java, "cinema.db")
                // Only the one-off load at start-up runs on the main thread; all writes are in the background.
                .allowMainThreadQueries()
                .build()
    }
}
