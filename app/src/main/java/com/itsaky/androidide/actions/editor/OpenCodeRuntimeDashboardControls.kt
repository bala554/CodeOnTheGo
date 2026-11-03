package com.itsaky.androidide.actions.editor

internal data class OpenCodeRuntimeDashboardControls(
	val canInstall: Boolean,
	val canStart: Boolean,
	val canStop: Boolean,
) {
	companion object {
		fun forStatus(
			status: OpenCodeServerStatus,
			supportedDevice: Boolean,
		): OpenCodeRuntimeDashboardControls =
			when (status) {
				OpenCodeServerStatus.NotInstalled -> OpenCodeRuntimeDashboardControls(supportedDevice, false, false)
				OpenCodeServerStatus.Stopped -> OpenCodeRuntimeDashboardControls(supportedDevice, true, false)
				OpenCodeServerStatus.Starting -> OpenCodeRuntimeDashboardControls(false, false, true)
				is OpenCodeServerStatus.Running -> OpenCodeRuntimeDashboardControls(false, false, status.managedByThisApp)
				is OpenCodeServerStatus.VersionConflict -> OpenCodeRuntimeDashboardControls(false, false, false)
				is OpenCodeServerStatus.Failed -> OpenCodeRuntimeDashboardControls(supportedDevice, false, false)
			}
	}
}
