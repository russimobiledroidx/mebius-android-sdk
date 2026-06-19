# Publishing to Maven Central

> **Goal:** anyone, on any continent, uses the SDK with nothing but
> `implementation("io.mebius:mebius-android-sdk:<version>")` from `mavenCentral()`.
> No `mavenLocal()`, no manual jar.

## TL;DR — push-button release (CI)

The repo ships `.github/workflows/release.yml`. Once the one-time setup below is
done, every release is just:

```bash
git tag v0.1.0 && git push origin v0.1.0      # CI publishes + releases to Central
```

That's the only recurring step. The one-time setup needs things ONLY the repo
owner can do (a Sonatype account, domain verification, a signing key) — they
cannot be automated from this machine.

## One-time setup (owner only — required before the first release)

1. **Sonatype Central Portal account** — register at <https://central.sonatype.com/>.
2. **Verify the `io.mebius` namespace.** Central makes you prove you own the
   `mebius.io` domain: it shows a TXT record (e.g. `sonatype-...`) that you add to
   the **DNS of mebius.io**, then click Verify.
   - ⚠️ If you do NOT own `mebius.io`, you cannot publish under `io.mebius`. The
     fallback is the auto-verified `io.github.russimobiledroidx` namespace — but
     then the coordinate becomes `io.github.russimobiledroidx:mebius-android-sdk`
     (change `GROUP` in `gradle.properties` accordingly).
3. **Generate a GPG signing key** (Central requires signed artifacts):
   ```bash
   brew install gnupg               # if gpg is missing
   gpg --quick-generate-key "Mebius <dev@mebius.io>" rsa4096 sign 2y
   gpg --list-secret-keys --keyid-format=long          # note the KEY_ID
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>   # publish public key
   gpg --export-secret-keys --armor <KEY_ID>           # copy this whole block
   ```
4. **Create a Central Portal user token** (Account > Generate User Token) →
   username + password.
5. **Add 4 GitHub Actions secrets** (repo Settings > Secrets and variables > Actions):
   - `MAVEN_CENTRAL_USERNAME` = token username
   - `MAVEN_CENTRAL_PASSWORD` = token password
   - `SIGNING_KEY` = the full armored secret-key block from step 3
   - `SIGNING_KEY_PASSWORD` = that key's passphrase

After this, `git push origin v<version>` triggers the global release. To publish
from your own machine instead of CI, put the same values in
`~/.gradle/gradle.properties` (see below) and run the gradle command directly.

---


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
