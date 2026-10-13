# Cookbook

A small Android app for `.gram` recipes: browse them, change the number of servings, add new ones by typing them or from a web link. The recipes are plain files on the phone; optionally, they live in a git repository instead: every change is committed and pushed, and other changes are pulled on start and with the sync button.

Native UI (Kotlin, Jetpack Compose, Material 3). No server, no AI, no database: the files (or the git working copy) are the data.

## Install

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or copy `app-debug.apk` to the phone and open it (allow installing from unknown sources). Android 8.0 or later.

Building needs JDK 17 and the Android SDK (platform 35); put `sdk.dir=/path/to/Android/Sdk` in `local.properties`.

## Release signing

`./gradlew assembleRelease` signs with the key described in `keystore.properties` at the project root (git-ignored):

```properties
storeFile=/path/to/gramcookbook-release.jks
storePassword=…
keyAlias=gramcookbook
keyPassword=…
```

Without that file it produces `app-release-unsigned.apk`. Android only installs an update signed with the same key as the installed app, so keep the keystore and its password backed up: losing them means users must uninstall to upgrade.

## Connect a repository (optional)

Git sync is off by default: the recipes stay on the phone. To keep them in a repository, turn on Settings → **Sync with a git repository**. On the first sync, the recipes already on the phone are added to the repository (one commit, "Add recipes from the phone"). Turning sync off again copies the repository's recipes to the phone, so the book stays the same; the repository's copy on the phone is kept for the next time.

Repository URL **over HTTPS** (`https://github.com/<you>/recipes.git`, not the `git@` SSH address).

For GitHub, create a fine-grained personal access token (Settings → Developer settings → Fine-grained tokens) limited to that repository, with **Contents: Read and write**. Put it in the token field. The token is stored in the app's private storage and excluded from backups.

**Test access** checks the values as typed, before saving, without changing the repository: it lists the remote branches (read), then opens a push connection and closes it without sending anything (write; GitHub only accepts it from a token allowed to write). It reports one of: read and write allowed (with the default branch), token missing, token refused, token without write access, repository not found, or server unreachable. If the token is refused, try your GitHub username in the username field.

## Other cookbooks (read-only)

Settings → **Other cookbooks**: add the HTTPS address of someone else's recipe repository (a token only for a private one; read access is enough). It is kept only if it can be cloned. Its recipes join the book's chapters by category, with the repository's name (`owner/repo`) in the contents and at the top of their pages. They are updated at start and with ⟳, and never changed by the app: **Copy to my cookbook** on a recipe makes your own copy, which can be edited. Removing a repository removes its recipes from the book (not your copies).

## Use

- **Browse**: the recipes are a book lying on a desk. Swipe to turn the pages (drag slowly to fold a page over). Page 1 is the contents, with search and page numbers; then each category (the Gram `category:` key) is a chapter: a title page, then its recipes. The colored tabs on the edge of the book open each chapter. Recipes without a category are the last chapter. Back returns to the contents.
- **Category**: the chip at the top of a recipe changes it: type a name or pick an existing one. It rewrites the `category:` line and syncs. Files are not moved.
- **Servings**: − / + on a recipe scales every quantity, except fixed ones (`@=salt{1 tsp}`). Recipes without `portions` scale by halves (×1, ×1.5…).
- **Write a recipe**: title, servings, category, ingredients one per line (`250 g de farine`), steps one per line (`## Name` starts a section). On save it becomes Gram: each ingredient is placed where a step mentions it (`la @farine{250g}`); the others go into a first "Ingredients" step. Or switch the form to **Gram** to write the recipe directly in Gram (with the editor's `@ # ~ ^ ##` buttons); what the form already holds is converted to start from.
- **From a link**: paste a recipe URL and tap Fetch, or use **Share → Cookbook** from the browser. It reads the schema.org recipe data most recipe sites publish (including its category), fills the form, and you review before saving. Sites without that data can't be imported this way.
- **Back** from the editor returns to the recipe, then to the list.
- **Edit**: the pencil opens the raw Gram text, with buttons for `@ # ~ ^ ##`.
- **Look**: recycled paper, ink and a leather cover, in serif type. The pages stay paper in dark mode. "Add a recipe" is the wax seal at the bottom of the book.
- **Colors** of the recipe text follow the Gram playground (gram-lang.org/play), light theme (the pages are always paper). On a recipe, the HTML preview's: ingredients purple, equipment blue, timers amber, temperatures red, intermediate preparations green, quantities as pills, actions as badges. In the editor, the playground editor's syntax highlighting (github-light theme over the official grammar's token types).

## Updates

The app checks the latest GitHub release of this repository at start (silently when offline) and from Settings → Updates. When a newer version exists, a banner offers to download it: the APK's size and SHA-256 are checked against the release, its signing key against the installed app, then Android's installer opens (the first time, Android asks to allow the app to install apps).

To publish an update: raise `versionCode` and `versionName` in `app/build.gradle.kts`, build with `./gradlew assembleRelease` (release key, see above), and create a GitHub release tagged `v<versionName>` with the APK attached. Releases must be signed with the same key as the installed app; an APK signed with another key is reported instead of installed.

## Sync

On start, after every save or delete, and with the ⟳ button: commit everything, pull with rebase, push. If the same recipe changed on the phone and elsewhere, the sync stops and reports a conflict without losing the phone's version; fix it from a computer.

Auto-GC is disabled in the app's working copy (`gc.auto = 0`): JGit's GC needs `java.lang.management`, which Android doesn't have.

## Gram support

`app/src/main/java/app/gramcookbook/gram/` holds a lenient reader that follows the official grammar (ingredients with modifiers, aliases, preparations, ranges, fractions, relative quantities; cookware; timers; temperatures; `&` references; `->&` outputs; sections; comments). It never fails: anything it can't read stays as text. `@use` imports are not resolved.

## Tests

```bash
./gradlew testDebugUnitTest            # parser, converter, importer, git sync, UI (Robolectric)
./gradlew testDebugUnitTest -Dlive=true  # also: a real recipe page and a real GitHub clone
./gradlew testDebugUnitTest -Dscreenshots=true --tests '*ScreenshotTest*'  # renders screens (the book, a recipe, the editor) to app/build/screenshots
```
