package io.github.bigironcheems.kanal

/**
 * Predefined priority constants for use with [@Subscribe][Subscribe] and lambda subscribe overloads.
 *
 * Higher values fire first. Handlers with equal priority fire in registration order.
 * Any integer value is valid, not just the constants defined here:
 * ```kotlin
 * @Subscribe(priority = 50)
 * fun onEvent(e: MyEvent) { ... }
 * ```
 */
public object Priority {
    /** Highest predefined priority (200). Fires before all other predefined levels. */
    public const val HIGHEST: Int = 200

    /** High priority (100). */
    public const val HIGH: Int = 100

    /** Normal priority (0). The default when no priority is specified. */
    public const val NORMAL: Int = 0

    /** Low priority (-100). */
    public const val LOW: Int = -100

    /** Lowest predefined priority (-200). Fires after all other predefined levels. */
    public const val LOWEST: Int = -200
}
