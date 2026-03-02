package io.github.bigironcheems.kanal

/**
 * Marks a method as an event handler to be discovered by an [EventBus].
 *
 * A valid handler method must:
 * - Accept exactly one parameter whose type implements [Event].
 * - Return `Unit` / `void`.
 *
 * Handlers may be instance methods (registered via [EventBus.subscribe]) or
 * static / `@JvmStatic` methods (registered via [EventBus.subscribeStatic]).
 *
 * **Override behaviour:** if a subclass overrides a handler method without
 * re-annotating it, the override shadows the base class handler and will not
 * be dispatched. Always re-annotate overrides:
 * ```kotlin
 * class Sub : Base() {
 *     @Subscribe
 *     override fun on(e: MyEvent) { super.on(e) }
 * }
 * ```
 *
 * @param priority Dispatch priority. Use [Priority] constants or any integer. Defaults to [Priority.NORMAL].
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Subscribe(val priority: Int = Priority.NORMAL)
