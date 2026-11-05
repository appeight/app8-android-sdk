package dev.app8.engine.render.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType as ImeKeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.app8.engine.core.layout.IntrinsicMeasure
import dev.app8.engine.core.layout.Size
import dev.app8.engine.core.model.ActionTrigger
import dev.app8.engine.core.model.AutocapitalizationType
import dev.app8.engine.core.model.EdgeInsets
import dev.app8.engine.core.model.KeyboardType
import dev.app8.engine.core.model.ReturnKeyType
import dev.app8.engine.core.model.StatefulContent
import dev.app8.engine.core.model.TextContentType
import dev.app8.engine.core.model.TextFieldProperties
import dev.app8.engine.core.model.TextViewProperties
import dev.app8.engine.core.model.Trigger
import dev.app8.engine.core.model.style.TextInputStyle
import dev.app8.engine.render.ChildrenLayout
import dev.app8.engine.render.controls.bringIntoViewAboveKeyboard
import dev.app8.engine.render.LayoutTraits
import dev.app8.engine.render.LocalDispatcher
import dev.app8.engine.render.LocalInteractionEnabled
import dev.app8.engine.render.LocalRenderEnv
import dev.app8.engine.render.RenderEnv
import dev.app8.engine.render.RenderNode
import dev.app8.engine.render.controls.FocusManager
import dev.app8.engine.render.controls.TextInputValidation
import dev.app8.engine.render.resolve
import dev.app8.engine.render.string
import dev.app8.engine.style.IosSystemColors
import dev.app8.engine.style.ResolvedText

/** What `textField` and `textView` share: the DSL properties both have. */
private class InputProps(
    val placeholder: String?,
    val text: String?,
    val bindVariable: String?,
    val keyboardType: KeyboardType?,
    val textContentType: TextContentType?,
    val returnKeyType: ReturnKeyType?,
    val autocapitalization: AutocapitalizationType?,
    val autocorrection: Boolean?,
    val maxLength: Int?,
    val isEnabled: Boolean?,
    val isSecure: Boolean = false,
    val inputMask: String? = null,
    val allowedCharacters: String? = null,
    val clearButtonMode: TextFieldProperties.ClearButtonMode? = null,
)

private fun inputProps(node: RenderNode): InputProps = when (val p = node.content.properties) {
    is TextFieldProperties -> InputProps(
        p.placeholder, p.text, p.bindVariable, p.keyboardType, p.textContentType, p.returnKeyType, p.autocapitalization,
        p.autocorrection, p.maxLength, p.isEnabled, p.isSecure == true, p.inputMask, p.allowedCharacters, p.clearButtonMode,
    )
    is TextViewProperties -> InputProps(
        p.placeholder, p.text, p.bindVariable, p.keyboardType, p.textContentType, p.returnKeyType, p.autocapitalization,
        p.autocorrection, p.maxLength, p.isEnabled,
    )
    else -> InputProps(null, null, null, null, null, null, null, null, null, null)
}

private const val TEXT_KEY = "text"

/**
 * The input's current text: a string `bindVariable` (written on every edit, so it is the
 * source of truth both ways), else the user's edits ([RenderNode.ui]), else the `text`
 * property (iOS `initializeText`). Read with tracking, so layout and drawing follow it.
 */
private fun currentText(node: RenderNode, p: InputProps): String {
    p.bindVariable?.let { b -> (node.resolve("{{$b}}") as? String)?.let { return it } }
    (node.ui[TEXT_KEY] as? String)?.let { return it }
    return node.string(p.text) ?: ""
}

/** UITextField / UITextView styling: DSL text style or UIKit defaults (17pt, black text, gray placeholder). */
private class InputLook(val text: ResolvedText, val placeholder: ResolvedText, val cursor: Color, val padding: PaddingPt)

internal data class PaddingPt(val top: Double, val left: Double, val bottom: Double, val right: Double)

private fun EdgeInsets?.resolve(top: Double, left: Double, bottom: Double, right: Double) =
    PaddingPt(this?.top ?: top, this?.left ?: left, this?.bottom ?: bottom, this?.right ?: right)

