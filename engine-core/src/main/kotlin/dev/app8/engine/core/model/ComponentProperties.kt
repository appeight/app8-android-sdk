package dev.app8.engine.core.model

// Per-component `properties` models (iOS `Components/<Name>/C<Name>Model.swift`). Expression strings stay raw.

/** `view` / `screen`. All fields accept `{{expressions}}`; animated fields accept `{ value, animation }`. */
public data class ViewProperties(
    val backgroundColor: AnimatedValue<String>? = null,
    val cornerRadius: Double? = null,
    val transformScale: AnimatedValue<String>? = null,
    val transformTranslateX: AnimatedValue<String>? = null,
    val transformTranslateY: AnimatedValue<String>? = null,
    val isHidden: String? = null,
    val alpha: AnimatedValue<String>? = null,
)

internal fun decodeViewProperties(c: Cursor): ViewProperties {
    c.obj()
    return ViewProperties(
        backgroundColor = c.opt("backgroundColor", ::decodeAnimatedString),
        cornerRadius = c.optDouble("cornerRadius"),
        transformScale = c.opt("transformScale", ::decodeAnimatedString),
        transformTranslateX = c.opt("transformTranslateX", ::decodeAnimatedString),
        transformTranslateY = c.opt("transformTranslateY", ::decodeAnimatedString),
        isHidden = c.optString("isHidden"),
        alpha = c.opt("alpha", ::decodeAnimatedString),
    )
}

public data class ButtonProperties(val text: String = "", val isEnabled: String? = null, val isSelected: String? = null)

internal fun decodeButtonProperties(c: Cursor): ButtonProperties {
    c.obj()
    return ButtonProperties(c.optString("text") ?: "", c.optString("isEnabled"), c.optString("isSelected"))
}

public data class ImageProperties(val type: Type, val model: Model) {
    @Suppress("EnumEntryName")
    public enum class Type { localAsset, remoteAsset, none }

    public sealed interface Model {
        public data class LocalAsset(val name: String) : Model
        public data class RemoteAsset(val asset: Asset) : Model
        public data object None : Model
    }
}

internal fun decodeImageProperties(c: Cursor): ImageProperties {
    c.obj()
    val type = c.req("type") { it.strictEnum<ImageProperties.Type>() }
    val model = when (type) {
        ImageProperties.Type.localAsset -> ImageProperties.Model.LocalAsset(c.reqString("name"))
        ImageProperties.Type.remoteAsset -> ImageProperties.Model.RemoteAsset(decodeAsset(c))
        ImageProperties.Type.none -> ImageProperties.Model.None
    }
    return ImageProperties(type, model)
}

public data class IconProperties(
    val type: Type,
    val model: Model,
    val backgroundColor: AnimatedValue<String>? = null,
    val tintColor: AnimatedValue<String>? = null,
    val isHidden: String? = null,
) {
    @Suppress("EnumEntryName")
    public enum class Type { symbol, asset, remote, none }

    public sealed interface Model {
        /** SF Symbol name; on Android mapped to a Material Symbol (or overridden via the node's `android` object). */
        public data class Symbol(val name: String) : Model
        public data class AssetModel(val name: String) : Model
        public data class Remote(val url: String) : Model
        public data object None : Model
    }
}

internal fun decodeIconProperties(c: Cursor): IconProperties {
    c.obj()
    val type = c.req("type") { it.strictEnum<IconProperties.Type>() }
    val backgroundColor = c.opt("backgroundColor", ::decodeAnimatedString)
    val tintColor = c.opt("tintColor", ::decodeAnimatedString)
    val isHidden = c.optString("isHidden")
    val model = when (type) {
        IconProperties.Type.symbol -> IconProperties.Model.Symbol(c.reqString("name"))
        IconProperties.Type.asset -> IconProperties.Model.AssetModel(c.reqString("name"))
        IconProperties.Type.remote -> IconProperties.Model.Remote(
            c.req("url") { u -> u.string().also { if (it.isEmpty()) u.fail("invalid URL") } },
        )
        IconProperties.Type.none -> IconProperties.Model.None
    }
    return IconProperties(type, model, backgroundColor, tintColor, isHidden)
}

public data class LabelProperties(
    val text: LocalizedString = LocalizedString.Literal(""),
    val backgroundColor: AnimatedValue<String>? = null,
    val isHidden: String? = null,
    val numberOfLines: Int? = null,
    val spans: List<Span>? = null,
) {
    public data class Span(val from: Int, val to: Int, val fontFamily: String? = null, val color: String? = null)
}

