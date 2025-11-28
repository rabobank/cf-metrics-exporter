## Release process

The repository contains two complementary GitHub Actions workflows that keep tags, versions, and the Maven build in sync:

- Prepare Release (pre-tag): .github/workflows/prepare-release.yml
- Release (publish + deploy): .github/workflows/release.yml

They are designed to work together as follows.

Versioning rules
- Tags may optionally start with a leading "v" (e.g., v1.2.3 or 1.2.3).
- When building from a tag, the project version is sourced from .mvn/maven.config via -Drevision=<tag>, and pom.xml uses <version>${revision}</version>.
- After a release is published, the main branch is automatically bumped to the next MINOR snapshot (e.g., 1.2.3 → 1.3.0-SNAPSHOT).

Standard flow
1) Prepare the release (pre-tag)
    - In GitHub, go to Actions → "Prepare Release (pre-tag)" → Run workflow.
    - Enter the desired tag (examples: 1.2.3 or v1.2.3) and run.
    - What it does:
        - Creates a release/<tag> branch from main.
        - Writes -Drevision=<tag> to .mvn/maven.config if not already present and commits the change.
        - Creates and pushes an annotated Git tag pointing at that commit.
        - Creates a DRAFT GitHub Release for that tag using CHANGELOG.md and generated notes.

2) Review and publish the draft release
    - Open the draft release created in step 1.
    - Review/edit title and notes as needed, then click Publish.

3) Publish and deploy
    - Publishing triggers the "Release" workflow (on: release: published).
    - What it does:
        - Checks out the tag that was created in step 1.
        - Verifies .mvn/maven.config contains -Drevision=<tag>. If not, it writes it, commits, and moves the tag to include the file (idempotent).
        - Builds and deploys the artifacts to Maven Central with the release profile.
        - Attaches the built JAR to the GitHub Release.
        - Switches to main and bumps .mvn/maven.config to the next MINOR snapshot (e.g., 1.3.0-SNAPSHOT), commits, and pushes.

Notes and edge cases
- If you created the tag via the Prepare Release workflow, the Release workflow will detect the correct revision and skip re-tagging.
- If you manually created a tag without updating .mvn/maven.config, the Release workflow will fix this by committing the correct revision and force-updating the tag so the tag points at the commit containing the correct file.
- Tag format validation: the Prepare Release workflow validates tag input; non-numeric tags will be rejected. The Release workflow will skip the main bump if the tag is not numeric.
- Re-running jobs:
    - Prepare Release will fail if the tag already exists (to prevent accidental overwrite).
    - The Release workflow is safe to re-run for the same published release; it will detect unchanged state and no-op where appropriate.
- Rollback:
    - If you need to abort before publishing, delete the created tag and draft release. The main branch remains unchanged until you publish.
    - If a publish fails mid-way, fix the issue and re-run the Release workflow; if necessary, delete and recreate the release/tag.

Examples
- Tag v1.2.3 → artifacts built with version 1.2.3, main becomes 1.3.0-SNAPSHOT afterwards.
- Tag 2.0 → artifacts built with version 2.0, main becomes 2.1.0-SNAPSHOT afterwards.

