# Compose Multiplatform desktop font reproductions

Two self-contained reproductions for font resolution on skiko targets. Each is an independent
Gradle build; run `./gradlew test` inside the directory. In both cases the failing test **is** the
bug being reported.

| directory | reproduces |
|---|---|
| [`default-font-pinning/`](default-font-pinning/) | `FontFamily.Default` cannot be pinned to a multi-weight family through supported API — only the first face registered under an alias survives |
| [`sktextstyle-cache/`](sktextstyle-cache/) | `SkTextStyle` is cached without the `FontFamily.Resolver`, so one resolver can render with another resolver's font |

Each directory's README explains what the run shows and cites the lines involved.

Measured on macOS 26.3 / arm64, JBR 21.0.8, Compose Multiplatform 1.13.0-alpha01, Kotlin 2.3.21.

The bundled `Roboto-Regular.ttf` and `Roboto-Bold.ttf` are from the Roboto project and are
licensed under the Apache License 2.0.