internal fun decodeLabelProperties(c: Cursor): LabelProperties {
    c.obj()
    return LabelProperties(
        text = c.opt("text", ::decodeLocalizedString) ?: LocalizedString.Literal(""),
        backgroundColor = c.opt("backgroundColor", ::decodeAnimatedString),
        isHidden = c.optString("isHidden"),
        numberOfLines = c.optInt("numberOfLines"),
        spans = c.opt("spans") { a ->
            a.list { s ->
                s.obj()
                LabelProperties.Span(s.req("from") { it.int() }, s.req("to") { it.int() }, s.optString("fontFamily"), s.optString("color"))
            }
        },
    )
}

public data class ScrollViewProperties(
    val direction: Direction? = null,
    val showsIndicator: Boolean? = null,
    val outputVariable: String? = null,
    val scrollThreshold: Double? = null,
    val contentInset: EdgeInsets? = null,
    val contentInsetAdjustment: ContentInsetAdjustment? = null,
    val bounces: Boolean? = null,
    val pagingEnabled: Boolean? = null,
    val keyboardDismissMode: KeyboardDismissMode? = null,
    val decelerationRate: DecelerationRate? = null,
    val autoScroll: AutoScroll? = null,
) {
    @Suppress("EnumEntryName")
    public enum class Direction { vertical, horizontal }

    @Suppress("EnumEntryName")
    public enum class ContentInsetAdjustment { never, automatic }

    @Suppress("EnumEntryName")
    public enum class KeyboardDismissMode { none, onDrag, interactive }

    @Suppress("EnumEntryName")
    public enum class DecelerationRate { normal, fast }

    public data class AutoScroll(val speed: Double, val infinite: Boolean? = null, val loopGap: Double? = null)
}

internal fun decodeScrollViewProperties(c: Cursor): ScrollViewProperties {
    c.obj()
    return ScrollViewProperties(
        direction = c.optEnum<ScrollViewProperties.Direction>("direction"),
        showsIndicator = c.optBool("showsIndicator"),
        outputVariable = c.optString("outputVariable"),
        scrollThreshold = c.optDouble("scrollThreshold"),
        contentInset = c.opt("contentInset", ::decodeEdgeInsets),
        contentInsetAdjustment = c.optEnum<ScrollViewProperties.ContentInsetAdjustment>("contentInsetAdjustment"),
        bounces = c.optBool("bounces"),
        pagingEnabled = c.optBool("pagingEnabled"),
        keyboardDismissMode = c.optEnum<ScrollViewProperties.KeyboardDismissMode>("keyboardDismissMode"),
        decelerationRate = c.optEnum<ScrollViewProperties.DecelerationRate>("decelerationRate"),
        autoScroll = c.opt("autoScroll") { a ->
            a.obj()
            ScrollViewProperties.AutoScroll(a.reqDouble("speed"), a.optBool("infinite"), a.optDouble("loopGap"))
        },
    )
}

public data class StackViewProperties(
    val axis: String? = null,
    val spacing: Double? = null,
    val alignment: String? = null,
    val distribution: String? = null,
    val backgroundColor: String? = null,
    val cornerRadius: Double? = null,
)

internal fun decodeStackViewProperties(c: Cursor): StackViewProperties {
    c.obj()
    return StackViewProperties(
        c.optString("axis"), c.optDouble("spacing"), c.optString("alignment"),
        c.optString("distribution"), c.optString("backgroundColor"), c.optDouble("cornerRadius"),
    )
}

public data class ActivityIndicatorProperties(val isAnimating: String? = null, val hidesWhenStopped: Boolean? = null)

internal fun decodeActivityIndicatorProperties(c: Cursor): ActivityIndicatorProperties {
    c.obj()
    return ActivityIndicatorProperties(c.optString("isAnimating"), c.optBool("hidesWhenStopped"))
}

public data class DatePickerProperties(
    val selectedDate: String? = null,
    val bindVariable: String? = null,
    val datePickerMode: Mode? = null,
    val displayStyle: DisplayStyle? = null,
    val minimumDate: String? = null,
    val maximumDate: String? = null,
    val isEnabled: String? = null,
) {
    @Suppress("EnumEntryName")
    public enum class Mode { date, time, dateAndTime, countdownTimer }

    @Suppress("EnumEntryName")
    public enum class DisplayStyle { compact, inline, wheels }
}

