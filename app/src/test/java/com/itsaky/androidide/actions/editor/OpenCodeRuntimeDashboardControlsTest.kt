package com.itsaky.androidide.actions.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpenCodeRuntimeDashboardControlsTest {
	@Test
	fun `only opens local chat when the server is managed by this app`() {
		assertThat(OpenCodeRuntimeDashboardControls.canOpenChat(OpenCodeServerStatus.Stopped)).isFalse()
		assertThat(OpenCodeRuntimeDashboardControls.canOpenChat(OpenCodeServerStatus.Running("1.18.31", true))).isTrue()
		assertThat(OpenCodeRuntimeDashboardControls.canOpenChat(OpenCodeServerStatus.Running("1.18.31", false))).isFalse()
	}

	@Test
	fun `offers install before setup and start after installation`() {
		assertThat(
			OpenCodeRuntimeDashboardControls.forStatus(OpenCodeServerStatus.NotInstalled, supportedDevice = true),
		).isEqualTo(OpenCodeRuntimeDashboardControls(canInstall = true, canStart = false, canStop = false))
		assertThat(
			OpenCodeRuntimeDashboardControls.forStatus(OpenCodeServerStatus.Stopped, supportedDevice = true),
		).isEqualTo(OpenCodeRuntimeDashboardControls(canInstall = true, canStart = true, canStop = false))
	}

	@Test
	fun `only offers stop for a server owned by this app`() {
		assertThat(
			OpenCodeRuntimeDashboardControls.forStatus(OpenCodeServerStatus.Starting, supportedDevice = true),
		).isEqualTo(OpenCodeRuntimeDashboardControls(canInstall = false, canStart = false, canStop = true))
		assertThat(
			OpenCodeRuntimeDashboardControls.forStatus(
				OpenCodeServerStatus.Running("1.18.31", managedByThisApp = true),
				supportedDevice = true,
			),
		).isEqualTo(OpenCodeRuntimeDashboardControls(canInstall = false, canStart = false, canStop = true))
		assertThat(
			OpenCodeRuntimeDashboardControls.forStatus(
				OpenCodeServerStatus.Running("1.18.31", managedByThisApp = false),
				supportedDevice = true,
			),
		).isEqualTo(OpenCodeRuntimeDashboardControls(canInstall = false, canStart = false, canStop = false))
	}

	@Test
	fun `does not offer actions on unsupported devices or a version conflict`() {
		assertThat(
			OpenCodeRuntimeDashboardControls.forStatus(OpenCodeServerStatus.NotInstalled, supportedDevice = false),
		).isEqualTo(OpenCodeRuntimeDashboardControls(canInstall = false, canStart = false, canStop = false))
		assertThat(
			OpenCodeRuntimeDashboardControls.forStatus(
				OpenCodeServerStatus.VersionConflict("9.9.9"),
				supportedDevice = true,
			),
		).isEqualTo(OpenCodeRuntimeDashboardControls(canInstall = false, canStart = false, canStop = false))
	}
}
