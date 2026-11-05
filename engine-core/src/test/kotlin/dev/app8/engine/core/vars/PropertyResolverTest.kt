package dev.app8.engine.core.vars

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Port of iOS `PropertyResolverTests`. */
class PropertyResolverTest {
    private val resolver = PropertyResolver()
    private val store = VariableStore().apply {
        defineVariable("name", VariableDefinition(VariableType.STRING, "John"))
        defineVariable("age", VariableDefinition(VariableType.NUMBER, 25))
        defineVariable("isActive", VariableDefinition(VariableType.BOOLEAN, true))
        defineVariable("items", VariableDefinition(VariableType.ARRAY, listOf("apple", "banana", "cherry")))
        defineVariable(
            "user",
            VariableDefinition(VariableType.OBJECT, mapOf("firstName" to "Jane", "lastName" to "Doe", "email" to "jane@example.com")),
        )
        defineVariable("count", VariableDefinition(VariableType.NUMBER, 3))
    }
    private val context = VariableContext(store)

    @Test
    fun containsExpressionTrue() {
        assertTrue(resolver.containsExpression("Hello {{name}}"))
        assertTrue(resolver.containsExpression("{{name}}"))
        assertTrue(resolver.containsExpression("{{a}} and {{b}}"))
    }

    @Test
    fun containsExpressionFalse() {
        assertFalse(resolver.containsExpression("Hello World"))
        assertFalse(resolver.containsExpression("Just text"))
        assertFalse(resolver.containsExpression("{{ incomplete"))
        assertFalse(resolver.containsExpression("incomplete }}"))
    }

    @Test fun resolveSingleExpressionReturnsRawValue() = assertEquals("John", resolver.resolve("{{name}}", context))
    @Test fun resolveSingleExpressionNumber() = assertEquals(25L, resolver.resolve("{{age}}", context))
    @Test fun resolveSingleExpressionBoolean() = assertEquals(true, resolver.resolve("{{isActive}}", context))
    @Test fun resolveSingleExpressionArray() = assertEquals(listOf("apple", "banana", "cherry"), resolver.resolve("{{items}}", context))
    @Test fun resolveSingleExpressionWithSpaces() = assertEquals(25L, resolver.resolve("  {{age}}  ", context))

    @Test fun resolveStringInterpolation() = assertEquals("Hello John!", resolver.resolve("Hello {{name}}!", context))
    @Test fun resolveMultipleExpressions() = assertEquals("John is 25 years old", resolver.resolve("{{name}} is {{age}} years old", context))
    @Test fun resolveExpressionAtStart() = assertEquals("John says hello", resolver.resolve("{{name}} says hello", context))
    @Test fun resolveExpressionAtEnd() = assertEquals("Hello John", resolver.resolve("Hello {{name}}", context))
    @Test fun resolveExpressionInMiddle() = assertEquals("Say John please", resolver.resolve("Say {{name}} please", context))

    @Test fun resolveNestedObjectAccess() = assertEquals("Jane", resolver.resolve("{{user.firstName}}", context))
    @Test fun resolveArrayAccess() = assertEquals("apple", resolver.resolve("{{items[0]}}", context))
    @Test fun resolveTernaryExpression() = assertEquals("Yes", resolver.resolve("{{isActive ? 'Yes' : 'No'}}", context))
    @Test fun resolveArithmeticExpression() = assertEquals(30L, resolver.resolve("{{age + 5}}", context))
    @Test fun resolveFunctionCall() = assertEquals(3L, resolver.resolve("{{length(items)}}", context))

    @Test
    fun resolveToStringAlwaysReturnsString() {
        assertEquals("John", resolver.resolveToString("{{name}}", context))
        assertEquals("25", resolver.resolveToString("{{age}}", context))
        assertEquals("true", resolver.resolveToString("{{isActive}}", context))
    }

    @Test fun resolveToStringWithInterpolation() = assertEquals("Count: 3", resolver.resolveToString("Count: {{count}}", context))
    @Test fun resolveToStringPlainText() = assertEquals("Plain text", resolver.resolveToString("Plain text", context))

