import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.winhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import platform.posix._pclose
import platform.posix._popen
import platform.posix.fgets
import platform.posix.getenv
import platform.windows.GetLastError
import platform.windows.GetModuleFileNameW
import platform.windows.MAX_PATH

@Serializable
enum class JavaSource {
    HMCL_JAVA_HOME,
    JAVA_HOME,
    PATH_ENV_PRIMARY,
    PATH_ENV
}

@Serializable
data class JavaInfo(
    val path: String,
    val version: String = "Unknown",
    val vendor: String = "Unknown",
    val sources: MutableSet<JavaSource> = mutableSetOf(),
    val isBroken: Boolean = false,
    @Transient val error: Pair<Boolean, Throwable>? = null
) {
    val isValid: Boolean get() = !isBroken
}

@Serializable
private data class JavaProbeOutput(
    val java_home: String,
    val java_version: String,
    val java_vendor: String = "Unknown"
)

private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    isLenient = true
}

val client = HttpClient(WinHttp) {
    install(ContentNegotiation) {
        json(json)
    }
}

@OptIn(ExperimentalForeignApi::class)
val currentExecutablePath: String by lazy {
    memScoped {
        val bufferSize = (MAX_PATH * 2).toUInt()
        val buffer = allocArray<UShortVar>(bufferSize.toInt())
        val length = GetModuleFileNameW(null, buffer, bufferSize)
        if (length == 0u) {
            throw IllegalStateException("Failed to get executable path. Error: ${GetLastError()}")
        }
        buffer.toKString()
    }
}

@Serializable
data class SentData(
    val javaInfoList: List<JavaInfo>,
    val errors: List<Pair<String, String>>
)

@Serializable
private data class ResponseData(
    val key: String
)

fun main() = runBlocking {
    val results = detectJava()

    val errors: MutableList<Pair<String, String>> = mutableListOf()

    results.filter { it.isBroken }.forEach {
        if (it.error != null) {
            errors.add(Pair(it.path, it.error.second.stackTraceToString()))
        }
    }

    val sentData = SentData(
        javaInfoList = results,
        errors = errors,
    )

    val workerUrl = "https://d.ciluu.com/"

    val encodedBody = json.encodeToString(sentData).encodeURLQueryComponent()

    val response = client.post(workerUrl) {
        setBody(encodedBody)
        contentType(ContentType.Text.Plain)
    }

    println(response.body<ResponseData>().key)
}

suspend fun detectJava(): List<JavaInfo> = coroutineScope {
    val candidates = collectCandidates()

    val groupedCandidates = candidates
        .groupBy { normalizePath(it.first) }
        .mapValues { (_, entries) ->
            entries.flatMap { it.second }.toMutableSet()
        }

    groupedCandidates.map { (path, sources) ->
        async(Dispatchers.IO) {
            probeJava(path).apply {
                this.sources.addAll(sources)
            }
        }
    }.awaitAll()
}

@OptIn(ExperimentalForeignApi::class)
private fun collectCandidates(): List<Pair<String, List<JavaSource>>> = buildList {
    fun getEnv(key: String): String? = getenv(key)?.toKString()?.takeIf { it.isNotBlank() }

    getEnv("HMCL_JAVA_HOME")?.let { add(it to listOf(JavaSource.HMCL_JAVA_HOME)) }
    getEnv("JAVA_HOME")?.let { add(it to listOf(JavaSource.JAVA_HOME)) }

    getEnv("Path")?.split(";")?.forEachIndexed { index, entry ->
        val trimmed = entry.trim()
        if (trimmed.isBlank()) return@forEachIndexed

        val lower = trimmed.lowercase()
        if (lower.contains("java") || lower.contains("jdk") || lower.contains("jre") || lower.contains("zulu")) {
            val source = if (index == 0) JavaSource.PATH_ENV_PRIMARY else JavaSource.PATH_ENV
            add(trimmed to listOf(source))
        }
    }
}

private fun probeJava(homePath: String): JavaInfo {
    val cleanPathStr = Path(homePath).toString()

    val javaExe = listOf("bin/java.exe", "java.exe")
        .map { Path(cleanPathStr, it) }
        .firstOrNull { SystemFileSystem.exists(it) }

    if (javaExe == null) {
        return JavaInfo(
            path = homePath,
            isBroken = true,
            error = false to IllegalArgumentException("Executable not found (checked bin/java.exe and java.exe)")
        )
    }

    return runCatching {
        val javaPath = javaExe.toString().replace("/", "\\")
        val cpPath = currentExecutablePath.replace("/", "\\")

        val cmd = "\"\"$javaPath\" -cp \"$cpPath\" Main\" 2>&1"

        val output = execCommand(cmd)
        if (output.isBlank()) error("Process output is empty")

        val jsonStart = output.indexOf('{')
        if (jsonStart == -1) error("No JSON found in output. Raw: ${output.take(100)}...")

        val jsonContent = output.substring(jsonStart)
        val info = json.decodeFromString<JavaProbeOutput>(jsonContent)

        JavaInfo(
            path = info.java_home,
            version = info.java_version,
            vendor = info.java_vendor,
            isBroken = false
        )
    }.getOrElse { e ->
        JavaInfo(
            path = homePath,
            isBroken = true,
            error = true to e
        )
    }
}

private fun normalizePath(rawPath: String): String {
    var p = rawPath.replace("\\", "/").trim()
    if (p.endsWith("/")) p = p.dropLast(1)
    if (p.endsWith("/bin", ignoreCase = true)) {
        p = p.substringBeforeLast("/bin")
    }
    if (p.endsWith("/")) p = p.dropLast(1)
    return p
}

@OptIn(ExperimentalForeignApi::class)
private fun execCommand(command: String): String = memScoped {
    val fp = _popen(command, "r") ?: return ""

    val output = StringBuilder()
    val bufferSize = 4096
    val buffer = allocArray<ByteVar>(bufferSize)

    try {
        while (fgets(buffer, bufferSize, fp) != null) {
            output.append(buffer.toKString())
        }
    } finally {
        _pclose(fp)
    }

    return output.toString().trim()
}