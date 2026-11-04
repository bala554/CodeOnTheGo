package com.itsaky.androidide.actions.editor

internal data class OpenCodeRuntimeDashboardControls(
    val showInstall: Boolean,
    val canInstall: Boolean,
    val canStart: Boolean,
    val canStop: Boolean,
) {
    companion object {
        fun canOpenChat(status: OpenCodeServerStatus): Boolean =
            status is OpenCodeServerStatus.Running && status.managedByThisApp

        fun forStatus(
            status: OpenCodeServerStatus,
            supportedDevice: Boolean,
        ): OpenCodeRuntimeDashboardControls =
            when (status) {
                OpenCodeServerStatus.NotInstalled ->
                    OpenCodeRuntimeDashboardControls(supportedDevice, supportedDevice, false, false)

                OpenCodeServerStatus.Stopped ->
                    OpenCodeRuntimeDashboardControls(false, false, true, false)

                OpenCodeServerStatus.Starting ->
                    OpenCodeRuntimeDashboardControls(false, false, false, true)

                is OpenCodeServerStatus.Running ->
                    OpenCodeRuntimeDashboardControls(false, false, false, status.managedByThisApp)

                is OpenCodeServerStatus.VersionConflict ->
                    OpenCodeRuntimeDashboardControls(false, false, false, false)

                is OpenCodeServerStatus.Failed ->
                    OpenCodeRuntimeDashboardControls(supportedDevice, supportedDevice, false, false)
            }
    }
}