    @Test fun resolveAnyWithString() = assertEquals("Hello John", resolver.resolveAny("Hello {{name}}", context))
    @Test fun resolveAnyWithNonString() = assertEquals(42, resolver.resolveAny(42, context))

    @Test
    fun resolveAnyWithArray() {
        val result = resolver.resolveAny(listOf("{{name}}", "{{age}}"), context) as List<*>
        assertEquals("John", result[0])
        assertEquals(25L, result[1])
    }

    @Test
    fun resolveAnyWithDictionary() {
        val result = resolver.resolveAny(mapOf("greeting" to "Hello {{name}}"), context) as Map<*, *>
        assertEquals("Hello John", result["greeting"])
    }

    @Test fun resolveAnyWithPlainString() = assertEquals("No expressions here", resolver.resolveAny("No expressions here", context))

    @Test fun resolveInvalidExpressionKeepsOriginal() = assertEquals("Hello {{undefinedVar}}", resolver.resolve("Hello {{undefinedVar}}", context))
    @Test fun resolveUnclosedBracket() = assertEquals("Hello {{name", resolver.resolve("Hello {{name", context))

    @Test fun resolveEmptyString() = assertEquals("", resolver.resolve("", context))
    @Test fun resolveOnlyText() = assertEquals("Just plain text", resolver.resolve("Just plain text", context))

    @Test
    fun resolveMultipleExpressionsWithArithmetic() =
        assertEquals("3 items: apple, banana", resolver.resolve("{{count}} items: {{items[0]}}, {{items[1]}}", context))

    @Test
    fun resolveComplexInterpolation() =
        assertEquals("Jane Doe (jane@example.com)", resolver.resolve("{{user.firstName}} {{user.lastName}} ({{user.email}})", context))

    // region Additional coverage (not in the Swift suite)

    @Test
    fun whole_string_expression_errors_propagate_and_nil_becomes_explicit_null() {
        assertFailsWith<VariableException.UndefinedVariable> { resolver.resolve("{{undefinedVar}}", context) }
        assertSame(Values.Null, resolver.resolve("{{user.missing}}", context))
        assertEquals("", resolver.resolveToString("{{user.missing}}", context))
    }

    @Test
    fun interpolation_stringifies_whole_doubles_without_fraction() {
        assertEquals("v=2 w=2.5", resolver.resolve("v={{4 / 2}} w={{5 / 2}}", context))
        assertEquals("2", resolver.resolveToString("{{4 / 2}}", context))
    }

    // endregion
}

/** Simplified iOS `TranslationStore` for tests: active → language-only → sibling region → default. */
class FakeTranslationStore : TranslationProvider {
    private var defaultLocale = "en"
    private var locales: Map<String, Map<String, String>> = emptyMap()
    private var active = "en"

    fun load(defaultLocale: String, locales: Map<String, Map<String, String>>) {
        this.defaultLocale = defaultLocale
        this.locales = locales
    }

    fun setActive(locale: String) {
        active = locale
    }

    override val activeLocale: Locale get() = Locale.forLanguageTag(active)

    override fun lookup(key: String): String? {
        locales[active]?.get(key)?.let { return it }
        val lang = active.substringBefore('-')
        locales[lang]?.get(key)?.let { return it }
        for ((k, v) in locales) if (k.substringBefore('-') == lang) v[key]?.let { return it }
        return locales[defaultLocale]?.get(key)
    }
}

/** Port of iOS `PropertyResolverLocalizedTests`. */
class PropertyResolverLocalizedTest {
    private val translations = FakeTranslationStore()
    private val resolver = PropertyResolver(translations)
    private val store = VariableStore()
    private val context = VariableContext(store)

    @Test
    fun literalPassesThroughUnchanged() {
        assertEquals("Hello", resolver.resolveLocalizedToString(LocalizedText.Literal("Hello"), context))
    }

    @Test
    fun literalStillRunsExpressionInterpolation() {
        store.defineVariable("name", VariableDefinition(VariableType.STRING, "Alex"))
        assertEquals("Hi Alex", resolver.resolveLocalizedToString(LocalizedText.Literal("Hi {{name}}"), context))
    }

