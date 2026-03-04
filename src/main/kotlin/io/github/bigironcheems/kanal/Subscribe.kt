package io.github.bigironcheems.kanal

/**
 * Marks a method as an event handler to be discovered by an [EventBus].
 *
 * A valid handler must accept exactly one parameter whose type implements [Event],
 * return `Unit` / `void`, and be an instance method (for [EventBus.subscribe]) or
 * a static / `@JvmStatic` method (for [EventBus.subscribeStatic]).
 *
 * If a subclass overrides a handler without re-annotating it, the override is not
 * dispatched. Always re-annotate overrides:
 * ```kotlin
 * class Sub : Base() {
 *     @Subscribe
 *     override fun on(e: MyEvent) { super.on(e) }
 * }
 * ```
 *
 * @param priority Dispatch priority; higher values fire first. Use [Priority] constants or
 *                 any integer. Defaults to [Priority.NORMAL].
 * @param async    If `true`, dispatches this handler on the bus's configured executor.
 *                 Falls back to synchronous execution if no executor is configured.
 *                 Defaults to `false`.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Subscribe(val priority: Int = Priority.NORMAL, val async: Boolean = false)
