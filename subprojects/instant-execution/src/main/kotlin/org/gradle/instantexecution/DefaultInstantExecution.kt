/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.instantexecution

import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.internal.GeneratedSubclasses
import org.gradle.api.internal.file.FilePropertyFactory
import org.gradle.api.internal.tasks.DefaultTaskInputs
import org.gradle.api.internal.tasks.DefaultTaskOutputs
import org.gradle.api.internal.tasks.properties.InputFilePropertyType
import org.gradle.api.internal.tasks.properties.OutputFilePropertyType
import org.gradle.api.internal.tasks.properties.PropertyValue
import org.gradle.api.internal.tasks.properties.PropertyVisitor
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.FileNormalizer
import org.gradle.initialization.InstantExecution
import org.gradle.instantexecution.serialization.DefaultReadContext
import org.gradle.instantexecution.serialization.DefaultWriteContext
import org.gradle.instantexecution.serialization.MutableReadContext
import org.gradle.instantexecution.serialization.MutableWriteContext
import org.gradle.instantexecution.serialization.PropertyKind
import org.gradle.instantexecution.serialization.ReadContext
import org.gradle.instantexecution.serialization.WriteContext
import org.gradle.instantexecution.serialization.beans.BeanPropertyReader
import org.gradle.instantexecution.serialization.beans.BeanPropertyWriter
import org.gradle.instantexecution.serialization.codecs.Codecs
import org.gradle.instantexecution.serialization.readClass
import org.gradle.instantexecution.serialization.readClassPath
import org.gradle.instantexecution.serialization.readCollection
import org.gradle.instantexecution.serialization.readCollectionInto
import org.gradle.instantexecution.serialization.readEnum
import org.gradle.instantexecution.serialization.readStrings
import org.gradle.instantexecution.serialization.withIsolate
import org.gradle.instantexecution.serialization.writeClass
import org.gradle.instantexecution.serialization.writeClassPath
import org.gradle.instantexecution.serialization.writeCollection
import org.gradle.instantexecution.serialization.writeEnum
import org.gradle.instantexecution.serialization.writeStrings
import org.gradle.internal.classloader.ClasspathUtil
import org.gradle.internal.classpath.ClassPath
import org.gradle.internal.hash.HashUtil
import org.gradle.internal.operations.BuildOperationExecutor
import org.gradle.internal.serialize.Decoder
import org.gradle.internal.serialize.Encoder
import org.gradle.internal.serialize.kryo.KryoBackedDecoder
import org.gradle.internal.serialize.kryo.KryoBackedEncoder
import org.gradle.util.GradleVersion
import org.gradle.util.Path

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

import java.util.ArrayList
import java.util.SortedSet


