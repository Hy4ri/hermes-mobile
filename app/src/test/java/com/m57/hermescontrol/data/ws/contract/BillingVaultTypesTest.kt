package com.m57.hermescontrol.data.ws.contract

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingVaultTypesTest {
    @Test
    fun emptyParamsEncodesToEmptyJsonObject() {
        val params = EmptyParams
        val encoded = OkHttpProvider.json.encodeToJsonElement(EmptyParams.serializer(), params)

        val expected = buildJsonObject {}
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertTrue((encoded as JsonObject).isEmpty())
    }

    @Test
    fun subscriptionPreviewParamsEncodesSubscriptionTypeIdWhenPresent() {
        val params = SubscriptionPreviewParams(subscriptionTypeId = "pro-monthly")
        val encoded = OkHttpProvider.json.encodeToJsonElement(SubscriptionPreviewParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("subscription_type_id", "pro-monthly")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("subscription_type_id"), (encoded as JsonObject).keys)
    }

    @Test
    fun subscriptionPreviewParamsOmitsNullSubscriptionTypeId() {
        val params = SubscriptionPreviewParams(subscriptionTypeId = null)
        val encoded = OkHttpProvider.json.encodeToJsonElement(SubscriptionPreviewParams.serializer(), params)

        val expected = buildJsonObject {}
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertTrue((encoded as JsonObject).isEmpty())
    }

    @Test
    fun subscriptionChangeParamsEncodesAllFieldsWhenPresent() {
        val params =
            SubscriptionChangeParams(
                subscriptionTypeId = "tier-2",
                cancel = true,
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(SubscriptionChangeParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("subscription_type_id", "tier-2")
                put("cancel", true)
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("subscription_type_id", "cancel"), (encoded as JsonObject).keys)
    }

    @Test
    fun subscriptionChangeParamsEncodesExplicitFalseForCancel() {
        val params =
            SubscriptionChangeParams(
                subscriptionTypeId = "tier-1",
                cancel = false,
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(SubscriptionChangeParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("subscription_type_id", "tier-1")
                put("cancel", false)
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("subscription_type_id", "cancel"), (encoded as JsonObject).keys)
    }

    @Test
    fun subscriptionChangeParamsOmitsOnlyNullFields() {
        val paramsOnlyCancel = SubscriptionChangeParams(subscriptionTypeId = null, cancel = false)
        val encodedOnlyCancel =
            OkHttpProvider.json.encodeToJsonElement(SubscriptionChangeParams.serializer(), paramsOnlyCancel)
        val expectedOnlyCancel =
            buildJsonObject {
                put("cancel", false)
            }
        assertEquals(expectedOnlyCancel, encodedOnlyCancel)
        assertTrue(encodedOnlyCancel is JsonObject)
        assertEquals(setOf("cancel"), (encodedOnlyCancel as JsonObject).keys)

        val paramsOnlyType = SubscriptionChangeParams(subscriptionTypeId = "tier-3", cancel = null)
        val encodedOnlyType =
            OkHttpProvider.json.encodeToJsonElement(SubscriptionChangeParams.serializer(), paramsOnlyType)
        val expectedOnlyType =
            buildJsonObject {
                put("subscription_type_id", "tier-3")
            }
        assertEquals(expectedOnlyType, encodedOnlyType)
        assertTrue(encodedOnlyType is JsonObject)
        assertEquals(setOf("subscription_type_id"), (encodedOnlyType as JsonObject).keys)

        val paramsEmpty = SubscriptionChangeParams(subscriptionTypeId = null, cancel = null)
        val encodedEmpty =
            OkHttpProvider.json.encodeToJsonElement(SubscriptionChangeParams.serializer(), paramsEmpty)
        val expectedEmpty = buildJsonObject {}
        assertEquals(expectedEmpty, encodedEmpty)
        assertTrue(encodedEmpty is JsonObject)
        assertTrue((encodedEmpty as JsonObject).isEmpty())
    }

    @Test
    fun subscriptionUpgradeParamsEncodesSubscriptionTypeIdWhenPresent() {
        val params = SubscriptionUpgradeParams(subscriptionTypeId = "enterprise-yearly")
        val encoded = OkHttpProvider.json.encodeToJsonElement(SubscriptionUpgradeParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("subscription_type_id", "enterprise-yearly")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("subscription_type_id"), (encoded as JsonObject).keys)
    }

    @Test
    fun subscriptionUpgradeParamsOmitsNullSubscriptionTypeId() {
        val params = SubscriptionUpgradeParams(subscriptionTypeId = null)
        val encoded = OkHttpProvider.json.encodeToJsonElement(SubscriptionUpgradeParams.serializer(), params)

        val expected = buildJsonObject {}
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertTrue((encoded as JsonObject).isEmpty())
    }

    @Test
    fun vaultSourceSetParamsEncodesAllFieldsWhenPresent() {
        val params =
            VaultSourceSetParams(
                name = "1password",
                enabled = true,
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(VaultSourceSetParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("name", "1password")
                put("enabled", true)
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("name", "enabled"), (encoded as JsonObject).keys)
    }

    @Test
    fun vaultSourceSetParamsEncodesExplicitFalseForEnabled() {
        val params =
            VaultSourceSetParams(
                name = "bitwarden",
                enabled = false,
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(VaultSourceSetParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("name", "bitwarden")
                put("enabled", false)
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("name", "enabled"), (encoded as JsonObject).keys)
    }

    @Test
    fun vaultSourceSetParamsOmitsNullFields() {
        val params = VaultSourceSetParams(name = "keepass", enabled = null)
        val encoded = OkHttpProvider.json.encodeToJsonElement(VaultSourceSetParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("name", "keepass")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("name"), (encoded as JsonObject).keys)

        val paramsEmpty = VaultSourceSetParams(name = null, enabled = null)
        val encodedEmpty =
            OkHttpProvider.json.encodeToJsonElement(VaultSourceSetParams.serializer(), paramsEmpty)
        val expectedEmpty = buildJsonObject {}
        assertEquals(expectedEmpty, encodedEmpty)
        assertTrue(encodedEmpty is JsonObject)
        assertTrue((encodedEmpty as JsonObject).isEmpty())
    }

    @Test
    fun vaultUnlockParamsEncodesAllFieldsWhenPresent() {
        val params =
            VaultUnlockParams(
                name = "local-vault",
                password = "secret-password",
            )
        val encoded = OkHttpProvider.json.encodeToJsonElement(VaultUnlockParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("name", "local-vault")
                put("password", "secret-password")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("name", "password"), (encoded as JsonObject).keys)
    }

    @Test
    fun vaultUnlockParamsOmitsNullFields() {
        val params = VaultUnlockParams(name = "local-vault", password = null)
        val encoded = OkHttpProvider.json.encodeToJsonElement(VaultUnlockParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("name", "local-vault")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("name"), (encoded as JsonObject).keys)

        val paramsEmpty = VaultUnlockParams(name = null, password = null)
        val encodedEmpty =
            OkHttpProvider.json.encodeToJsonElement(VaultUnlockParams.serializer(), paramsEmpty)
        val expectedEmpty = buildJsonObject {}
        assertEquals(expectedEmpty, encodedEmpty)
        assertTrue(encodedEmpty is JsonObject)
        assertTrue((encodedEmpty as JsonObject).isEmpty())
    }

    @Test
    fun vaultLockParamsEncodesEmptyJsonObjectWhenNameNull() {
        val params = VaultLockParams(name = null)
        val encoded = OkHttpProvider.json.encodeToJsonElement(VaultLockParams.serializer(), params)

        val expected = buildJsonObject {}
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertTrue((encoded as JsonObject).isEmpty())
    }

    @Test
    fun vaultLockParamsEncodesNameWhenSet() {
        val params = VaultLockParams(name = "primary-vault")
        val encoded = OkHttpProvider.json.encodeToJsonElement(VaultLockParams.serializer(), params)

        val expected =
            buildJsonObject {
                put("name", "primary-vault")
            }
        assertEquals(expected, encoded)
        assertTrue(encoded is JsonObject)
        assertEquals(setOf("name"), (encoded as JsonObject).keys)
    }

    @Test
    fun rpcMethodsRegistrationMatchesContract() {
        assertEquals("subscription.state", RpcMethods.SUBSCRIPTION_STATE.name)
        assertEquals("usage.bars", RpcMethods.USAGE_BARS.name)
        assertEquals("subscription.resume", RpcMethods.SUBSCRIPTION_RESUME.name)
        assertEquals("vault.list", RpcMethods.VAULT_LIST.name)
        assertEquals("vault.sources", RpcMethods.VAULT_SOURCES.name)
        assertEquals("subscription.preview", RpcMethods.SUBSCRIPTION_PREVIEW.name)
        assertEquals("subscription.change", RpcMethods.SUBSCRIPTION_CHANGE.name)
        assertEquals("subscription.upgrade", RpcMethods.SUBSCRIPTION_UPGRADE.name)
        assertEquals("vault.source.set", RpcMethods.VAULT_SOURCE_SET.name)
        assertEquals("vault.unlock", RpcMethods.VAULT_UNLOCK.name)
        assertEquals("vault.lock", RpcMethods.VAULT_LOCK.name)

        assertTrue(RpcMethods.all.contains(RpcMethods.SUBSCRIPTION_STATE))
        assertTrue(RpcMethods.all.contains(RpcMethods.USAGE_BARS))
        assertTrue(RpcMethods.all.contains(RpcMethods.SUBSCRIPTION_RESUME))
        assertTrue(RpcMethods.all.contains(RpcMethods.VAULT_LIST))
        assertTrue(RpcMethods.all.contains(RpcMethods.VAULT_SOURCES))
        assertTrue(RpcMethods.all.contains(RpcMethods.SUBSCRIPTION_PREVIEW))
        assertTrue(RpcMethods.all.contains(RpcMethods.SUBSCRIPTION_CHANGE))
        assertTrue(RpcMethods.all.contains(RpcMethods.SUBSCRIPTION_UPGRADE))
        assertTrue(RpcMethods.all.contains(RpcMethods.VAULT_SOURCE_SET))
        assertTrue(RpcMethods.all.contains(RpcMethods.VAULT_UNLOCK))
        assertTrue(RpcMethods.all.contains(RpcMethods.VAULT_LOCK))
    }

    @Test
    fun jsonElementPassthroughMethodsDecodeResultUntouched() {
        val arbitraryPayload =
            buildJsonObject {
                put("tier", "pro")
                put("credits", 4200)
                put("details", buildJsonObject { put("active", true) })
            }

        val methods =
            listOf(
                RpcMethods.SUBSCRIPTION_STATE,
                RpcMethods.USAGE_BARS,
                RpcMethods.SUBSCRIPTION_RESUME,
                RpcMethods.VAULT_LIST,
                RpcMethods.VAULT_SOURCES,
                RpcMethods.SUBSCRIPTION_PREVIEW,
                RpcMethods.SUBSCRIPTION_CHANGE,
                RpcMethods.SUBSCRIPTION_UPGRADE,
                RpcMethods.VAULT_SOURCE_SET,
                RpcMethods.VAULT_UNLOCK,
                RpcMethods.VAULT_LOCK,
            )

        for (method in methods) {
            val decoded: JsonElement =
                OkHttpProvider.json.decodeFromJsonElement(
                    method.result,
                    arbitraryPayload,
                )
            assertEquals("Method ${method.name} must pass raw result untouched", arbitraryPayload, decoded)
        }
    }
}
