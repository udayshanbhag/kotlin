/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.report

import org.gradle.api.Project
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskExecutionResult
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskSkippedResult
import org.jetbrains.kotlin.build.report.metrics.BuildMetrics
import org.jetbrains.kotlin.build.report.metrics.BuildMetricsReporter
import org.jetbrains.kotlin.build.report.metrics.BuildTime
import org.jetbrains.kotlin.gradle.plugin.KotlinGradleBuildServices
import org.jetbrains.kotlin.gradle.plugin.internal.state.TaskExecutionResults
import org.jetbrains.kotlin.gradle.report.data.BuildExecutionData
import org.jetbrains.kotlin.gradle.report.data.BuildExecutionDataProcessor
import org.jetbrains.kotlin.gradle.report.data.TaskExecutionData
import org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

abstract class BuildMetricsReporterService : BuildService<BuildMetricsReporterService.Parameters>,
    OperationCompletionListener, AutoCloseable {

    interface Parameters : BuildServiceParameters {
        var buildDataProcessors: List<BuildExecutionDataProcessor>
        var startParameters: List<String>
    }

    private val log = Logging.getLogger(this.javaClass)

    //Task path to build metrics
    private val buildMetricsMap = ConcurrentHashMap<String, BuildMetricsReporter>()
    private val taskRecords = ConcurrentHashMap<String, TaskRecord>()
    private val failureMessages = ConcurrentLinkedQueue<String?>()
    private val taskClass = ConcurrentHashMap<String, String>()

    public fun add(taskPath: String, type: String, metrics: BuildMetricsReporter) {
        if (buildMetricsMap.containsKey(taskPath)) {
            log.warn("Duplicate path $taskPath")
        }
        buildMetricsMap[taskPath] = metrics
        taskClass[taskPath] = type
    }

    override fun onFinish(event: FinishEvent?) {
        if (event is TaskFinishEvent) {
            val result = event.result
            val taskPath = event.descriptor.taskPath
            val startMs = event.result.startTime
            val finishMs = event.result.endTime
            if (result is TaskFailureResult) {
                failureMessages.addAll(result.failures.map { it.message })
            }
            val skipMessage = when (result) {
                is TaskSkippedResult -> result.skipMessage
                else -> null
            }
            val didWork = result is TaskExecutionResult
            val buildMetrics = BuildMetrics()
            buildMetrics.buildTimes.add(BuildTime.GRADLE_TASK, finishMs - startMs)

            buildMetricsMap[taskPath]?.also { buildMetrics.addAll(it.getMetrics()) }
            val taskExecutionResult = TaskExecutionResults[taskPath]
            val icLogLines = taskExecutionResult?.icLogLines ?: emptyList()
            taskExecutionResult?.buildMetrics?.also { buildMetrics.addAll(it) }
            taskRecords[taskPath] = TaskRecord(taskPath, startMs, finishMs, skipMessage, didWork, icLogLines, buildMetrics, taskClass[taskPath] ?: "unknown")
        }
    }

    override fun close() {
        val buildData = BuildExecutionData(
            startParameters = parameters.startParameters,
            failureMessages = failureMessages.toList(),
            taskExecutionData = taskRecords.values.sortedBy { it.startMs }
        )
        parameters.buildDataProcessors.forEach { it.process(buildData, log) }
        buildMetricsMap.clear()
        taskRecords.clear()
        failureMessages.clear()
    }

    companion object {
        fun registerIfAbsent(project: Project): Provider<BuildMetricsReporterService> {
            val buildDataProcessors = ArrayList<BuildExecutionDataProcessor>()
            val rootProject = project.gradle.rootProject
            val reportingSettings = reportingSettings(rootProject)

            if (reportingSettings.buildReportMode != BuildReportMode.NONE && reportingSettings.buildReportDir != null) {
                configurePlainTextReportWriter(rootProject.name, reportingSettings)?.let { buildProcessor ->
                    buildDataProcessors.add(buildProcessor)
                }
            }

            if (reportingSettings.metricsOutputFile != null) {
                buildDataProcessors.add(MetricsWriter(reportingSettings.metricsOutputFile.absoluteFile))
            }

            if (buildDataProcessors.isEmpty()) {
                return project.provider { DummyBuildMetricsReporterService() }
            }
            return project.gradle.sharedServices.registerIfAbsent(
                "build_metric_service_${KotlinGradleBuildServices::class.java.classLoader.hashCode()}",
                BuildMetricsReporterService::class.java
            ) {
                val gradle = project.gradle

                gradle.taskGraph.whenReady { graph ->
                    graph.allTasks.asSequence()
                        .filterIsInstance<AbstractKotlinCompile<*>>()
                        .forEach { it.reportingSettings = reportingSettings }
                }

                val startParameters = arrayListOf<String>()
                gradle.startParameter.apply {
                    startParameters.add("tasks = ${taskRequests.joinToString { it.args.toString() }}")
                    startParameters.add("excluded tasks = $excludedTaskNames")
                    startParameters.add("current dir = $currentDir")
                    startParameters.add("project properties args = $projectProperties")
                    startParameters.add("system properties args = $systemPropertiesArgs")
                }
                it.parameters.startParameters = startParameters
                it.parameters.buildDataProcessors = buildDataProcessors
            }!!
        }

    }

}

private class DummyBuildMetricsReporterService : BuildMetricsReporterService() {
    override fun getParameters(): Parameters = object : Parameters {
        override var buildDataProcessors: List<BuildExecutionDataProcessor> = emptyList()
        override var startParameters: List<String> = emptyList()
    }

}

private class TaskRecord(
    override val taskPath: String,
    override val startMs: Long,
    override val endMs: Long,
    override val skipMessage: String?,
    override val didWork: Boolean,
    override val icLogLines: List<String>,
    override val buildMetrics: BuildMetrics,
    override val type: String
) : TaskExecutionData {
    override val isKotlinTask by lazy {
        type.startsWith("org.jetbrains.kotlin")
    }

    override val totalTimeMs: Long
        get() = (endMs - startMs)
}