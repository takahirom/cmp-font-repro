# Pinning `FontFamily.Default` through supported API

Reproduction for the CMP issue about pinning `FontFamily.Default` on skiko targets.

**No `@InternalComposeUiApi` is used anywhere in this project.** That is the point: this is how
far the supported API gets, and it is not far enough for a multi-weight family.

## Run

```
./gradlew test
```

The single test fails on its second assertion.

```
alias registered under        : .AppleSystemUIFont
Roboto, addressed explicitly  : regular=245 bold=249
FontFamily.Default, before pin: regular=257 bold=277
FontFamily.Default, after pin : regular=245 bold=245
                                               ^^^ should be 249
```

Measured on macOS 26.3 / arm64, JBR 21.0.8, CMP 1.13.0-alpha01.

## What it does

`FontCache.ensureRegistered(fontFamily)` starts with
`if (fontFamily == FontFamily.Default) return FontFamily.SansSerif.aliases`
(`ui-skiko/nonAndroidMain/androidx/compose/ui/text/platform/PlatformFont.nonAndroid.kt:255`), so
registering a typeface under the host OS's first sans-serif alias does redirect
`FontFamily.Default`. The supported way to register one is the public
`androidx.compose.ui.text.platform.Typeface(SkTypeface, alias)` (`:175`) wrapped in a
`FontFamily`, which reaches `ensureRegistered(nativeTypeface, alias)` when it is resolved
(`:271-274`).

Registering Roboto Regular that way works: `FontFamily.Default` at `FontWeight.Normal` goes from
257 px to 245 px, Roboto Regular's width.

Registering Roboto Bold under the same alias afterwards does nothing. `FontCache.registered` is a
`MutableSet<String>` keyed on the alias alone (`:215`) and the guard is
`if (!registered.contains(key))` (`:244`), so the second face is dropped without an error.
`FontFamily.Default` at `FontWeight.Bold` then comes out at 245 px — the Regular face's advance,
emboldened by Skia — instead of Roboto Bold's 249 px.

## Two things worth knowing if you try this by hand

**The alias name is not obtainable from public API.** `GenericFontFamiliesMapping` (same file,
`:288`) is `private`, so the only way to learn what to register under is to copy the table, which
is what `sansSerifAlias` in the test is. A copy will drift. Only the macOS branch of that copy has
been measured here; the Windows and Linux names are transcribed from the source.

**The pin has to be installed before the resolver has resolved `FontFamily.Default` even once.**
`FontFamily.Default` resolves through `GlobalTypefaceRequestCache`, an
`LruCache<TypefaceRequest, TypefaceResult>(16)`
(`ui-text/commonMain/androidx/compose/ui/text/font/FontFamilyResolver.kt:134`, `:170`).
`TypefaceRequest` carries `resourceLoaderCacheKey` (`:151`), which is the loader's `cacheKey`, so
entries are not shared between two `createFontFamilyResolver()` instances — that is why this test
can measure its baseline on a separate resolver without disturbing the pinned one.

What the cache holds is a `FontLoadResult(typeface, aliases)`. Registration does not evict it: it
calls `fonts.paragraphCache.reset()` and nothing else (`PlatformFont.nonAndroid.kt:244-248`). The
`aliases` are identical before and after registration — they are `FontFamily.SansSerif.aliases`
either way — so the entry looks unchanged while the `typeface` it carries is still the host OS
font, and `toSkTextStyle` assigns that typeface directly. A resolver that has already answered
`FontFamily.Default` therefore keeps answering with the system font.

Registering at start-up, before the first `Text`, avoids this.

## The remaining workaround

An explicit `FontListFontFamily` everywhere. In Material 3 that means copying every `Typography`
text style, because `Typography` has no `defaultFontFamily` parameter.