private fun look(node: RenderNode, env: RenderEnv, multiline: Boolean): InputLook {
    val style = node.content.style as? TextInputStyle
    val lines = if (multiline) 0 else 1
    val text = env.text(env.textModel(style?.text), 17f, Color.Black, lines)
    val placeholder = env.text(env.textModel(style?.placeholder), 17f, IosSystemColors.of("placeholderText", env.appearance), lines)
    val cursor = env.color(style?.tintColor) ?: IosSystemColors.of("systemBlue", env.appearance)
    val padding = if (multiline) style?.padding.resolve(12.0, 12.0, 12.0, 12.0) else style?.padding.resolve(0.0, 12.0, 0.0, 12.0)
    return InputLook(text, placeholder, cursor, padding)
}

internal fun KeyboardType?.toIme(secure: Boolean): ImeKeyboardType = when (this) {
    KeyboardType.asciiCapable, KeyboardType.numbersAndPunctuation, KeyboardType.twitter -> ImeKeyboardType.Ascii
    KeyboardType.URL, KeyboardType.webSearch -> ImeKeyboardType.Uri
    KeyboardType.numberPad, KeyboardType.asciiCapableNumberPad -> if (secure) ImeKeyboardType.NumberPassword else ImeKeyboardType.Number
    KeyboardType.phonePad, KeyboardType.namePhonePad -> ImeKeyboardType.Phone
    KeyboardType.emailAddress -> ImeKeyboardType.Email
    KeyboardType.decimalPad -> ImeKeyboardType.Decimal
    KeyboardType.default, null -> if (secure) ImeKeyboardType.Password else ImeKeyboardType.Text
}

internal fun ReturnKeyType?.toIme(multiline: Boolean): ImeAction = when (this) {
    ReturnKeyType.go, ReturnKeyType.join, ReturnKeyType.route, ReturnKeyType.`continue` -> ImeAction.Go
    ReturnKeyType.google, ReturnKeyType.yahoo, ReturnKeyType.search -> ImeAction.Search
    ReturnKeyType.next -> ImeAction.Next
    ReturnKeyType.send, ReturnKeyType.emergencyCall -> ImeAction.Send
    ReturnKeyType.done -> ImeAction.Done
    ReturnKeyType.default, null -> if (multiline) ImeAction.Default else ImeAction.Done
}

internal fun AutocapitalizationType?.toIme(): KeyboardCapitalization = when (this) {
    AutocapitalizationType.none -> KeyboardCapitalization.None
    AutocapitalizationType.words -> KeyboardCapitalization.Words
    AutocapitalizationType.allCharacters -> KeyboardCapitalization.Characters
    // UIKit's default is sentence capitalization.
    AutocapitalizationType.sentences, null -> KeyboardCapitalization.Sentences
}

/** `textContentType` → Android autofill hint. */
internal fun TextContentType.toAutofill(): ContentType? = when (this) {
    TextContentType.username -> ContentType.Username
    TextContentType.password -> ContentType.Password
    TextContentType.newPassword -> ContentType.NewPassword
    TextContentType.oneTimeCode -> ContentType.SmsOtpCode
    TextContentType.emailAddress -> ContentType.EmailAddress
    TextContentType.telephoneNumber -> ContentType.PhoneNumber
    TextContentType.name_ -> ContentType.PersonFullName
    TextContentType.givenName -> ContentType.PersonFirstName
    TextContentType.familyName -> ContentType.PersonLastName
    TextContentType.middleName -> ContentType.PersonMiddleName
    TextContentType.namePrefix -> ContentType.PersonNamePrefix
    TextContentType.nameSuffix -> ContentType.PersonNameSuffix
    TextContentType.addressCity -> ContentType.AddressLocality
    TextContentType.addressState -> ContentType.AddressRegion
    TextContentType.countryName -> ContentType.AddressCountry
    TextContentType.fullStreetAddress -> ContentType.PostalAddress
    TextContentType.streetAddressLine1 -> ContentType.AddressStreet
    TextContentType.streetAddressLine2 -> ContentType.AddressAuxiliaryDetails
    TextContentType.postalCode -> ContentType.PostalCode
    TextContentType.creditCardNumber -> ContentType.CreditCardNumber
    TextContentType.creditCardExpiration -> ContentType.CreditCardExpirationDate
    TextContentType.creditCardExpirationMonth -> ContentType.CreditCardExpirationMonth
    TextContentType.creditCardExpirationYear -> ContentType.CreditCardExpirationYear
    TextContentType.creditCardSecurityCode -> ContentType.CreditCardSecurityCode
    TextContentType.birthdate -> ContentType.BirthDateFull
    TextContentType.birthdateDay -> ContentType.BirthDateDay
    TextContentType.birthdateMonth -> ContentType.BirthDateMonth
    TextContentType.birthdateYear -> ContentType.BirthDateYear
    else -> null
}

