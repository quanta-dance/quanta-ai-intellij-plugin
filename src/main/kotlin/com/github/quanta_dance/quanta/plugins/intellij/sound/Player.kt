import com.intellij.openapi.diagnostic.Logger
import javazoom.jl.player.Player as JLayerPlayer
import java.io.InputStream

object Player {

    private val logger = Logger.getInstance(Player::class.java)

    @Volatile
    private var currentThread: Thread? = null
    @Volatile
    private var currentPlayer: JLayerPlayer? = null
    @Volatile
    private var currentStream: InputStream? = null

    /**
     * Play MP3 from the given [audioData] InputStream asynchronously.
     * Any ongoing playback is stopped before starting a new one.
     *
     * @param audioData InputStream (e.g. from OpenAI speech API)
     * @param onFinished Callback invoked when playback ends or fails
     */
    @Synchronized
    fun playMp3(audioData: InputStream, onFinished: (() -> Unit)? = null) {
        // Stop previous playback if still running
        stop()

        val player = JLayerPlayer(audioData)
        currentPlayer = player
        currentStream = audioData

        val t = Thread({
            try {
                player.play() // blocks until stream ends or stopped
            } catch (t: Throwable) {
                logger.warn("Playback interrupted or failed: ${t.message}", t)
            } finally {
                cleanup(player, audioData, onFinished)
            }
        }, "AI-MP3-Player")

        currentThread = t
        t.isDaemon = true
        t.start()
    }

    /**
     * Stop current playback immediately, if any.
     */
    @Synchronized
    fun stop() {
        try {
            currentPlayer?.close()
        } catch (_: Throwable) {}

        try {
            currentStream?.close()
        } catch (_: Throwable) {}

        try {
            currentThread?.interrupt()
        } catch (_: Throwable) {}

        currentPlayer = null
        currentThread = null
        currentStream = null
    }

    /**
     * Internal cleanup after playback ends or fails.
     */
    @Synchronized
    private fun cleanup(
        player: JLayerPlayer,
        audioData: InputStream,
        onFinished: (() -> Unit)?
    ) {
        try {
            audioData.close()
        } catch (_: Throwable) {}

        if (currentPlayer === player) {
            currentPlayer = null
            currentThread = null
            currentStream = null
        }

        try {
            onFinished?.invoke()
        } catch (e: Throwable) {
            logger.warn("onFinished callback failed: ${e.message}", e)
        }
    }
}