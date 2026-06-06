# Kanal

[![CI][badge-ci]][link-ci]
[![Kotlin][badge-kotlin]][link-kotlin]
[![JVM][badge-jvm]][link-jvm]
[![Maven][badge-maven]][link-maven]
[![License][badge-license]][link-license]

A **Kotlin-first, Java-compatible** event-handler library for the JVM.

## Modules

| Module                                           | Description                                                                                                                                  | Java compatible |
|--------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|-----------------|
| [`kanal-core`](kanal-core/README.md)             | Core event bus - annotation and lambda subscribers, priority dispatch, cancellable and modifiable events, supertype dispatch, async handlers | Yes             |
| [`kanal-coroutines`](kanal-coroutines/README.md) | Coroutines extensions - `asFlow`, `postSuspend`, `suspendHandler`, `nextEvent`                                                               | Kotlin only     |

## Installation

### kanal-core

```kotlin
repositories {
    maven("https://maven.meteordev.org/releases") {
        name = "meteordev"
    }
}

dependencies {
    implementation("io.github.big-iron-cheems:kanal-core:$VERSION")
}
```

### kanal-coroutines

Depends on `kanal-core` automatically via `api()`.

```kotlin
dependencies {
    implementation("io.github.big-iron-cheems:kanal-coroutines:$VERSION")
}
```

Replace the placeholder `$VERSION` with the latest version shown in the badge above.

## Quick example

```kotlin
class PlayerJumpEvent(val player: String) : Event

val bus = EventBus()
bus.subscribe<PlayerJumpEvent> { e -> println("${e.player} jumped!") }
bus.post(PlayerJumpEvent("Steve"))
```

See the module READMEs for the full API surface, Java usage, async dispatch, coroutines integration, and performance
data.

## Documentation

The project specification is written in [Typst](https://github.com/typst/typst) under [`docs/`](docs).
To build the PDF locally, install Typst and run:

```bash
typst compile docs/kanal.typ docs/kanal.pdf
```

## License

Apache 2.0, see [LICENSE](LICENSE).

[//]: # (Badge definitions)
[badge-ci]: https://github.com/Big-Iron-Cheems/Kanal/actions/workflows/ci.yml/badge.svg
[badge-kotlin]: https://img.shields.io/badge/dynamic/toml?url=https://raw.githubusercontent.com/Big-Iron-Cheems/Kanal/main/gradle/libs.versions.toml&query=versions.kotlin&label=kotlin&color=7F52FF&logo=kotlin&logoColor=white
[badge-jvm]: https://img.shields.io/badge/dynamic/toml?url=https://raw.githubusercontent.com/Big-Iron-Cheems/Kanal/main/gradle/libs.versions.toml&query=versions.jvm&label=JVM&color=orange&logo=openjdk&logoColor=white
[badge-maven]: https://img.shields.io/badge/dynamic/toml?url=https://raw.githubusercontent.com/Big-Iron-Cheems/Kanal/main/gradle/libs.versions.toml&query=versions.project&label=maven&color=blue&logo=apachemaven&logoColor=white
[badge-license]: https://img.shields.io/github/license/Big-Iron-Cheems/Kanal?logo=apache&logoColor=white

[//]: # (Link definitions)
[link-ci]: https://github.com/Big-Iron-Cheems/Kanal/actions/workflows/ci.yml
[link-kotlin]: https://kotlinlang.org
[link-jvm]: https://openjdk.org/projects/jdk/25/
[link-maven]: https://maven.meteordev.org/#/releases/io/github/big-iron-cheems/Kanal
[link-license]: https://github.com/Big-Iron-Cheems/Kanal/blob/main/LICENSE
