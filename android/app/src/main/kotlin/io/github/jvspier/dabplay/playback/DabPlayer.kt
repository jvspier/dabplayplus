package io.github.jvspier.dabplay.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import io.github.jvspier.dabplay.core.Reception
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.jvspier.dabplay.engine.DabEngine
import io.github.jvspier.dabplay.engine.EngineState
import io.github.jvspier.dabplay.data.LogoStore
import io.github.jvspier.dabplay.data.ServiceKey
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The MediaSession's player: maps play/pause and next/previous (steering-wheel keys) onto the engine and exposes the
 * current station, "Artist – Title" and the slide as metadata. Live radio: one dynamic, non-seekable item.
 */
@UnstableApi
class DabPlayer(
    private val engine: DabEngine,
    private val logos: StateFlow<Map<ServiceKey, LogoStore.Entry>>,
    private val logosOn: () -> Boolean,
    looper: Looper = Looper.getMainLooper(),
) : SimpleBasePlayer(looper) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var s: EngineState = engine.state.value
    private var logoKey: ServiceKey? = null
    private var logoBytes: ByteArray? = null

    init {
        scope.launch { engine.state.collect { s = it; invalidateState() } }
        scope.launch { logos.collect { logoKey = null; invalidateState() } }       // a logo arrived: refresh the artwork
    }

    /** The station logo as artwork when there's no slide (head unit media widgets show it); read once per station. */
    private fun logoFor(key: ServiceKey): ByteArray? {
        if (!logosOn()) return null
        if (key != logoKey) { logoKey = key; logoBytes = logos.value[key]?.file?.let { f -> runCatching { f.readBytes() }.getOrNull() } }
        return logoBytes
    }

    override fun getState(): State {
        val cur = s.current
        val cmds = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA, Player.COMMAND_STOP,
        ).build()
        val b = State.Builder()
            .setAvailableCommands(cmds)
            .setPlayWhenReady(s.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            // no signal: "buffering" tells the car's own display (and Android Auto-like UIs) that it's waiting, not stopped
            .setPlaybackState(when {
                cur == null -> Player.STATE_IDLE
                s.playing && (s.tuning || s.reception == Reception.LOST) -> Player.STATE_BUFFERING
                else -> Player.STATE_READY
            })
        if (cur != null) {
            val np = s.nowPlaying
            val md = MediaMetadata.Builder()
                .setStation(cur.label)
                .setTitle(if (np?.isSong == true) np.title else cur.label)
                .setArtist(if (np?.isSong == true) np.artist else np?.raw)
                .setDisplayTitle(cur.label)
                .setSubtitle(np?.display)
                .apply { (s.slide ?: logoFor(cur.key))?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
                .build()
            val item = MediaItemData.Builder(cur.key.toString())
                .setMediaItem(MediaItem.Builder().setMediaId(cur.key.toString()).setMediaMetadata(md).build())
                .setMediaMetadata(md)
                .setIsDynamic(true)
                .setIsSeekable(false)
                .build()
            b.setPlaylist(listOf(item)).setCurrentMediaItemIndex(0)
        }
        return b.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) engine.resume() else engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> engine.skip(+1)
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> engine.skip(-1)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> { engine.pause(); return Futures.immediateVoidFuture() }

    override fun handleRelease(): ListenableFuture<*> { scope.cancel(); return Futures.immediateVoidFuture() }
}