class DefaultInstantExecution(
    private val host: Host
) : InstantExecution {

    interface Host {

        val isSkipLoadingState: Boolean

        val currentBuild: ClassicModeBuild

        fun createBuild(rootProjectName: String): InstantExecutionBuild

        fun <T> getService(serviceType: Class<T>): T

        fun getSystemProperty(propertyName: String): String?

        val rootDir: File

        val requestedTaskNames: List<String>

        fun classLoaderFor(classPath: ClassPath): ClassLoader
    }

    override fun canExecuteInstantaneously(): Boolean = when {
        !isInstantExecutionEnabled -> {
            false
        }
        host.isSkipLoadingState -> {
            logger.lifecycle("Calculating task graph as skipping instant execution cache was requested")
            false
        }
        !instantExecutionStateFile.isFile -> {
            logger.lifecycle("Calculating task graph as no instant execution cache is available for tasks: ${host.requestedTaskNames.joinToString(" ")}")
            false
        }
        else -> {
            logger.lifecycle("Reusing instant execution cache. This is not guaranteed to work in any way.")
            true
        }
    }

    override fun saveTaskGraph() {

        if (!isInstantExecutionEnabled) {
            return
        }

        buildOperationExecutor.withStoreOperation {
            KryoBackedEncoder(stateFileOutputStream()).use { encoder ->
                DefaultWriteContext(codecs, encoder, logger).run {

                    val build = host.currentBuild
                    writeString(build.rootProject.name)
                    val scheduledTasks = build.scheduledTasks
                    writeRelevantProjectsFor(scheduledTasks)

                    val tasksClassPath = classPathFor(scheduledTasks)
                    writeClassPath(tasksClassPath)

                    writeTaskGraphOf(build, scheduledTasks)
                }
            }
        }
    }

    override fun loadTaskGraph() {

        require(isInstantExecutionEnabled)

        buildOperationExecutor.withLoadOperation {
            KryoBackedDecoder(stateFileInputStream()).use { decoder ->
                DefaultReadContext(codecs, decoder, logger).run {

                    val rootProjectName = readString()
                    val build = host.createBuild(rootProjectName)
                    readRelevantProjects(build)

                    build.autoApplyPlugins()
                    build.registerProjects()

                    val tasksClassPath = readClassPath()
                    val taskClassLoader = classLoaderFor(tasksClassPath)
                    initialize(build::getProject, taskClassLoader)

                    val scheduledTasks = readTaskGraph()
                    build.scheduleTasks(scheduledTasks)
                }
            }
        }
    }

    private
    val codecs by lazy {
        Codecs(
            directoryFileTreeFactory = service(),
            fileCollectionFactory = service(),
            fileResolver = service(),
            instantiator = service(),
            objectFactory = service(),
            patternSpecFactory = service(),
            filePropertyFactory = service()
        )
    }

    private
    fun MutableWriteContext.writeTaskGraphOf(build: ClassicModeBuild, tasks: List<Task>) {
        writeCollection(tasks) { task ->
            try {
                writeTask(task, build.dependenciesOf(task))
            } catch (e: Throwable) {
                throw GradleException("Could not save state of $task.", e)
            }
        }
    }

    private
    fun MutableReadContext.readTaskGraph(): List<Task> {
        val tasksWithDependencies = readTasksWithDependencies()
        wireTaskDependencies(tasksWithDependencies)
        return tasksWithDependencies.map { (task, _) -> task }
    }

    private
    fun MutableReadContext.readTasksWithDependencies(): List<Pair<Task, List<String>>> =
        readCollectionInto({ size -> ArrayList(size) }) {
            readTask()
        }

    private
    fun wireTaskDependencies(tasksWithDependencies: List<Pair<Task, List<String>>>) {
        val tasksByPath = tasksWithDependencies.associate { (task, _) ->
            task.path to task
        }
        tasksWithDependencies.forEach { (task, dependencies) ->
            task.dependsOn(dependencies.map(tasksByPath::getValue))
        }
    }

    private
    fun Encoder.writeRelevantProjectsFor(tasks: List<Task>) {
        writeCollection(fillTheGapsOf(relevantProjectPathsFor(tasks))) { projectPath ->
            writeString(projectPath.path)
        }
    }

    private
    fun Decoder.readRelevantProjects(build: InstantExecutionBuild) {
        readCollection {
            val projectPath = readString()
            build.createProject(projectPath)
        }
    }

    private
    fun relevantProjectPathsFor(tasks: List<Task>) =
        tasks.mapNotNull { task ->
            task.project.takeIf { it.parent != null }?.path?.let(Path::path)
        }.toSortedSet()

    private
    val filePropertyFactory: FilePropertyFactory
        get() = service()

    private
    val buildOperationExecutor: BuildOperationExecutor
        get() = service()

    private
    inline fun <reified T> service() =
        host.service<T>()

    private
    fun classLoaderFor(classPath: ClassPath) =
        host.classLoaderFor(classPath)

    private
    fun classPathFor(tasks: List<Task>) =
        tasks.map(::taskClassPath).fold(ClassPath.EMPTY, ClassPath::plus)

    private
    fun taskClassPath(task: Task) =
        task.javaClass.classLoader.let(ClasspathUtil::getClasspath)

    private
    fun MutableWriteContext.writeTask(task: Task, dependencies: Set<Task>) {
        val taskType = GeneratedSubclasses.unpack(task.javaClass)
        writeString(task.project.path)
        writeString(task.name)
        writeClass(taskType)
        writeStrings(dependencies.map { it.path })

        withIsolate(task) {
            BeanPropertyWriter(taskType).run {
                writeFieldsOf(task)
                writeRegisteredPropertiesOf(task, this)
            }
        }
    }

    private
    fun MutableReadContext.readTask(): Pair<Task, List<String>> {
        val projectPath = readString()
        val taskName = readString()
        val taskType = readClass().asSubclass(Task::class.java)
        val taskDependencies = readStrings()

        val task = createTask(projectPath, taskName, taskType)

        withIsolate(task) {
            BeanPropertyReader(taskType, filePropertyFactory).run {
                readFieldsOf(task)
                readRegisteredPropertiesOf(task, this)
            }
        }

        return task to taskDependencies
    }

    private
    fun WriteContext.writeRegisteredPropertiesOf(task: Task, propertyWriter: BeanPropertyWriter) {
        propertyWriter.run {

            fun writeProperty(propertyName: String, propertyValue: PropertyValue, kind: PropertyKind): Boolean {
                val value = propertyValue.call() ?: return false
                return writeNextProperty(propertyName, value, kind)
            }

            fun writeInputProperty(propertyName: String, propertyValue: PropertyValue): Boolean =
                writeProperty(propertyName, propertyValue, PropertyKind.InputProperty)

            fun writeOutputProperty(propertyName: String, propertyValue: PropertyValue): Boolean =
                writeProperty(propertyName, propertyValue, PropertyKind.OutputProperty)

            (task.outputs as? DefaultTaskOutputs)?.visitRegisteredProperties(
                object : PropertyVisitor.Adapter() {

                    override fun visitOutputFilePropertiesOnly(): Boolean =
                        true

                    override fun visitOutputFileProperty(
                        propertyName: String,
                        optional: Boolean,
                        value: PropertyValue,
                        filePropertyType: OutputFilePropertyType
                    ) {
                        if (!writeOutputProperty(propertyName, value)) {
                            return
                        }
                        writeBoolean(optional)
                        writeEnum(filePropertyType)
                    }
                }
            )
            writeString("")

            (task.inputs as? DefaultTaskInputs)?.visitRegisteredProperties(
                object : PropertyVisitor.Adapter() {

                    override fun visitInputProperty(
                        propertyName: String,
                        propertyValue: PropertyValue,
                        optional: Boolean
                    ) {
                        if (!writeInputProperty(propertyName, propertyValue)) {
                            return
                        }
                        writeBoolean(optional)
                        writeBoolean(false)
                    }

                    override fun visitInputFileProperty(
                        propertyName: String,
                        optional: Boolean,
                        skipWhenEmpty: Boolean,
                        incremental: Boolean,
                        fileNormalizer: Class<out FileNormalizer>?,
                        propertyValue: PropertyValue,
                        filePropertyType: InputFilePropertyType
                    ) {
                        if (!writeInputProperty(propertyName, propertyValue)) {
                            return
                        }
                        writeBoolean(optional)
                        writeBoolean(true)
                        writeEnum(filePropertyType)
                        writeBoolean(skipWhenEmpty)
                        writeClass(fileNormalizer!!)
                    }
                }
            )
            writeString("")
        }
    }

    private
    fun MutableReadContext.readRegisteredPropertiesOf(task: Task, propertyReader: BeanPropertyReader) {
        propertyReader.run {

            while (true) {
                val (propertyName, propertyValue) = readNextProperty(PropertyKind.OutputProperty) ?: break
                require(propertyValue != null)
                val optional = readBoolean()
                val filePropertyType = readEnum<OutputFilePropertyType>()
                task.outputs.run {
                    when (filePropertyType) {
                        OutputFilePropertyType.DIRECTORY -> dir(propertyValue)
                        OutputFilePropertyType.DIRECTORIES -> dirs(propertyValue)
                        OutputFilePropertyType.FILE -> file(propertyValue)
                        OutputFilePropertyType.FILES -> files(propertyValue)
                    }
                }.run {
                    withPropertyName(propertyName)
                    optional(optional)
                }
            }

            while (true) {
                val (propertyName, propertyValue) = readNextProperty(PropertyKind.InputProperty) ?: break
                require(propertyValue != null)
                val optional = readBoolean()
                val isFileInputProperty = readBoolean()
                if (isFileInputProperty) {
                    val filePropertyType = readEnum<InputFilePropertyType>()
                    val skipWhenEmpty = readBoolean()
                    val normalizer = readClass()
                    task.inputs.run {
                        when (filePropertyType) {
                            InputFilePropertyType.FILE -> file(propertyValue)
                            InputFilePropertyType.DIRECTORY -> dir(propertyValue)
                            InputFilePropertyType.FILES -> files(propertyValue)
                        }
                    }.run {
                        withPropertyName(propertyName)
                        optional(optional)
                        skipWhenEmpty(skipWhenEmpty)
                        @Suppress("unchecked_cast")
                        withNormalizer(normalizer as Class<out FileNormalizer>)
                    }
                } else {
                    task.inputs
                        .property(propertyName, propertyValue)
                        .optional(optional)
                }
            }
        }
    }

    private
    fun ReadContext.createTask(projectPath: String, taskName: String, taskClass: Class<out Task>) =
        getProject(projectPath).tasks.createWithoutConstructor(taskName, taskClass)

    private
    fun stateFileOutputStream(): FileOutputStream = instantExecutionStateFile.run {
        createParentDirectories()
        outputStream()
    }

    private
    fun stateFileInputStream() = instantExecutionStateFile.inputStream()

    private
    fun File.createParentDirectories() {
        Files.createDirectories(parentFile.toPath())
    }

    private
    val isInstantExecutionEnabled: Boolean
        get() = host.getSystemProperty("org.gradle.unsafe.instant-execution") != null

    private
    val instantExecutionStateFile by lazy {
        val currentGradleVersion = GradleVersion.current().version
        val cacheDir = File(host.rootDir, ".instant-execution-state/$currentGradleVersion").absoluteFile
        val baseName = HashUtil.createCompactMD5(host.requestedTaskNames.joinToString("/"))
        val cacheFileName = "$baseName.bin"
        File(cacheDir, cacheFileName)
    }
}


inline fun <reified T> DefaultInstantExecution.Host.service(): T =
    getService(T::class.java)


internal
fun fillTheGapsOf(paths: SortedSet<Path>): List<Path> {
    val pathsWithoutGaps = ArrayList<Path>(paths.size)
    var index = 0
    paths.forEach { path ->
        var parent = path.parent
        var added = 0
        while (parent !== null && parent !in pathsWithoutGaps) {
            pathsWithoutGaps.add(index, parent)
            added += 1
            parent = parent.parent
        }
        pathsWithoutGaps.add(path)
        added += 1
        index += added
    }
    return pathsWithoutGaps
}


private
val logger = Logging.getLogger(DefaultInstantExecution::class.java)
