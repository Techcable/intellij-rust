/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.profiler.legacy

import com.intellij.execution.configurations.RunProfile
import com.intellij.profiler.clion.ProfilerExecutor
import org.rust.cargo.runconfig.buildtool.CargoBuildManager.isBuildToolWindowAvailable
import org.rust.cargo.runconfig.command.CargoCommandConfiguration
import org.rust.cargo.runconfig.legacy.RsAsyncRunner
import org.rust.profiler.dtrace.RsDTraceConfigurationExtension
import org.rust.profiler.perf.RsPerfConfigurationExtension

private const val ERROR_MESSAGE_TITLE: String = "Unable to run profiler"

/**
 * This runner is used if [isBuildToolWindowAvailable] is false.
 */
class RsProfilerRunnerLegacy : RsAsyncRunner(ProfilerExecutor.EXECUTOR_ID, ERROR_MESSAGE_TITLE) {
    override fun getRunnerId(): String = RUNNER_ID

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        if (profile !is CargoCommandConfiguration) return false
        return (RsDTraceConfigurationExtension.isEnabledFor() || RsPerfConfigurationExtension.isEnabledFor(profile)) &&
            super.canRun(executorId, profile)
    }

    companion object {
        const val RUNNER_ID: String = "RsProfilerRunnerLegacy"
    }
}