internal fun decodeDatePickerProperties(c: Cursor): DatePickerProperties {
    c.obj()
    return DatePickerProperties(
        selectedDate = c.optString("selectedDate"),
        bindVariable = c.optString("bindVariable"),
        datePickerMode = c.optEnum<DatePickerProperties.Mode>("datePickerMode"),
        displayStyle = c.optEnum<DatePickerProperties.DisplayStyle>("displayStyle"),
        minimumDate = c.optString("minimumDate"),
        maximumDate = c.optString("maximumDate"),
        isEnabled = c.optString("isEnabled"),
    )
}

public data class PageControlProperties(
    val numberOfPages: String? = null,
    val currentPage: String? = null,
    val bindVariable: String? = null,
    val hidesForSinglePage: Boolean? = null,
)

internal fun decodePageControlProperties(c: Cursor): PageControlProperties {
    c.obj()
    return PageControlProperties(c.optString("numberOfPages"), c.optString("currentPage"), c.optString("bindVariable"), c.optBool("hidesForSinglePage"))
}

public data class PickerProperties(
    val options: List<Option>? = null,
    val selectedValue: String? = null,
    val bindVariable: String? = null,
    val displayMode: DisplayMode? = null,
    val placeholder: String? = null,
    val isEnabled: String? = null,
) {
    public data class Option(val value: String, val label: String, val icon: String? = null)

    @Suppress("EnumEntryName")
    public enum class DisplayMode { menu, segmented }
}

internal fun decodePickerProperties(c: Cursor): PickerProperties {
    c.obj()
    return PickerProperties(
        options = c.opt("options") { a ->
            a.list { o -> o.obj(); PickerProperties.Option(o.reqString("value"), o.reqString("label"), o.optString("icon")) }
        },
        selectedValue = c.optString("selectedValue"),
        bindVariable = c.optString("bindVariable"),
        displayMode = c.optEnum<PickerProperties.DisplayMode>("displayMode"),
        placeholder = c.optString("placeholder"),
        isEnabled = c.optString("isEnabled"),
    )
}

public data class ShapeProperties(
    val kind: Kind,
    val progress: AnimatedValue<String>? = null,
    val startAngle: Double? = null,
    val lineWidth: Double? = null,
    val lineCap: LineCap? = null,
    val strokeColor: String? = null,
    val trackColor: String? = null,
    val trackLineWidth: Double? = null,
    val animationDuration: Double? = null,
    val animationCurve: String? = null,
    val fillColor: String? = null,
    val points: List<Point>? = null,
    val smooth: Boolean? = null,
    val closed: Boolean? = null,
) {
    @Suppress("EnumEntryName")
    public enum class Kind { arc, bar, circle, line, polyline }

    @Suppress("EnumEntryName")
    public enum class LineCap { round, butt, square }

    /** Point coordinates; literals or expressions. */
    public data class Point(val x: String, val y: String)
}

internal fun decodeShapeProperties(c: Cursor): ShapeProperties {
    c.obj()
    return ShapeProperties(
        kind = c.req("kind") { it.strictEnum<ShapeProperties.Kind>() },
        progress = c.opt("progress", ::decodeAnimatedString),
        startAngle = c.optDouble("startAngle"),
        lineWidth = c.optDouble("lineWidth"),
        lineCap = c.optEnum<ShapeProperties.LineCap>("lineCap"),
        strokeColor = c.optString("strokeColor"),
        trackColor = c.optString("trackColor"),
        trackLineWidth = c.optDouble("trackLineWidth"),
        animationDuration = c.optDouble("animationDuration"),
        animationCurve = c.optString("animationCurve"),
        fillColor = c.optString("fillColor"),
        points = c.opt("points") { a -> a.list { p -> p.obj(); ShapeProperties.Point(p.reqString("x"), p.reqString("y")) } },
        smooth = c.optBool("smooth"),
        closed = c.optBool("closed"),
    )
}

public data class ShimmerProperties(val isAnimating: String? = null, val duration: Double? = null, val direction: Direction? = null) {
    @Suppress("EnumEntryName")
    public enum class Direction { leftToRight, rightToLeft, topToBottom }
}

internal fun decodeShimmerProperties(c: Cursor): ShimmerProperties {
    c.obj()
    return ShimmerProperties(c.optString("isAnimating"), c.optDouble("duration"), c.optEnum<ShimmerProperties.Direction>("direction"))
}

public data class SliderProperties(
    val value: String? = null,
    val minimumValue: Float? = null,
    val maximumValue: Float? = null,
    val step: Float? = null,
    val bindVariable: String? = null,
    val isEnabled: String? = null,
)