    @Test
    fun keyLookupResolvesToTranslation() {
        translations.load("en", mapOf("fr" to mapOf("home.greeting" to "Bonjour")))
        translations.setActive("fr")
        assertEquals("Bonjour", resolver.resolveLocalizedToString(LocalizedText.Key("home.greeting"), context))
    }

    @Test
    fun keyLookupInterpolatesVariablesInTranslatedString() {
        translations.load("en", mapOf("fr" to mapOf("welcome.back" to "Bon retour, {{firstName}} !")))
        translations.setActive("fr")
        store.defineVariable("firstName", VariableDefinition(VariableType.STRING, "Alex"))
        assertEquals("Bon retour, Alex !", resolver.resolveLocalizedToString(LocalizedText.Key("welcome.back"), context))
    }

    @Test
    fun keyMissReturnsKeyAsDebugPlaceholder() {
        translations.load("en", mapOf("en" to emptyMap()))
        translations.setActive("en")
        assertEquals("missing.key", resolver.resolveLocalizedToString(LocalizedText.Key("missing.key"), context))
    }

    @Test
    fun keyLookupWithoutStoreReturnsKey() {
        assertEquals("home.greeting", PropertyResolver().resolveLocalizedToString(LocalizedText.Key("home.greeting"), context))
    }

    @Test
    fun i18nFunctionResolvesVariableValueAsKey() {
        translations.load(
            "en",
            mapOf("en" to mapOf("card.safe.title" to "It's safe"), "fr" to mapOf("card.safe.title" to "C'est sûr")),
        )
        translations.setActive("fr")
        store.defineVariable("title", VariableDefinition(VariableType.STRING, "card.safe.title"))
        assertEquals("C'est sûr", resolver.resolveLocalizedToString(LocalizedText.Literal("{{i18n(title)}}"), context))
    }

    @Test
    fun i18nFunctionMissReturnsKeyForDebugVisibility() {
        translations.load("en", mapOf("en" to emptyMap()))
        translations.setActive("en")
        store.defineVariable("title", VariableDefinition(VariableType.STRING, "nonexistent.key"))
        assertEquals("nonexistent.key", resolver.resolveLocalizedToString(LocalizedText.Literal("{{i18n(title)}}"), context))
    }

    @Test
    fun i18nFunctionAcceptsLiteralKey() {
        translations.load("en", mapOf("de" to mapOf("home.greeting" to "Hallo")))
        translations.setActive("de")
        assertEquals("Hallo", resolver.resolveLocalizedToString(LocalizedText.Literal("{{i18n('home.greeting')}}"), context))
    }

    @Test
    fun i18nFunctionUsableInsidePlainTextField() {
        translations.load("en", mapOf("de" to mapOf("home.greeting" to "Hallo")))
        translations.setActive("de")
        assertEquals(
            "Hallo, world!",
            resolver.resolveLocalizedToString(LocalizedText.Literal("{{i18n('home.greeting')}}, world!"), context),
        )
    }

    @Test
    fun i18nFunctionWithoutStoreReturnsKey() {
        store.defineVariable("title", VariableDefinition(VariableType.STRING, "card.safe.title"))
        assertEquals("card.safe.title", PropertyResolver().resolveLocalizedToString(LocalizedText.Literal("{{i18n(title)}}"), context))
    }

    @Test
    fun setLocaleAffectsCurrencyFormattingInTranslatedString() {
        translations.load(
            "en",
            mapOf(
                "en" to mapOf("price.total" to "Total: {{formatCurrency(amount, 'USD')}}"),
                "de" to mapOf("price.total" to "Total: {{formatCurrency(amount, 'USD')}}"),
            ),
        )
        store.defineVariable("amount", VariableDefinition(VariableType.NUMBER, 1234.5))
        translations.setActive("en-US")
        val us = resolver.resolveLocalizedToString(LocalizedText.Key("price.total"), context)
        translations.setActive("de-DE")
        val de = resolver.resolveLocalizedToString(LocalizedText.Key("price.total"), context)
        assertNotEquals(us, de)
        // Additional: exact values (iOS: "Total: $1,234.50" / "Total: 1.234,50 $").
        assertEquals("Total: $1,234.50", us)
        assertEquals("Total: 1.234,50 $", de)
    }
}
