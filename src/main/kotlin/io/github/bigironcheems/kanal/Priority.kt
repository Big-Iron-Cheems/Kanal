package io.github.bigironcheems.kanal

/**
 * Predefined priority constants for use with [@Subscribe][Subscribe].
 *
 * Higher values fire first. Handlers with equal priority fire in subscription order.
 *
 * Custom integer values are also valid:
 * ```kotlin
 * @Subscribe(priority = 50)
 * fun onEvent(e: MyEvent) { ... }
 * ```
 */
public object Priority {
    public const val HIGHEST: Int = 200
    public const val HIGH: Int = 100
    public const val NORMAL: Int = 0
    public const val LOW: Int = -100
    public const val LOWEST: Int = -200
}
