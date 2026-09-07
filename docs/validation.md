# Publication validation

The initial Zerifin publication snapshot was checked on 2026-09-07.

- Full Gradle gate and both Libre debug/release assemblies completed successfully.
- 100 Libre and 100 Proprietary unit tests passed, with zero failures or errors.
- 19 companion tests and four web-menu tests passed.
- Release output is an unsigned `zerifin-…-libre-release-unsigned.apk`, matching the CI artifact rule.
- Public documentation links, workflow/issue YAML, shell syntax and staged whitespace checks passed.
- The source scan found no known credentials or local development identifiers in publication content.
- The upstream license is byte-for-byte unchanged. The publication branch retains upstream ancestry
  and excludes the local development commits containing host/device handoffs.

The configured lint/detekt checks are nonfatal and still report findings, including translation and
dependency diagnostics. These results do not establish a warning-free build. GitHub-hosted CI has
not run before the repository is published; its build/test commands were exercised locally.

Earlier device checks covered Jellyfin mining plus public YouTube decoding, caption seeking,
English alignment, dictionary lookup, pronunciation, sentence audio and card-field preparation.
The publication change preserves that app implementation, apart from archive naming and whitespace.
A stable public release still needs signing/update qualification and a real Anki add using a test
collection. See [publishing.md](publishing.md).
