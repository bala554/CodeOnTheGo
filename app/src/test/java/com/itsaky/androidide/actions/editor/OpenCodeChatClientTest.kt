package com.itsaky.androidide.actions.editor

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenCodeChatClientTest {
	@Test
	fun `lists connected enabled models and keeps provider defaults`() {
		val response = JSONObject(
			"""
			{
			  "all": [
			    {
			      "id": "openai",
			      "name": "OpenAI",
			      "models": {
			        "gpt-test": {"id": "gpt-test", "name": "GPT Test", "enabled": true},
			        "disabled-model": {"id": "disabled-model", "name": "Disabled", "enabled": false}
			      }
			    },
			    {
			      "id": "anthropic",
			      "name": "Anthropic",
			      "models": {
			        "claude-test": {"id": "claude-test", "name": "Claude Test", "enabled": true}
			      }
			    }
			  ],
			  "connected": ["openai"],
			  "default": {"openai": "gpt-test", "anthropic": "claude-test"}
			}
			""".trimIndent(),
		)

		val catalog = parseOpenCodeModelCatalog(response)

		assertThat(catalog.providers).containsExactly(
			OpenCodeChatProvider(
				id = "openai",
				name = "OpenAI",
				models = listOf(
					OpenCodeChatModel(
						providerId = "openai",
						providerName = "OpenAI",
						id = "gpt-test",
						name = "GPT Test",
					),
				),
			),
		)
		assertThat(catalog.defaultModels).containsExactly("openai", "gpt-test")
	}
}
