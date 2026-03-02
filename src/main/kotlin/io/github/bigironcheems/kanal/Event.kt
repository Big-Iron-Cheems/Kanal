package io.github.bigironcheems.kanal

/**
 * Marker interface for all events that can be dispatched through an [EventBus].
 *
 * Any class implementing this interface can be posted to the bus and received
 * by methods annotated with [Subscribe]. The interface imposes no behaviour
 * of its own; it simply identifies a type as a participant in the event system.
 *
 * Events may additionally implement [Cancellable] to support early-termination
 * of handler dispatch.
 */
public interface Event