internal fun decodeSliderProperties(c: Cursor): SliderProperties {
    c.obj()
    return SliderProperties(
        c.optString("value"), c.optFloat("minimumValue"), c.optFloat("maximumValue"),
        c.optFloat("step"), c.optString("bindVariable"), c.optString("isEnabled"),
    )
}

public data class ToggleProperties(val isOn: String? = null, val bindVariable: String? = null, val isEnabled: String? = null)

internal fun decodeToggleProperties(c: Cursor): ToggleProperties {
    c.obj()
    return ToggleProperties(c.optString("isOn"), c.optString("bindVariable"), c.optString("isEnabled"))
}

@Suppress("EnumEntryName")
public enum class KeyboardType {
    default, asciiCapable, numbersAndPunctuation, URL, numberPad, phonePad, namePhonePad, emailAddress,
    decimalPad, twitter, webSearch, asciiCapableNumberPad,
}

/** Autofill hint. The DSL value `"name"` is [name_] (`name` clashes with `Enum.name`); match on [raw]. */
@Suppress("EnumEntryName")
public enum class TextContentType {
    username, password, newPassword, oneTimeCode,
    emailAddress, telephoneNumber, name_, givenName, familyName, middleName, nickname, namePrefix, nameSuffix,
    addressCity, addressState, addressCityAndState, countryName, fullStreetAddress, streetAddressLine1,
    streetAddressLine2, postalCode, sublocality,
    creditCardNumber, creditCardExpiration, creditCardExpirationMonth, creditCardExpirationYear,
    creditCardSecurityCode, creditCardName, creditCardGivenName, creditCardMiddleName, creditCardFamilyName, creditCardType,
    organizationName, jobTitle, URL, dateTime, birthdate, birthdateDay, birthdateMonth, birthdateYear,
    flightNumber, shipmentTrackingNumber;

    /** The DSL string. */
    public val raw: String get() = name.removeSuffix("_")
}

@Suppress("EnumEntryName")
public enum class ReturnKeyType { default, go, google, join, next, route, search, send, yahoo, done, emergencyCall, `continue` }

@Suppress("EnumEntryName")
public enum class AutocapitalizationType { none, words, sentences, allCharacters }

public data class TextFieldProperties(
    val placeholder: String? = null,
    val text: String? = null,
    val bindVariable: String? = null,
    val keyboardType: KeyboardType? = null,
    val textContentType: TextContentType? = null,
    val returnKeyType: ReturnKeyType? = null,
    val isSecure: Boolean? = null,
    val autocapitalization: AutocapitalizationType? = null,
    val autocorrection: Boolean? = null,
    val maxLength: Int? = null,
    val inputMask: String? = null,
    val allowedCharacters: String? = null,
    val isEnabled: Boolean? = null,
    val clearButtonMode: ClearButtonMode? = null,
) {
    @Suppress("EnumEntryName")
    public enum class ClearButtonMode { never, whileEditing, unlessEditing, always }
}

internal fun decodeTextFieldProperties(c: Cursor): TextFieldProperties {
    c.obj()
    return TextFieldProperties(
        placeholder = c.optString("placeholder"),
        text = c.optString("text"),
        bindVariable = c.optString("bindVariable"),
        keyboardType = c.optEnum<KeyboardType>("keyboardType"),
        textContentType = c.optEnum<TextContentType>("textContentType") { it.raw },
        returnKeyType = c.optEnum<ReturnKeyType>("returnKeyType"),
        isSecure = c.optBool("isSecure"),
        autocapitalization = c.optEnum<AutocapitalizationType>("autocapitalization"),
        autocorrection = c.optBool("autocorrection"),
        maxLength = c.optInt("maxLength"),
        inputMask = c.optString("inputMask"),
        allowedCharacters = c.optString("allowedCharacters"),
        isEnabled = c.optBool("isEnabled"),
        clearButtonMode = c.optEnum<TextFieldProperties.ClearButtonMode>("clearButtonMode"),
    )
}

public data class TextViewProperties(
    val placeholder: String? = null,
    val text: String? = null,
    val bindVariable: String? = null,
    val keyboardType: KeyboardType? = null,
    val textContentType: TextContentType? = null,
    val returnKeyType: ReturnKeyType? = null,
    val autocapitalization: AutocapitalizationType? = null,
    val autocorrection: Boolean? = null,
    val maxLength: Int? = null,
    val isEnabled: Boolean? = null,
    val scrollEnabled: Boolean? = null,
    val autoGrow: Boolean? = null,
    val maxHeight: Double? = null,
    val minHeight: Double? = null,
)

