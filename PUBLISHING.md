# Publishing to Maven Central

The library module (`:mebius`) publishes to Maven Central via the
[Vanniktech Maven Publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/),
using the **Sonatype Central Portal** host. Coordinates: `io.mebius:mebius-android-sdk`.

## One-time setup

1. **Central Portal account & namespace.** Register at
   <https://central.sonatype.com/> and verify ownership of the `io.mebius` namespace.

2. **GPG signing key.** Generate and export an in-memory armored key:

   ```bash
   gpg --gen-key
   gpg --export-secret-keys --armor <KEY_ID> > signing-key.asc
   ```

3. **Credentials.** Provide these as Gradle properties (in
   `~/.gradle/gradle.properties`, **never** committed) or as environment variables.
   These are placeholders — fill in real values at release time:

   ```properties
   # ~/.gradle/gradle.properties  (NOT in version control)
   mavenCentralUsername=<central-portal-token-username>
   mavenCentralPassword=<central-portal-token-password>

   signingInMemoryKey=<contents of signing-key.asc, newlines as \n>
   signingInMemoryKeyId=<short key id, optional>
   signingInMemoryKeyPassword=<key passphrase>
   ```

   Or as environment variables for CI:

   ```bash
   export ORG_GRADLE_PROJECT_mavenCentralUsername=...
   export ORG_GRADLE_PROJECT_mavenCentralPassword=...
   export ORG_GRADLE_PROJECT_signingInMemoryKey=...
   export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=...
   ```

## Set the version

Edit `VERSION_NAME` in `gradle.properties` (drop any `-SNAPSHOT` suffix for releases).

## Dry run (recommended before every release)

Build and stage the artifacts locally without uploading anything:

```bash
# Generate the full publication into the local Maven repo and inspect it.
./gradlew :mebius:publishToMavenLocal

# Verify the produced files (aar, sources jar, javadoc jar, pom, signatures).
ls ~/.m2/repository/io/mebius/mebius-android-sdk/<version>/
```

You should see `.aar`, `-sources.jar`, `-javadoc.jar`, `.pom`, and matching `.asc`
signature files.

## Release

```bash
# Uploads to the Central Portal staging area. automaticRelease = false means you
# must review and release manually in the Central Portal UI.
./gradlew :mebius:publishAndReleaseToMavenCentral --no-configuration-cache
```

After upload, log in to <https://central.sonatype.com/>, review the deployment,
and publish. Propagation to Maven Central typically takes 10–30 minutes.

## Tag the release

```bash
git tag -a v<version> -m "Release <version>"
git push origin v<version>
```
