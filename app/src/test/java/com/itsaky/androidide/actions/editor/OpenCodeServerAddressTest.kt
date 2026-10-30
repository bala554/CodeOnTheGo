package com.itsaky.androidide.actions.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpenCodeServerAddressTest {
	@Test
	fun `accepts HTTP and HTTPS server URLs`() {
		assertThat(OpenCodeServerAddress.parse("http://192.168.1.20:4096")).isNotNull()
		assertThat(OpenCodeServerAddress.parse("https://opencode.example.com/workspace")).isNotNull()
	}

	@Test
	fun `rejects missing host and unsupported schemes`() {
		assertThat(OpenCodeServerAddress.parse("/session")).isNull()
		assertThat(OpenCodeServerAddress.parse("ftp://opencode.example.com")).isNull()
	}

	@Test
	fun `rejects credentials query strings and fragments`() {
		assertThat(OpenCodeServerAddress.parse("https://user:password@opencode.example.com")).isNull()
		assertThat(OpenCodeServerAddress.parse("https://opencode.example.com/?token=secret")).isNull()
		assertThat(OpenCodeServerAddress.parse("https://opencode.example.com/#session")).isNull()
	}
}
