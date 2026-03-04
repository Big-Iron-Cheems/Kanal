package io.github.bigironcheems.kanal

/**
 * Marker interface for all events that can be dispatched through an [EventBus].
 *
 * Implement this interface on any class you want to post and receive via [@Subscribe][Subscribe].
 * The interface imposes no behaviour; it only identifies a type as a bus participant.
 * Optionally combine with [Cancellable] or [Modifiable] for richer dispatch semantics.
 */
public interface Event
