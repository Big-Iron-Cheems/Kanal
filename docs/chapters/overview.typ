#import "../theme.typ": *

= Overview <overview>

Kanal is a Kotlin-first, Java-compatible event-handler library. Its purpose is to decouple
producers from consumers: a component that raises an event does not need to know who, if
anyone, is listening. Consumers register handlers and receive events in a controlled order
without any coupling to the producer.

The library targets JDK 25 and Kotlin 2.x. Java callers have complete access to the same
API through dedicated overloads; no feature requires Kotlin.

== Core concepts <overview-concepts>

An *event* is any class that implements the `Event` marker interface. No base class is
required; there is no built-in event registry. Any type hierarchy works: plain classes,
data classes, sealed hierarchies, records from Java.

A *handler* is a method or lambda that accepts an event. Handlers are registered on an
`EventBus` instance. Each handler carries a *priority* (an integer; higher fires first)
and an optional *async* flag.

A *subscription* is a token returned by lambda-based registration. Calling `cancel()` on it
removes the handler from the bus. Annotation-based handlers are removed by passing the
subscriber object back to `unsubscribe()`.

A *dispatch* is the act of posting an event to a bus. The bus collects all handlers
whose declared type is assignable from the event's runtime type, sorts them by priority,
and invokes them in order. The posted event instance is returned from `post()`.

== Interfaces at a glance <overview-interfaces>

#table(
  columns: (auto, 1fr),
  stroke: 0.5pt + luma(200),
  inset: (x: 0.8em, y: 0.55em),
  fill: (_, row) => if calc.odd(row) { luma(248) } else { white },
  table.header(
    [*Type*], [*Role*],
  ),
  [`Event`],        [Marker. Every dispatchable type implements this.],
  [`EventBus`],     [Primary API. Registers handlers, posts events, manages lifecycle. See @spec-post.],
  [`TypedEventBus<E>`], [Compile-time-restricted view. `post` and `subscribe` only accept subtypes of `E`. See @spec-typed.],
  [`Cancellable`],  [Optional. Events that can halt further dispatch mid-chain. See @spec-cancellable.],
  [`Modifiable<T>`],[Optional. Events carrying a mutable typed result that handlers may replace. See @spec-modifiable.],
  [`Subscription`], [Token returned by lambda registration. `cancel()` removes the handler. See @spec-registration.],
  [`Subscribe`],    [Annotation that marks a method as a handler. Carries `priority` and `async`. See @spec-registration.],
  [`Priority`],     [Object holding five predefined integer constants: HIGHEST through LOWEST. See @spec-dispatch.],
)

== What Kanal is not <overview-not>

Kanal does not provide:

- Persistent or durable event storage.
- Network or inter-process delivery.
- Event sourcing or replay.
- A built-in scheduler or periodic dispatch.

It is a *within-process, in-memory* dispatch mechanism. Extensions such as
`kanal-coroutines` layer additional dispatch strategies on top of the same core.

== Package layout <overview-packages>

All public API lives under `io.github.bigironcheems.kanal`. Internal
implementation classes live in the `internal` subpackage and are not part of the
public API surface (enforced by the binary compatibility check).

The `kanal-coroutines` module adds Kotlin coroutine integration and depends on
the core `kanal` module.
