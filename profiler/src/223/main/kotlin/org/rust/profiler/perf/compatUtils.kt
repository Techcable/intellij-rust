/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.profiler.perf

import com.intellij.execution.process.BaseProcessHandler
import com.intellij.openapi.project.Project
import com.intellij.profiler.clion.perf.PerfProfilerProcess
import com.jetbrains.cidr.lang.toolchains.CidrToolEnvironment
import java.nio.file.Path

// BACKCOMPAT: 2022.2. Inline it
fun createPerfProfilerProcess(
    perfProcessHandler: BaseProcessHandler<*>,
    isAttach: Boolean,
    outputPerfDataFile: Path,
    processName: String,
    project: Project,
    attachedTimestamp: Long,
    environment: CidrToolEnvironment
): PerfProfilerProcess = PerfProfilerProcess(
    perfProcessHandler,
    isAttach,
    outputPerfDataFile,
    processName,
    project,
    attachedTimestamp,
    environment
)
