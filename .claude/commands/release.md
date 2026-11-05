---
description: Cut a release of the App8 Android SDK on the shared release train — verify the version against app8-dsl and iOS, tag main, and let the Release workflow publish to GitHub Packages and create the GitHub release.
argument-hint: "[<version, e.g. 0.4.0>]  (optional — must equal EngineVersion.CURRENT; otherwise read from it)"
---

# /release — App8 Android SDK release cutter

You are cutting a release of the open-source `appeight/app8-android-sdk`
library. A release is an annotated **git tag** at `main`'s HEAD, pushed to
`origin`. The tag push starts `.github/workflows/release.yml`, which verifies
the version, runs the tests, publishes `dev.app8:engine-core`, `dev.app8:engine`
and `dev.app8:engine-maps` to GitHub Packages (`./gradlew publish`), and creates
the **GitHub release** with the tag message as its notes. Once the workflow is
green, client apps can bump their dependency.

Follow this runbook **step by step**. Tagging is outward-facing and
irreversible in practice (a published Maven version cannot be republished), so
every outward step is gated by an explicit `AskUserQuestion` confirmation.

Argument: `$ARGUMENTS` — an optional version (e.g. `0.4.0`). It is only a
cross-check: the version is fixed by the release train (below), never computed
from commits here.

This repo is public and early-stage: a maintainer cutting their own release is
fine. See **Graduation** at the bottom.

---

## The release train (version-sync rule)

`app8-dsl`, `app8-ios-sdk` and `app8-android-sdk` (and the two cloud SDKs)
release together under **one version** (`app8-dsl/VERSIONING.md`): Android
`X.Y.Z` renders exactly what iOS `X.Y.Z` renders.

- **Source of truth: the `app8-dsl` tag.** The train version is the app8-dsl
  tag being shipped. app8-dsl is tagged first.
- **In this repo** the version lives in `EngineVersion.CURRENT`
  (`engine-core/src/main/kotlin/dev/app8/engine/core/EngineVersion.kt`).
  Gradle reads it for the Maven version; `dsl.lock` names the same app8-dsl
  tag. Both are bumped together in one commit, landed via `/land` **before**
  `/release`. `/release` never edits them; it only reads and verifies.
- **On iOS** the same version is `EngineVersion.current`
  (`Sources/App8Engine/Core/EngineVersion.swift`) and the `app8-ios-sdk` tag.
  Cut both SDKs together: the iOS tag must exist already, or be cut in the
  same session (Stage B.4).
- Tags are SemVer with **no `v` prefix** (`0.4.0`), matching iOS.

---

## Asking the user

**Every** question to the user in this runbook — without exception — goes
through the `AskUserQuestion` tool. **Never** end a message with a free-form
prompt that waits for the user to type "go", "yes", "ok", or an edit (e.g.
"Reply with changes, or 'go'." is forbidden). If you need an answer, ask with
the tool; if you don't need one, don't stop.

Gates in this runbook:

- **B.4 iOS twin** — `cut iOS now` / `Android first` / `abort` (only when the
  iOS tag does not exist yet)
- **B.5 version confirm** — confirm the train version
- **C.1 release notes** — confirm the drafted notes
- **D.1 final release gate** — `create` / `hold` / `abort`
- **E.4 workflow failure** — `rerun` / `stop` (only when the Release workflow fails)

For the drafted **release notes**: draft them, print the full draft in the
message body so it renders as readable markdown, then call `AskUserQuestion` —
first option **"Use as-is"**, and the user picks **"Other"** to supply an edited
version. The `AskUserQuestion` tool is the **only** way this runbook pauses for
input.

---

## Stage 0 — Preflight

Run in parallel and stop on any failure:

1. `gh auth status` — if not logged in, **stop** and tell the user to run
   `! gh auth login` in the prompt, then re-invoke `/release`.
2. `git remote get-url origin` — confirm it is `appeight/app8-android-sdk`. If
   the remote is a fork or something else, stop and ask the user.
3. `gh repo view --json defaultBranchRef -q .defaultBranchRef.name` — record the
   **default branch** (expected: `main`).

---

## Stage A — Verify release-ready state

A release is cut **only** from a clean `main` that exactly matches
`origin/main`. Releasing anything else risks tagging work that was never
reviewed or pushed.

1. `git rev-parse --abbrev-ref HEAD` — if not on `main`, switch:
   `git checkout main`.
2. `git status --porcelain` — the working tree **must be clean**. If it is
   dirty, **stop**: uncommitted work is not part of a release — tell the user to
   `/land` it (or stash it) first, then re-invoke `/release`.
