Releasing
========

 1. Update the version in `gradle.properties`.
 2. Update the `CHANGELOG.md` for the impending release.
 3. `git commit -am "Prepare for release X.Y.Z."` (where X.Y.Z is the new version)
 4. `git tag -a X.Y.Z -m "Version X.Y.Z"` (where X.Y.Z is the new version)
 5. `git push && git push --tags`
 6. Generate newly updated documentation with `./gradlew dokkaHtml`.
 7. Deploy new documentation to `fegan104.github.io`.
 8. Update the `README.md` with the new version from Jitpack.
