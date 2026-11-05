package dev.app8.engine.render.collection

import dev.app8.engine.core.model.TableViewProperties

/**
 * `UITableView` style metrics in points, measured on the iOS 26 renders (iPhone 17,
 * `app8-conformance/out/ios`): inset card margins, corner radius, the gap above a section
 * with and without a header title, and the header title's placement.
 */
internal object TablePlan {
    data class Metrics(
        val margin: Double,
        val radius: Double,
        val sectionGap: Double,
        val titledGap: Double,
        val textInset: Double,
        val headerSize: Float,
        val headerBottom: Double,
        val bottomPadding: Double,
    )

    fun metrics(style: TableViewProperties.TableStyle): Metrics = when (style) {
        TableViewProperties.TableStyle.insetGrouped -> Metrics(20.0, 26.0, 35.0, 56.0, 16.0, 17f, 8.0, 35.0)
        TableViewProperties.TableStyle.grouped -> Metrics(0.0, 0.0, 35.0, 56.0, 20.0, 17f, 8.0, 35.0)
        TableViewProperties.TableStyle.plain -> Metrics(0.0, 0.0, 0.0, 28.0, 20.0, 15f, 4.0, 0.0)
    }
}
