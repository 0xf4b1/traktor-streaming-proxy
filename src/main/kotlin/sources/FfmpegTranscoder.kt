package sources

import java.nio.file.Files

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

            val transcodeArguments = mutableListOf<String>()
            if (audioFilter != null) {
                transcodeArguments += listOf("-af", audioFilter)
            }
            transcodeArguments += listOf(
                "-c:a",
                "aac",
                "-b:a",
                transcodeBitrate,
                "-aac_coder",
                "twoloop"
            )
            val transcodeResult = runFfmpeg(url, output.toString(), transcodeArguments)
            check(transcodeResult.success) {
                "ffmpeg failed: ${transcodeResult.output.takeLast(2_000)}"
            }
            return Files.readAllBytes(output)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private fun runFfmpeg(url: String, output: String, audioArguments: List<String>): Result {
        val command = mutableListOf(
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

    private data class Result(val success: Boolean, val output: String)
}
