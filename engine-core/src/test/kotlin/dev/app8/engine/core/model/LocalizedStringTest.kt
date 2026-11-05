package dev.app8.engine.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Port of iOS LocalizedStringTests.swift. */
class LocalizedStringTest {

    private fun decode(json: String): LocalizedString = DslDecoder().decode(json, ::decodeLocalizedString)

    @Test
    fun decodesBareStringAsLiteral() {
        assertEquals(LocalizedString.Literal("Hello, world"), decode("\"Hello, world\""))
    }

    @Test
    fun decodesEmptyStringAsLiteral() {
        assertEquals("", decode("\"\"").rawValue)
    }

    @Test
    fun literalKeepsExpressionPlaceholdersIntact() {
        assertEquals(LocalizedString.Literal("Hello {{user.name}}"), decode("\"Hello {{user.name}}\""))
    }

    @Test
    fun decodesI18nObjectAsKey() {
        assertEquals(LocalizedString.Key("home.greeting"), decode("""{"${'$'}i18n":"home.greeting"}"""))
    }

    @Test
    fun i18nKeyRawValueReturnsKey() {
        assertEquals("checkout.cta", decode("""{"${'$'}i18n":"checkout.cta"}""").rawValue)
    }

    @Test
    fun throwsOnUnknownObjectKey() {
        assertFailsWith<DslDecodingException> { decode("""{"translate":"home.greeting"}""") }
    }

    @Test
    fun throwsOnNonStringI18nValue() {
        assertFailsWith<DslDecodingException> { decode("""{"${'$'}i18n":42}""") }
    }
}
