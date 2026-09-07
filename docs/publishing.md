# Publishing the Zerifin fork

Suggested repository name: **zerifin**

Suggested description: **Japanese subtitle lookup, dictionaries and multimedia Anki mining for Jellyfin and YouTube on Android.**

Suggested topics: `android`, `jellyfin`, `japanese`, `language-learning`, `ankidroid`, `sentence-mining`, `yomitan`, `youtube`.

## Publication branch

`codex/public-fork` is the prepared source branch. It contains the complete feature snapshot and
public documentation on top of upstream commit `779294e9b5ac5a3fb1da3d7e3271df4f021aaa0e`.
Upstream history and licensing are preserved. Local development handoffs, host-specific scripts,
device audits and runtime data are excluded from the branch and its new history.

Create a **GitHub fork of `jellyfin/jellyfin-android`**, named `zerifin`, so GitHub retains the upstream
relationship. The fork can be created through the web UI or [GitHub CLI](https://cli.github.com/manual/gh_repo_fork):

```sh
gh repo fork jellyfin/jellyfin-android --fork-name zerifin --clone=false --remote=false
```

After checking that it is the correct owner/repository, add a publication remote in your prepared
checkout. Replace `OWNER` below with the GitHub account or organization that owns the fork:

```sh
git remote add public https://github.com/OWNER/zerifin.git
git push public codex/public-fork:main
```

Push only the prepared branch. Do not use `--mirror`, `--all` or `--tags` from a development repository:
other local branches may contain internal handoffs, and inherited upstream release tags are not
Zerifin releases. This procedure creates a new `main` branch and needs no force push.

Set `main` as the fork's default branch. Set the description/topics above, enable Issues and private
vulnerability reporting, and enable the supplied GitHub Actions workflow. Review the public README
and source tree in GitHub before announcing the fork.

Fork documentation: https://docs.github.com/en/pull-requests/reference/forks

## Before distributing releases

The repository is prepared for source publication and unsigned build artifacts. It does not create
a public repository or publish a release by itself. CI has read-only repository permissions and
contains no upstream deployment jobs or signing secrets.

Choose and securely retain a release signing identity, decide the version, and test updates before
publishing installable releases. The existing IDs are deliberately preserved; a debug build from
an unrelated signing key cannot update an existing debug installation. See [building.md](building.md).
The green application identity and APK archive name are Zerifin; technical upstream namespaces
and dependency coordinates remain unchanged.

The current prototype has automated tests and physical-device verification of playback, seeking,
lookup and media/card preparation. Complete a real Anki add with a test collection and verify
normal Jellyfin playback when qualifying a release. Keep those test notes and private device logs
out of the public repository.
