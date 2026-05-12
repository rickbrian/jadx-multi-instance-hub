# Changelog

## v1.1.0 (2026-05-12)

### New Features
- **`search_dex_strings`** — Instant string search via DEX bytecode scanning (`const-string` instruction parsing). Returns class+method locations with zero decompilation (~180ms for 46k classes)
- **`decompile_status`** — Monitor background decompilation progress (progress/total/percent/eta)
- **Background decompilation** — Auto-starts after `load_apk`, single low-priority daemon thread, main package classes first. After completion all search/source calls become instant cache hits
- **CLI `--test` mode** — `--bg-decompile` to watch background progress, `--full-decompile` for benchmarking

### Optimizations
- **`search_string`** — DEX pool pre-check, auto package filter from manifest, timeout (default 30s), maxResults pagination (default 50)
- After background decompile: full 46k class search in <500ms (was 5+ minutes)

### Performance (MobiKwik 46k classes)

| Operation | Before | After |
|-----------|--------|-------|
| `search_dex_strings` | N/A | **180ms** |
| `search_string` (auto filter) | 5+ min | **276ms** |
| `search_string` (all 46k) | 5+ min | **422ms** |
| String not in APK | 5+ min | **0ms** |

### Safety & Stability
- DEX parser bounds checks with `long` overflow protection
- ULEB128 shift limit + position bounds check
- `awaitTermination(10s)` on background thread shutdown
- `InputStream` leak fix in `getResourceFile`
- `@PreDestroy` cleanup for `InstanceManager`

## v1.0.0

- Initial release: multi-instance JADX MCP server
- 18 MCP tools with target routing
- ClassNode-based method indexing (no decompilation at load time)
