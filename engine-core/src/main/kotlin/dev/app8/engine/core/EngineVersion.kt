package dev.app8.engine.core

/** The SDK release. Equal on iOS and Android; must match the git tag. */
public object EngineVersion {
    public const val CURRENT: String = "0.4.3"

    /** Newest DSL version this engine renders (app8-dsl `DSL_VERSION`). */
    public const val MAX_SUPPORTED_DSL: String = "1.1"
}
