package io.github.jvspier.dabplay.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.jvspier.dabplay.DabApp
import io.github.jvspier.dabplay.MainActivity
import io.github.jvspier.dabplay.R

/** Foreground media service: owns the MediaSession (steering-wheel keys, system media controls, notification). */
@UnstableApi
class RadioService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as DabApp
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val player = DabPlayer(app.engine, app.logos.state, logosOn = { app.settings.state.value.downloadLogos })
        // who pressed play/pause or next/previous (a steering-wheel key, the head unit going to sleep, another app):
        // the engine's log only says what happened
        val logCommands = object : MediaSession.Callback {
            override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
                COMMANDS[playerCommand]?.let { app.events.i("RadioService", "$it from ${controller.packageName}") }
                return super.onPlayerCommandRequest(session, controller, playerCommand)
            }
        }
        session = MediaSession.Builder(this, player).setSessionActivity(open).setCallback(logCommands).build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build()
            .apply { setSmallIcon(R.drawable.ic_notification) })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    private companion object {
        val COMMANDS = mapOf(Player.COMMAND_PLAY_PAUSE to "play/pause", Player.COMMAND_STOP to "stop",
            Player.COMMAND_SEEK_TO_NEXT to "next", Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM to "next",
            Player.COMMAND_SEEK_TO_PREVIOUS to "previous", Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM to "previous")
    }
}