/**
 * `textField` (UITextField) and `textView` (UITextView): a [BasicTextField] drawn entirely
 * from DSL styles (the material, text and placeholder styles, `tintColor` cursor, padding),
 * with Android's IME, autofill and selection handles. Two-way `bindVariable`, `onTextChange`
 * with `$value`, `focus`/`blur` state triggers, input masks, `maxLength` (auto-advance) and
 * `allowedCharacters` as on iOS. The return key runs the `tap` actions, then drops focus
 * unless they moved it.
 */
internal class TextInputKind(private val multiline: Boolean) : ComponentKind {
    override val handlesTaps: Boolean get() = true

    override fun traits(node: RenderNode, env: RenderEnv): LayoutTraits {
        val p = inputProps(node)
        val l = look(node, env, multiline)
        val text = currentText(node, p)
        val tv = node.content.properties as? TextViewProperties
        if (multiline) {
            if (tv?.autoGrow != true) return LayoutTraits.NONE
            val min = tv.minHeight ?: 44.0
            val max = tv.maxHeight ?: 200.0
            return LayoutTraits(
                intrinsic = IntrinsicMeasure { maxWidth ->
                    val inner = maxWidth?.let { (it - l.padding.left - l.padding.right).coerceAtLeast(1.0) }
                    val s = env.measure(AnnotatedString(text.ifEmpty { " " }), l.text, inner)
                    Size(maxWidth ?: (s.width + l.padding.left + l.padding.right), (s.height + l.padding.top + l.padding.bottom).coerceIn(min, max))
                },
                signature = "tv:$text|${l.text}|${l.padding}|$min|$max",
            )
        }
        val placeholder = node.string(p.placeholder) ?: ""
        return LayoutTraits(
            intrinsic = IntrinsicMeasure {
                val a = env.measure(AnnotatedString(text.ifEmpty { " " }), l.text, null)
                val b = env.measure(AnnotatedString(placeholder), l.placeholder, null)
                Size(maxOf(a.width, b.width) + l.padding.left + l.padding.right, maxOf(a.height, b.height) + l.padding.top + l.padding.bottom)
            },
            signature = "tf:$text|$placeholder|${l.text}|${l.padding}",
        )
    }

    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val env = LocalRenderEnv.current
        val dispatcher = LocalDispatcher.current
        val p = inputProps(node)
        val l = look(node, env, multiline)
        val text = currentText(node, p)
        val focus = node.session.focus
        val focusRequester = remember(node) { FocusRequester() }
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        var focused by remember(node) { mutableStateOf(false) }
        var value by remember(node) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
        // Variable → view (iOS externalTextUpdate).
        if (value.text != text) value = TextFieldValue(text, TextRange(text.length))

        DisposableEffect(node, focus) {
            focus.register(
                FocusManager.Entry(
                    node.key, node.id, node.session.treeOrder[node.key] ?: Int.MAX_VALUE,
                    request = { runCatching { focusRequester.requestFocus() }; keyboard?.show() },
                    release = { focusManager.clearFocus(); keyboard?.hide() },
                ),
            )
            onDispose { focus.unregister(node.key) }
        }

        val enabled = (p.isEnabled ?: true) && LocalInteractionEnabled.current
        val bind = p.bindVariable

        fun commit(newText: String) {
            node.ui[TEXT_KEY] = newText
            bind?.let { b -> runCatching { node.scope.store.setValue(b, newText) } }
            // onTextChange sees the new text as `$value` (iOS overlay); analytics fire through the dispatcher.
            dispatcher.dispatchTrigger(node, ActionTrigger.onTextChange, node.scope.untracked.overlaying("\$value", newText))
            val max = p.maxLength
            if (!multiline && max != null && newText.length >= max) focus.focusNext()
        }

