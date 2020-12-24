package eu.kanade.tachiyomi.data.track.shikimori

import android.content.Context
import android.graphics.Color
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.util.lang.runAsObservable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rx.Completable
import rx.Observable
import uy.kohesive.injekt.injectLazy

class Shikimori(private val context: Context, id: Int) : TrackService(id) {

    companion object {
        const val READING = 1
        const val COMPLETED = 2
        const val ON_HOLD = 3
        const val DROPPED = 4
        const val PLANNING = 5
        const val REPEATING = 6

        const val DEFAULT_STATUS = READING
        const val DEFAULT_SCORE = 0
    }

    override val name = "Shikimori"

    private val json: Json by injectLazy()

    private val interceptor by lazy { ShikimoriInterceptor(this) }

    private val api by lazy { ShikimoriApi(client, interceptor) }

    override fun getScoreList(): List<String> {
        return IntRange(0, 10).map(Int::toString)
    }

    override fun displayScore(track: Track): String {
        return track.score.toInt().toString()
    }

    override fun add(track: Track): Observable<Track> {
        return runAsObservable({ api.addLibManga(track, getUsername()) })
    }

    override fun update(track: Track): Observable<Track> {
        return runAsObservable({ api.updateLibManga(track, getUsername()) })
    }

    override fun bind(track: Track): Observable<Track> {
        return runAsObservable({ api.findLibManga(track, getUsername()) })
            .flatMap { remoteTrack ->
                if (remoteTrack != null) {
                    track.copyPersonalFrom(remoteTrack)
                    track.library_id = remoteTrack.library_id
                    update(track)
                } else {
                    // Set default fields if it's not found in the list
                    track.score = DEFAULT_SCORE.toFloat()
                    track.status = DEFAULT_STATUS
                    add(track)
                }
            }
    }

    override fun search(query: String): Observable<List<TrackSearch>> {
        return runAsObservable({ api.search(query) })
    }

    override fun refresh(track: Track): Observable<Track> {
        return runAsObservable({ api.findLibManga(track, getUsername()) })
            .map { remoteTrack ->
                if (remoteTrack != null) {
                    track.copyPersonalFrom(remoteTrack)
                    track.total_chapters = remoteTrack.total_chapters
                }
                track
            }
    }

    override fun getLogo() = R.drawable.ic_tracker_shikimori

    override fun getLogoColor() = Color.rgb(40, 40, 40)

    override fun getStatusList(): List<Int> {
        return listOf(READING, COMPLETED, ON_HOLD, DROPPED, PLANNING, REPEATING)
    }

    override fun getStatus(status: Int): String = with(context) {
        when (status) {
            READING -> getString(R.string.reading)
            COMPLETED -> getString(R.string.completed)
            ON_HOLD -> getString(R.string.on_hold)
            DROPPED -> getString(R.string.dropped)
            PLANNING -> getString(R.string.plan_to_read)
            REPEATING -> getString(R.string.repeating)
            else -> ""
        }
    }

    override fun getCompletionStatus(): Int = COMPLETED

    override fun login(username: String, password: String) = login(password)

    fun login(code: String): Completable {
        return runAsObservable({ api.accessToken(code) }).map { oauth: OAuth? ->
            interceptor.newAuth(oauth)
            if (oauth != null) {
                val user = api.getCurrentUser()
                saveCredentials(user.toString(), oauth.access_token)
            }
        }.doOnError {
            logout()
        }.toCompletable()
    }

    fun saveToken(oauth: OAuth?) {
        preferences.trackToken(this).set(json.encodeToString(oauth))
    }

    fun restoreToken(): OAuth? {
        return try {
            json.decodeFromString<OAuth>(preferences.trackToken(this).get())
        } catch (e: Exception) {
            null
        }
    }

    override fun logout() {
        super.logout()
        preferences.trackToken(this).delete()
        interceptor.newAuth(null)
    }
}
