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