3. `git fetch origin main --tags --prune` — get the latest commits and tags.
4. Compare local `main` to `origin/main`:
   - **Behind** — fast-forward: `git pull --ff-only origin main`.
   - **Ahead** (local commits not on origin) — **stop**: those commits must be
     landed via `/land` (reviewed + pushed) before they can be released.
   - **Diverged** — **stop** and surface it; never force anything.
   - **Equal** — good, continue.
5. `gh run list --workflow ci.yml --branch main --limit 1 --json conclusion,headSha`
   — the latest CI run on `main` must be `success` for `main`'s HEAD. If it
   failed or is still running, **stop** and report it.

State plainly what you verified (on `main`, clean, synced, CI green).

> **No local test run.** Every commit on `main` passed `/land`'s Stage F and
> CI; the Release workflow re-runs the tests before publishing.

---

## Stage B — Verify the train version

### B.1 — Read the version

1. Run `scripts/check-version.sh`. It reads `EngineVersion.CURRENT`, checks it
   is SemVer, and checks `dsl.lock` equals it. On failure, **stop**: the bump
   commit is missing or inconsistent — fix it with `/land`, not here.
2. The **target version** is `EngineVersion.CURRENT`.
3. If `$ARGUMENTS` gave a version that differs from the target (ignoring a
   leading `v`), **stop** and surface the mismatch. Bumping the version is a
   `/land` of `EngineVersion.kt` + `dsl.lock`.

### B.2 — The app8-dsl tag exists (source of truth)

```
scripts/check-version.sh --release <target>
```

This also checks that `appeight/app8-dsl` has the tag `dsl.lock` names
(`git ls-remote --tags https://github.com/appeight/app8-dsl.git`). If the tag
is missing, **stop**: app8-dsl is tagged first (`VERSIONING.md`, step 6). Tell
the user to tag app8-dsl `<target>`, then re-invoke `/release`.

### B.3 — Fresh Android tag

1. `git tag --sort=-v:refname | head -1` — the latest Android tag (may be empty
   for the first release).
2. The target **must not already be a tag** here. If it is, **stop**.
3. If there is a prior tag, the target **must be strictly greater** (SemVer).
   Otherwise **stop**.
4. `git log <last-tag>..HEAD --oneline` (all commits if there is no prior tag)
   — the commits in this release. If empty, **stop**: nothing to release.

### B.4 — The iOS twin (`AskUserQuestion` when needed)

Both SDKs carry the same version. Check iOS:

1. Tag: `git ls-remote --tags https://github.com/appeight/app8-ios-sdk.git refs/tags/<target>`.
   If it exists, record **iOS: released** and continue to B.5.
2. Otherwise read iOS `main`:
   ```
   gh api repos/appeight/app8-ios-sdk/contents/Sources/App8Engine/Core/EngineVersion.swift \
     -H "Accept: application/vnd.github.raw" | sed -nE 's/.*static let current = "([^"]+)".*/\1/p'
   ```
   - **Not equal to the target** — **stop**: the train is broken. iOS must bump
     `EngineVersion.current` (and its `dsl.lock`, if present) to `<target>` and
     land it first. Report both versions.
   - **Equal to the target** — iOS is ready but untagged. Ask
     (`AskUserQuestion`): "iOS `main` is at `<target>` but not tagged. Cut both
     together?"
     - **cut iOS now** (recommended) — run `/release` in `../app8-ios-sdk`
       for `<target>`, then come back and repeat B.4.1.
     - **Android first** — continue; iOS must be tagged right after. Record it
       and repeat it loudly in the Stage F summary.
     - **abort** — stop.

### B.5 — Confirm the version (`AskUserQuestion`)

Print the train status, then confirm:

| | Version | State |
|---|---|---|
| app8-dsl | `<target>` | tag exists |
| iOS | `<target>` | released / cut next |
| Android | `<target>` | to be tagged (prior: `<last-tag>`, N commits) |

- **question**: "Release App8 Android SDK `<target>` on the `<target>` train?"
- First option **"Use it"**; **"Other"** only to abort or flag that the
  version itself is wrong (then stop — the fix is a `/land`).

---

## Stage C — Release notes

### C.1 — Draft and confirm (`AskUserQuestion`)

1. Draft notes from the Conventional-Commit subjects since the last tag (B.3),
   grouped under headings — drop those with no entries:

   ```markdown
   ## <version>

   Release train <version>: app8-dsl <version>, app8-ios-sdk <version>.

   ### Installation
   implementation("dev.app8:engine:<version>")

   ### Features
   - <feat subject> (#PR)

   ### Fixes
   - <fix subject> (#PR)

   ### Other
   - <docs / chore / refactor / test subjects> (#PR)
   ```

   Strip the Conventional-Commit `type:` prefix from each line. Append the PR
   number when the squashed commit subject carries one (`… (#12)`). Always keep
   the train line — it is the visible record of the version-sync rule.
