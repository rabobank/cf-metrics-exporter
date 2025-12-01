## Release process

This repository uses a GitHub Actions workflow to build and publish releases.
It does not perform any commits or tag moves on your behalf. 
CI will fail fast if the version in .mvn/maven.config does not match the release tag, and you are expected to make any necessary version changes manually.

Workflows:
- Prepare Release (pre-tag): .github/workflows/prepare-release.yml (optional helper; do not rely on CI to push commits)
- Release (publish + deploy): .github/workflows/release.yml

Versioning rules
- Tags must NOT start with a leading "v". Use plain semantic versions like `1.2.3`.
- When building from a tag, the project version is sourced from `.mvn/maven.config` via `-Drevision=<tag>`, and `pom.xml` uses `<version>${revision}</version>`.
- After a release is published, you must manually bump the main branch to the next -SNAPSHOT version.

Standard flow
1) Prepare the release (pre-tag)
    - Ensure .mvn/maven.config contains exactly one line with the intended release revision, for example:
      `-Drevision=1.2.3`
    - Create and push an annotated Git tag that matches the revision in .mvn/maven.config (example: `1.2.3`).
    - Optionally, create a draft GitHub Release for that tag with the desired notes.

2) Publish the GitHub Release
    - Publishing triggers the "Release" workflow (on: release: published).

3) Publish and deploy (performed by CI)
    - CI checks out the tag and verifies that `.mvn/maven.config` contains `-Drevision=<tag>`.
    - If the value does not match the tag, the workflow fails and prints instructions to fix and retry. No commits or tag moves are performed by CI.
    - If it matches, CI builds and deploys artifacts to Maven Central with the release profile and attaches the built JAR to the GitHub Release.

4) After the release (manual)
    - Manually bump main to the next development version, e.g. `1.3.0-SNAPSHOT`:
      git checkout main
      echo "-Drevision=1.3.0-SNAPSHOT" > .mvn/maven.config
      git commit -am "chore: set next development version to 1.3.0-SNAPSHOT"
      git push origin main

Notes
- CI will not create commits, move tags, or push to branches.
- If the tag does not match the revision in .mvn/maven.config, fix the file in your repository and re-run the release.

