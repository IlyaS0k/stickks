package ru.ilyasok.StickKs.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.compilerRunner.toArgumentStrings
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.ilyasok.StickKs.core.utils.DSLDependenciesProvider
import ru.ilyasok.StickKs.dsl.FeatureBlock
import ru.ilyasok.StickKs.model.FeatureStatus
import ru.ilyasok.StickKs.model.OperationResult
import ru.ilyasok.StickKs.repository.IFeatureRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.UUID

@Service
class FeatureCompilationService(
    private val featureErrorsService: FeatureErrorsService,
    private val featureRepository: IFeatureRepository,
    dslDependenciesProvider: DSLDependenciesProvider
) {

    init {
       clearCompilationOutputDir()
    }

    companion object {

        private val logger: Logger = LoggerFactory.getLogger(FeatureCompilationService::class.java)

        private const val COMPILED_METHOD_NAME = "getFeature"

        private const val OUTPUT_DIR_NAME = "feature-compilation-output"

        private const val COMPILATOR_TARGET_JVM = "17"

        private val EXCLUDE_CP_EXTENSIONS = listOf("css", "js")

        private fun clearCompilationOutputDir() {
            val directory = File(OUTPUT_DIR_NAME).apply { mkdirs() }

            Files.walk(directory.toPath())
                .filter { it != directory.toPath() }
                .sorted(java.util.Comparator.reverseOrder())
                .forEach {
                    try {
                        Files.delete(it)
                        logger.debug("Deleted file: {}", it)
                    } catch (e: Exception) {
                        logger.debug("Failed to delete file {}: {}", it, e.message)
                    }
                }
        }

    }

    class CompilationResult(
        val featureBlock: FeatureBlock? = null,
        success: Boolean,
        error: Throwable? = null
    ) : OperationResult(success, error) {
        init {
            require(if (success) featureBlock != null && error == null else featureBlock == null && error != null) {
                "Failed to construct CompilationResult"
            }
        }
    }

    private var compilationOutputDir: File = File(OUTPUT_DIR_NAME)

    private val imports = dslDependenciesProvider.provideAsString()

    fun compileAsync(id: UUID?, featureCode: String, markBroken: Boolean = true) = CoroutineScope(Dispatchers.IO).async {
        val isBroken = if (id != null) featureRepository.findById(id)?.status == FeatureStatus.BROKEN else false
        if (!isBroken) {
            return@async compile(id, featureCode, markBroken)
        }
        return@async CompilationResult(success = false, error = RuntimeException("attempt to compile broken feature"), featureBlock = null)
    }

    fun compile(id: UUID?, featureCode: String, markBroken: Boolean = true): CompilationResult {
        logger.info("Starting compilation process for feature $id")
        val threadId = Thread.currentThread().id
        val compilationOutputFile = "Feature${threadId}.kt"
        val classToLoad = "Feature${threadId}Kt"
        val nameSubstrToDelete = "Feature${threadId}"
        try {
            val source = imports
                .plus("\n")
                .plus(
                    featureCode.replaceFirst(
                        Regex("""feature\s*\{"""), "fun $COMPILED_METHOD_NAME() = feature {"
                    )
                )

            val sourceFile = File(compilationOutputDir, compilationOutputFile).apply { writeText(source) }
            val args = K2JVMCompilerArguments().apply {
                freeArgs = listOf(sourceFile.absolutePath)
                destination = compilationOutputDir.absolutePath
                classpath = compilerClassPath()
                jvmTarget = COMPILATOR_TARGET_JVM

            }
            val compilationByteStream = ByteArrayOutputStream()
            val compilationOutputStream = PrintStream(compilationByteStream)
            val exitCode = K2JVMCompiler().exec(compilationOutputStream, *args.toArgumentStrings().toTypedArray())
            if (exitCode != ExitCode.OK) {
                logger.debug("Feature compilation error\n: {}", compilationByteStream.toString())
                val compilationError = RuntimeException("$exitCode : $compilationByteStream")
                if (id != null) runBlocking {
                    if (markBroken) setBroken(id)
                    featureErrorsService.updateFeatureErrors(id, compilationError.stackTraceToString())
                }
                return CompilationResult(success = false, error = compilationError)
            }
            URLClassLoader(arrayOf(compilationOutputDir.toURI().toURL())).use { classLoader ->
                val clazz = classLoader.loadClass(classToLoad)
                val method = clazz.getDeclaredMethod(COMPILED_METHOD_NAME)
                val feature = method.invoke(null) as FeatureBlock
                logger.debug("Successfully compiled feature(id = {})\n: {}", id, featureCode)
                return CompilationResult(success = true, featureBlock = feature)
            }
        } catch (error: InvocationTargetException) {
            val e = error.targetException
            logger.info("Incorrect DSL syntax(feature id = $id)\n: $featureCode", e)
            return CompilationResult(success = false, error = e)
        } catch (e: Throwable) {
            logger.info("Error while compiling feature(feature id = $id)\n: $featureCode", e)
            return CompilationResult(success = false, error = e)
        } finally {
            compilationOutputDir.listFiles()?.forEach {
                try {
                    if (it.name.contains(nameSubstrToDelete)) {
                        it.delete()
                    }
                } catch (e: Throwable) {
                    logger.error("Failed to delete temp compilation file $it", e)
                }
            }
        }
    }

    private fun compilerClassPath(): String {
        val rawClassPath = System.getProperty("java.class.path")
        val filterCpRegex = Regex(""".*\.(${EXCLUDE_CP_EXTENSIONS.joinToString("|")})$""", RegexOption.IGNORE_CASE)

        return rawClassPath
            .split(":")
            .filterNot { it.matches(filterCpRegex) }
            .joinToString(":")
    }

    private suspend fun setBroken(featureId: UUID)  {
        try {
            val feature = featureRepository.findById(featureId) ?: throw RuntimeException("Feature with id $featureId not found")
            optimisticTry(10) {
                featureRepository.save(feature.copy(status = FeatureStatus.BROKEN))
            }
        } catch (e: Throwable) {
            logger.error("Error while setting broken feature with if\n: $featureId", e)
        }
    }

}