2. Print the full drafted notes in the message body.
3. Confirm via `AskUserQuestion` — first option **"Use as-is"**, **"Other"** to
   supply edited notes.

The confirmed notes become the annotated tag message; the Release workflow
publishes them as the GitHub release body.

---

## Stage D — Final release gate

### D.1 — Confirm (`AskUserQuestion`)

Print a clean summary first (see Stage F), then ask one question:

- **question**: "Create release `<version>` — tag `<version>` at `<short-sha>`
  on `main`, push it, and let the Release workflow publish to GitHub Packages
  and create the GitHub release?"
- **create** — run Stage E.
- **hold** — do nothing; stop and report.
- **abort** — do nothing; stop and report.

---

## Stage E — Tag & publish

On **create**, run in order:

1. Write the confirmed notes to a temporary file and create an **annotated**
   tag at `main`'s HEAD:
   ```
   git tag -a <version> -F <notes-file>
   ```
2. Push the tag:
   ```
   git push origin <version>
   ```
3. Watch the Release workflow the push started:
   ```
   gh run list --workflow release.yml --limit 5 --json databaseId,headBranch,status
   gh run watch <run-id> --exit-status
   ```
   (`headBranch` is the tag name.) The workflow checks the tag against
   `EngineVersion.CURRENT` and the app8-dsl tag, runs the tests and
   `apiCheck`, runs `./gradlew publish`, and creates the GitHub release.
4. **On failure** (`AskUserQuestion`): show the failing step
   (`gh run view <run-id> --log-failed`) and ask:
   - **rerun** — only for transient failures (network, runner):
     `gh run rerun <run-id> --failed`, then watch again.
   - **stop** — report and stop. If `publish` partly succeeded, GitHub
     Packages already holds that version and it cannot be re-published: the
     fix is the next patch version, never a moved tag.
5. On success, report the release URL (`gh release view <version> --json url`)
   and confirm the packages exist:
   ```
   gh api orgs/appeight/packages/maven/dev.app8.engine/versions --jq '.[].name' | grep -x <version>
   ```

If a step fails (tag exists, push rejected), **stop** and surface the error.
Never `--force` a tag, never delete or move an existing tag, and never
overwrite an existing release without explicit user instruction. Publish from
a laptop (`./gradlew publish` with `GITHUB_ACTOR` / `GITHUB_TOKEN` set) only
when the user explicitly asks.

---

## Stage F — Summary

Print a scannable recap:

1. **Released** — the version, the commit it tagged (`<short-sha> <subject>`).
2. **Train** — app8-dsl `<version>` tag; iOS `<version>`: released / **still to
   be tagged** (if B.4 chose "Android first" — say it loudly and give the
   command: `/release` in `../app8-ios-sdk`); the cloud SDKs
   (`app8-ios-cloud-sdk`, `app8-android-cloud-sdk`) follow with the same version.
3. **Previous** — the prior tag and how many commits are in this release (or
   "first release").
4. **Tag** — created & pushed (annotated).
5. **Release workflow** — run URL and result; packages published
   (`dev.app8:engine-core`, `engine`, `engine-maps` at `<version>`).
6. **GitHub release** — title + URL, or "held / aborted".
7. **For Gradle clients** — bump the dependency to
   `implementation("dev.app8:engine:<version>")` (and `engine-maps` if used).

---

## Guardrails (non-negotiable)

- **One version across the train.** The release version is
  `EngineVersion.CURRENT`, which equals `dsl.lock`, an existing app8-dsl tag,
  and the iOS version. Never tag a version that disagrees; fixing the version
  is a `/land`, not a `/release`.
- **app8-dsl is tagged first; iOS and Android are cut together.**
- **Release only from a clean `main` that equals `origin/main`, with green CI.**
- **Tag at `main`'s HEAD only.**
- **Annotated tags, SemVer, no `v` prefix.**
- **Never reuse, move, delete, or force a tag**, and never overwrite an existing
  GitHub release, without explicit user instruction. A published Maven version
  is final: ship fixes as the next patch.
- **Never force-push.**
- **Tag & publish only at the D.1 gate, only on explicit `create`.**
- **Every question to the user uses `AskUserQuestion`** — bounded gates and the
  drafted release notes alike. Never end a message with a free-form "reply
  with … / type 'go'" prompt. See "Asking the user".

---

## Graduation

CI already runs the full gate on every PR and the Release workflow re-tests
before publishing. Once outside contributors arrive, protect `main` (required
review, required `CI / build` check) and protect version tags (tag protection
rule for `[0-9]*` so only maintainers can push them).

When the API is declared stable, the train cuts `1.0.0`; `apiCheck` then
guards SemVer: a changed `api/*.api` removal or signature change requires a
major version.

If the platforms ever need independent patch releases, this rule retires and
`/release` returns to computing a SemVer bump from this repo's own commits.
