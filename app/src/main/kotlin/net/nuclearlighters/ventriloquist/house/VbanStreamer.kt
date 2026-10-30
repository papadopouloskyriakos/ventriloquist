package net.nuclearlighters.ventriloquist.house

import net.nuclearlighters.ventriloquist.capture.CaptureController
import net.nuclearlighters.ventriloquist.config.Config
import net.nuclearlighters.ventriloquist.vban.UdpSink
import net.nuclearlighters.ventriloquist.vban.VbanSender

/** [Streamer] that feeds playback capture into a [VbanSender] over UDP. */
class VbanStreamer(private val capture: CaptureController) : Streamer {
    private var udp: UdpSink? = null
    private var captureSink: CaptureController.Sink? = null

    @Volatile
    var sender: VbanSender? = null
        private set

    @Volatile
    var target: String? = null
        private set

    override val running: Boolean get() = captureSink != null

    override fun start(config: Config) {
        if (captureSink != null) return
        val socket = UdpSink(config.vbanHost, config.vbanPort)
        val s = VbanSender(config.vbanStream, socket)
        val sink = CaptureController.Sink { pcm, length -> s.send(pcm, length) }
        try {
            capture.addSink(sink)
        } catch (e: Exception) {
            socket.close()
            throw e
        }
        udp = socket
        sender = s
        captureSink = sink
        target = "${config.vbanHost}:${config.vbanPort} \"${config.vbanStream}\""
    }

    override fun stop() {
        captureSink?.let { capture.removeSink(it) }
        captureSink = null
        udp?.close()
        udp = null
    }
}
