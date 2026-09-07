# Zerifin contributor guidance

- Read README.md and docs/building.md before changing the project.
- Preserve existing work and inspect Git status before edits.
- Keep the Zerifin application label and green branding. Public release builds use
  `io.github.sommepo.zerifin`; debug builds add `.debug`. Preserve these IDs and the public release
  signing identity unless a migration is explicitly requested. Keep the upstream Kotlin namespace.
- Reuse the native player, interactive subtitle renderer, dictionary repository and Anki gateway.
- Preserve upstream licenses, copyrights and dependency acknowledgements.
- Do not commit credentials, signing keys, APKs, dictionaries, app data, runtime caches or device logs.
- Use original implementations and small permitted fixtures; do not copy other applications' source or assets.
- Keep companion changes limited to the trusted-network YouTube adapter.
- Run checks appropriate to the change, then the documented build gate for implementation changes.
  Note the existing nonfatal lint/detekt findings rather than claiming a warning-free build.
