# VRC-A (Chatbox-VRC-A) — Claude Code Instructions

## Project Overview
Android app called VRC-A (VRChat Assistant), also referred to as Chatbox-VRC-A.
Built with Kotlin, Jetpack Compose, Firebase Firestore, and GitHub Actions CI.
There are two build variants: a public user-facing APK and a separate admin APK.
Display name embedded in UI strings: "Ashoska Mitsu Sisko".

## Repository Structure
- Main app source: `app/src/main/`
- Admin vs public build separation: `BuildConfig.IS_ADMIN_BUILD`
- CI workflow: `.github/workflows/android.yml`
- Firebase config: `google-services.json`

## Build Commands
```bash
# Debug build (public)
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Admin build
./gradlew assembleAdminDebug

# Run tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest

# Full build + test
./gradlew build
```

## Architecture & Stack
- Language: Kotlin
- UI: Jetpack Compose
- Backend: Firebase Firestore
- Auth/device management: Firestore `bannedDevices` collection
- NowPlaying: pause detection logic (check for edge cases when modifying)
- Admin UI: Users tab, Releases tab, tab bar navigation
- APK distribution: GitHub Releases

## Coding Conventions
- Use Jetpack Compose for all new UI — no XML layouts
- Follow existing file/package structure when adding new screens or components
- Admin-only features must be gated behind `BuildConfig.IS_ADMIN_BUILD`
- Firestore security rules must be updated alongside any schema changes
- Keep admin APK and public APK builds fully separated

## Git & PR Conventions
- Branch naming: `fix/description`, `feature/description`, `chore/description`
- Commit messages: short, imperative ("Fix NowPlaying pause detection", "Add Releases tab filter")
- Always open a PR for non-trivial changes rather than pushing directly to main
- Include a brief description in the PR body of what changed and why

## Autonomous Permissions
Claude is fully authorised to do all of the following without asking for confirmation:

- Read, create, edit, and delete any files in the repository
- Run any build, test, or lint commands
- Commit changes with descriptive commit messages
- Push branches to the remote repository
- Open pull requests with descriptions of what was changed
- Merge pull requests if all checks pass and the task is self-contained
- Create and push new releases to GitHub Releases
- Update Firestore security rules when schema changes require it
- Install dependencies or update `build.gradle` files as needed
- Fix any build errors, lint warnings, or test failures encountered along the way
- Refactor code for clarity or performance without being asked
- Add or update comments and documentation inline
- **Update this CLAUDE.md file** whenever new features, architecture changes, or conventions are added

## Keeping CLAUDE.md Up To Date
Claude must treat this file as a living document and update it as part of normal development:

- **When adding a new feature or screen:** Add a brief entry under Architecture & Stack describing what it does and any key implementation notes
- **When adding a new dependency or library:** Note it under Architecture & Stack with its purpose
- **When changing build commands or adding new Gradle tasks:** Update the Build Commands section
- **When changing Firestore schema or collections:** Update the relevant notes under Architecture & Stack
- **When establishing a new pattern or convention:** Add it to Coding Conventions so it applies to future work
- **When completing a significant PR:** Review this file and add anything that would help future sessions understand the codebase better

Claude should update CLAUDE.md in the same commit as the feature it describes, not as a separate task. The goal is that any new Claude Code session can read this file and immediately understand the full current state of the project without needing to explore the codebase from scratch.

## What Claude Should Always Do
- Run `./gradlew build` after making changes to verify nothing is broken before pushing
- Check that both admin and public build variants still compile after any change
- Never hardcode secrets, API keys, or credentials — use environment variables or Firebase config
- Keep `BuildConfig.IS_ADMIN_BUILD` gates intact on all admin-only features
- If a Firestore rule change is needed, update both the rules file and note it in the PR
- Update this CLAUDE.md file whenever the architecture, conventions, or features change

## What Claude Should Never Do
- Push directly to `main` for large feature additions — always use a PR
- Remove or weaken `bannedDevices` Firestore security rules
- Mix admin-only UI into the public build
- Break the existing GitHub Actions CI pipeline without replacing it with something better
- Leave this CLAUDE.md file out of date after making significant changes
