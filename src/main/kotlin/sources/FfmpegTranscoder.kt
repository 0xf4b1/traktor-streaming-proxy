package sources

import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal object FfmpegTranscoder {
    fun audioUrlToMp4(
        url: String,
        remux: Boolean,
        transcodeBitrate: String,
        audioFilter: String?
    ): ByteArray {
        val output = Files.createTempFile("traktor-soundcloud-", ".mp4")
        try {
            if (remux) {
                val remuxResult = runFfmpeg(url, output.toString(), listOf("-c:a", "copy"))
                if (remuxResult.success) {
                    return Files.readAllBytes(output)
                }
                System.err.println(
                    "SoundCloud AAC remux failed; falling back to AAC transcoding: " +
                        remuxResult.output.takeLast(1_000)
                )
            }

            val transcodeResult = runFfmpeg(
                url,
                output.toString(),
                transcodeArguments(transcodeBitrate, audioFilter)
            )
            check(transcodeResult.success) {
                "ffmpeg failed: ${transcodeResult.output.takeLast(2_000)}"
            }
            return Files.readAllBytes(output)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    fun audioUrlToFragmentedMp4(
        url: String,
        remux: Boolean,
        transcodeBitrate: String,
        audioFilter: String?,
        output: OutputStream
    ) {
        val audioArguments = if (remux) {
            listOf("-c:a", "copy")
        } else {
            transcodeArguments(transcodeBitrate, audioFilter)
        }
        val command = baseCommand(url)
        command += audioArguments
        command += listOf(
            "-movflags",
            "frag_keyframe+empty_moov+default_base_moof",
            "-frag_duration",
            "1000000",
            "-flush_packets",
            "1",
            "-f",
            "mp4",
            "pipe:1"
        )

        val process = ProcessBuilder(command).start()
        val diagnosticOutput = AtomicReference("")
        val diagnosticReader = thread(
            start = true,
            isDaemon = true,
            name = "soundcloud-ffmpeg-diagnostics"
        ) {
            diagnosticOutput.set(process.errorStream.bufferedReader().use { it.readText() })
        }
        try {
            process.inputStream.use { input -> input.copyTo(output) }
            output.flush()
            val exitCode = process.waitFor()
            diagnosticReader.join()
            check(exitCode == 0) {
                "ffmpeg streaming failed with exit code $exitCode: " +
                    diagnosticOutput.get().takeLast(2_000)
            }
        } catch (exception: Exception) {
            process.destroyForcibly()
            diagnosticReader.join(1_000)
            throw exception
        }
    }

    private fun runFfmpeg(url: String, output: String, audioArguments: List<String>): Result {
        val command = baseCommand(url)
        command += audioArguments
        command += listOf(
            "-movflags",
            "+faststart",
            "-f",
            "mp4",
            output
        )
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val diagnosticOutput = process.inputStream.bufferedReader().use { it.readText() }
        return Result(process.waitFor() == 0, diagnosticOutput)
    }

    private fun baseCommand(url: String): MutableList<String> = mutableListOf(
        "ffmpeg",
        "-nostdin",
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-i",
        url,
        "-map",
        "0:a:0",
        "-vn",
    )

    private fun transcodeArguments(
        transcodeBitrate: String,
        audioFilter: String?
    ): List<String> = buildList {
        if (audioFilter != null) {
            addAll(listOf("-af", audioFilter))
        }
        addAll(
            listOf(
                "-c:a",
                "aac",
                "-b:a",
                transcodeBitrate,
                "-aac_coder",
                "twoloop"
            )
        )
    }

    private data class Result(val success: Boolean, val output: String)
}
