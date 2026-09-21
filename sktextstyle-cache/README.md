# `SkTextStyle` cached across `FontFamily.Resolver`s

Reproduction for the CMP issue: `skTextStylesCache` is keyed on `ComputedStyle.Immutable`
alone, so the first resolver to build a given computed style bakes its alias list into an
entry that every later paragraph with an equal style reuses — whichever resolver the
composition actually provides.

## Run

```
./gradlew test
```

One of the three tests fails. That failure is the bug.

```
run 1 -- same TextStyle, stock renders first
  stock  rendered 277 px (host OS system font)
  pinned rendered 277 px (host OS system font)     <- should be 249
SameStyleTest FAILED

run 2 -- TextStyles differ in `background` only, so the cache keys differ
  pinned rendered 249 px (Roboto)
  stock  rendered 277 px (host OS system font)
DifferentKeyTest PASSED

run 3 -- same TextStyle, System.gc() between the two renders
  stock  rendered 277 px (host OS system font)
  pinned rendered 249 px (Roboto)
ForcedGcTest PASSED
```

Measured on macOS 26.3 / arm64, JBR 21.0.8, CMP 1.13.0-alpha01. The absolute widths depend on
the host OS system font; the assertion does not use them. It only requires that two resolvers
using two different fonts do not measure the same, which holds on any OS.

## What each run shows

| run | setup | shows |
|---|---|---|
| 1 | both scenes use the same `TextStyle` | the second render reuses the first resolver's entry |
| 2 | the two `TextStyle`s differ in one irrelevant field (`background`) | both resolvers are correct as soon as the keys differ |
| 3 | same `TextStyle`, `System.gc()` between the renders | the weak key is collected, so the bug disappears |

`System.gc()` is a request, not a guarantee. If the key is not collected on your JVM, run 3 fails
in the same way run 1 does. That is the same bug, not a broken reproduction — it is the point of
the run.

Run 2 is the diagnosis: `background` cannot affect glyph advances, but it does change
`ComputedStyle` and therefore the cache key. Run 3 is why this presents as a flaky test rather
than as a stable wrong answer — `WeakKeysCache` is a `java.util.WeakHashMap` on the JVM
(`ui-skiko/desktopMain/androidx/compose/ui/text/Cache.jvm.kt:21`), so an entry survives only
until its key is collected.

## Why `forkEvery = 1`

`skTextStylesCache` is a file-level `private val`, so it is process-global. Without the fork,
the first scenario to run would poison the other two.

## Why this uses `@InternalComposeUiApi`

Reaching the bug needs two resolvers that return **different aliases** for the same
`FontFamily`. Two `createFontFamilyResolver()` instances never do: they return the same generic
aliases and differ only in what each has registered inside its own `FontCollection`, which each
paragraph re-resolves for itself. Since `FontFamily.Resolver` is `sealed` and
`createFontFamilyResolver()` is the only public way to obtain one, no purely public-API program
can reach this today, and the reproduction has to pin `FontFamily.Default` through the
`PlatformTypefacesLoader` seam.

That seam is itself the subject of a separate issue. This one is independent of it: the cache
would still be missing the resolver from its key if the pinning had a supported entry point.