        val onValueChange: (TextFieldValue) -> Unit = { new ->
            val old = value.text
            if (new.text == old) {
                value = new
            } else {
                val (inserted, removed) = TextInputValidation.edit(old, new.text)
                if (TextInputValidation.isInputAllowed(inserted, removed, old, p.maxLength, p.allowedCharacters)) {
                    val masked = p.inputMask?.let { TextInputValidation.formatWithMask(new.text, it) } ?: new.text
                    value = if (masked == new.text) new else TextFieldValue(masked, TextRange(masked.length))
                    commit(masked)
                }
            }
        }

        val keyboardOptions = KeyboardOptions(
            capitalization = p.autocapitalization.toIme(),
            autoCorrectEnabled = p.autocorrection,
            keyboardType = p.keyboardType.toIme(p.isSecure),
            imeAction = p.returnKeyType.toIme(multiline),
        )
        val onReturn: () -> Unit = {
            dispatcher.dispatchTrigger(node, ActionTrigger.tap)
            if (focus.currentKey == node.key || focus.currentKey == null) {
                focusManager.clearFocus()
                keyboard?.hide()
            }
        }
        val keyboardActions = if (multiline && (p.returnKeyType == null || p.returnKeyType == ReturnKeyType.default)) KeyboardActions.Default
        else KeyboardActions(onDone = { onReturn() }, onGo = { onReturn() }, onNext = { onReturn() }, onSearch = { onReturn() }, onSend = { onReturn() })

        var fieldModifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { s ->
                if (s.isFocused == focused) return@onFocusChanged
                focused = s.isFocused
                val triggers = (node.content as? StatefulContent)?.triggers
                if (s.isFocused) {
                    focus.didFocus(node.key)
                    triggers?.get(Trigger.focus)?.let { dispatcher.setState(node, it) }
                } else {
                    focus.didBlur(node.key)
                    triggers?.get(Trigger.blur)?.let { dispatcher.setState(node, it) }
                }
            }
        p.textContentType?.toAutofill()?.let { ct -> fieldModifier = fieldModifier.semantics { contentType = ct } }

        val placeholder = node.string(p.placeholder)
        val showClear = when (p.clearButtonMode) {
            TextFieldProperties.ClearButtonMode.always -> true
            TextFieldProperties.ClearButtonMode.whileEditing -> focused
            TextFieldProperties.ClearButtonMode.unlessEditing -> !focused
            else -> false
        } && value.text.isNotEmpty() && enabled

        Box(modifier.bringIntoViewAboveKeyboard(focused)) {
            val pad = Modifier.padding(start = l.padding.left.dp, top = l.padding.top.dp, end = l.padding.right.dp, bottom = l.padding.bottom.dp)
            Row(Modifier.fillMaxSize().then(pad), verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = fieldModifier.weight(1f).let { if (multiline) it.fillMaxSize() else it },
                    enabled = enabled,
                    textStyle = l.text.style,
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    singleLine = !multiline,
                    visualTransformation = if (p.isSecure) PasswordVisualTransformation() else VisualTransformation.None,
                    cursorBrush = SolidColor(l.cursor),
                    decorationBox = { inner ->
                        Box(contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart) {
                            if (value.text.isEmpty() && !placeholder.isNullOrEmpty()) {
                                BasicText(placeholder, style = l.placeholder.style, maxLines = l.placeholder.maxLines, softWrap = multiline)
                            }
                            inner()
                        }
                    },
                )
                if (!multiline && showClear) ClearButton(env) { onValueChange(TextFieldValue("")) }
            }
            ChildrenLayout(node, Modifier.fillMaxSize())
        }
    }

    /** UITextField's clear button: a filled gray circle with a white cross, 19pt in a 28pt target. */
    @Composable
    private fun ClearButton(env: RenderEnv, onClear: () -> Unit) {
        val fill = IosSystemColors.of("tertiaryLabel", env.appearance)
        Box(
            Modifier.size(28.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClear),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(17.dp)) {
                drawCircle(fill)
                val r = size.minDimension * 0.18f
                val c = center
                val w = 1.6.dp.toPx()
                drawLine(Color.White, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), w, StrokeCap.Round)
                drawLine(Color.White, Offset(c.x - r, c.y + r), Offset(c.x + r, c.y - r), w, StrokeCap.Round)
            }
        }
    }
}
