package io.github.bigironcheems.kanal

/**
 * Optional interface for events that carry a mutable result value.
 *
 * Any handler in the dispatch chain can read or replace [value]. Handlers fire in
 * descending priority order, so a high-priority handler sets an initial result that
 * lower-priority handlers may refine.
 *
 * ```kotlin
 * class DamageEvent(override var value: Double) : Event, Modifiable<Double>
 *
 * bus.subscribe<DamageEvent> { e -> e.value *= 0.5 }
 * val result = bus.post(DamageEvent(10.0)).value  // 5.0
 * ```
 *
 * Composable with [Cancellable]:
 * ```kotlin
 * class ItemDropEvent(
 *     override var value: Int,
 *     override var isCancelled: Boolean = false,
 * ) : Event, Modifiable<Int>, Cancellable
 * ```
 *
 * Java: `var value: T` compiles to `getValue()` / `setValue(T)`. Primitives are boxed due to
 * the generic parameter; use wrapper types (`Double`, `Integer`, etc.):
 * ```java
 * public class DamageEvent implements Event, Modifiable<Double> {
 *     private double value;
 *     public DamageEvent(double value) { this.value = value; }
 *     @Override public Double getValue() { return value; }
 *     @Override public void setValue(Double v) { this.value = v; }
 * }
 * bus.subscribe(DamageEvent.class, Priority.NORMAL, e -> e.setValue(e.getValue() * 0.5));
 * ```
 *
 * If boxing overhead matters on a hot path, extend a typed Kotlin base class so Java
 * subclasses inherit concrete (non-generic) accessors:
 * ```kotlin
 * abstract class DoubleEvent(override var value: Double) : Event, Modifiable<Double>
 * ```
 *
 * @param T The type of the mutable value carried by this event.
 */
public interface Modifiable<T> {
    /** The current value. Any handler may read or replace this during dispatch. */
    public var value: T
}