internal fun decodeTextViewProperties(c: Cursor): TextViewProperties {
    c.obj()
    return TextViewProperties(
        placeholder = c.optString("placeholder"),
        text = c.optString("text"),
        bindVariable = c.optString("bindVariable"),
        keyboardType = c.optEnum<KeyboardType>("keyboardType"),
        textContentType = c.optEnum<TextContentType>("textContentType") { it.raw },
        returnKeyType = c.optEnum<ReturnKeyType>("returnKeyType"),
        autocapitalization = c.optEnum<AutocapitalizationType>("autocapitalization"),
        autocorrection = c.optBool("autocorrection"),
        maxLength = c.optInt("maxLength"),
        isEnabled = c.optBool("isEnabled"),
        scrollEnabled = c.optBool("scrollEnabled"),
        autoGrow = c.optBool("autoGrow"),
        maxHeight = c.optDouble("maxHeight"),
        minHeight = c.optDouble("minHeight"),
    )
}

public data class VideoProperties(
    val type: Type,
    val model: Model,
    val autoplay: Boolean = true,
    val loop: Boolean = true,
    val muted: Boolean = true,
    val audioMix: AudioMix = AudioMix.auto,
    val endBehavior: EndBehavior = EndBehavior.freezeLastFrame,
    val poster: PosterSource? = null,
    val endPoster: PosterSource? = null,
    val startDelay: Double? = null,
    val startTime: Double? = null,
    val rate: Float? = null,
    val marks: List<Mark>? = null,
) {
    public val loops: Boolean get() = loop || endBehavior == EndBehavior.loop

    @Suppress("EnumEntryName")
    public enum class Type { localAsset, remoteAsset, none }

    public sealed interface Model {
        public data class LocalAsset(val name: String) : Model
        public data class RemoteAsset(val asset: Asset) : Model
        public data object None : Model
    }

    @Suppress("EnumEntryName")
    public enum class EndBehavior { freezeLastFrame, loop, hidePoster, showPoster }

    @Suppress("EnumEntryName")
    public enum class AudioMix { auto, mix, duck, interrupt }

    public data class PosterSource(
        val kind: Kind,
        val name: String? = null,
        val id: String? = null,
        val url: String? = null,
        val time: Double? = null,
    ) {
        @Suppress("EnumEntryName")
        public enum class Kind { firstFrame, localAsset, remoteAsset, url, frameAtTime }

        public val remoteAsset: Asset?
            get() = if (kind == Kind.remoteAsset || kind == Kind.url) Asset(id, name, url) else null
    }

    public data class Mark(val id: String, val time: Double)
}

internal fun decodeVideoProperties(c: Cursor): VideoProperties {
    c.obj()
    val type = c.req("type") { it.strictEnum<VideoProperties.Type>() }
    val model = when (type) {
        VideoProperties.Type.localAsset -> VideoProperties.Model.LocalAsset(c.reqString("name"))
        VideoProperties.Type.remoteAsset -> VideoProperties.Model.RemoteAsset(decodeAsset(c))
        VideoProperties.Type.none -> VideoProperties.Model.None
    }
    return VideoProperties(
        type = type,
        model = model,
        autoplay = c.optBool("autoplay") ?: true,
        loop = c.optBool("loop") ?: true,
        muted = c.optBool("muted") ?: true,
        audioMix = c.optEnum<VideoProperties.AudioMix>("audioMix") ?: VideoProperties.AudioMix.auto,
        endBehavior = c.optEnum<VideoProperties.EndBehavior>("endBehavior") ?: VideoProperties.EndBehavior.freezeLastFrame,
        poster = c.opt("poster", ::decodePosterSource),
        endPoster = c.opt("endPoster", ::decodePosterSource),
        startDelay = c.optDouble("startDelay"),
        startTime = c.optDouble("startTime"),
        rate = c.optFloat("rate"),
        marks = c.opt("marks") { a -> a.list { m -> m.obj(); VideoProperties.Mark(m.reqString("id"), m.reqDouble("time")) } },
    )
}

private fun decodePosterSource(c: Cursor): VideoProperties.PosterSource {
    c.obj()
    return VideoProperties.PosterSource(
        kind = c.req("type") { it.strictEnum<VideoProperties.PosterSource.Kind>() },
        name = c.optString("name"),
        id = c.optString("id"),
        url = c.optString("url"),
        time = c.optDouble("time"),
    )
}
