# Publishing to Maven Central

> **Goal:** anyone, on any continent, uses the SDK with nothing but
> `implementation("io.mebius:mebius-android-sdk:<version>")` from `mavenCentral()`.
> No `mavenLocal()`, no manual jar.

Coordinates: `io.mebius:mebius-android-sdk`, published with the
[Vanniktech Maven Publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/)
to the **Sonatype Central Portal**.

## Where things stand

The one-time setup is **done**. The `io.mebius` namespace is verified, a GPG
signing key exists (`Mebius Release Signing <dev@mebius.io>`, rsa4096, expires
2028-08-05), and `0.2.2` is live on Central.

What is *not* set up is CI: the repo has no Actions secrets, so
`.github/workflows/release.yml` skips the upload and releases are pushed from a
maintainer's machine. Both paths are below.

## Release from your machine (how releases happen today)

Credentials live in `~/.gradle/gradle.properties` — never in this repo:

```properties
mavenCentralUsername=<central-portal-token-username>
mavenCentralPassword=<central-portal-token-password>
signingInMemoryKey=<armored secret key, newlines written as \n>
signingInMemoryKeyPassword=<key passphrase>
```

> The single most common failure is the key. `signingInMemoryKey` must be the
> **whole** armored block on one line with real newlines escaped as `\n`. A key
> that is truncated, unescaped, or empty fails as
> `Could not read PGP secret key` at `:mebius:signMavenPublication`.

Then, per release:

```bash
# 1. Set the version.
#    Edit VERSION_NAME in gradle.properties (no -SNAPSHOT suffix for a release).

# 2. Check what will be published, without uploading anything.
./gradlew :mebius:publishToMavenLocal --no-configuration-cache
ls ~/.m2/repository/io/mebius/mebius-android-sdk/<version>/
#    Expect .aar, -sources.jar, -javadoc.jar, .pom, and a matching .asc for each.
#    No .asc files means signing was skipped — your key is not configured, and
#    Central will reject the upload.

# 3. Upload and release.
./gradlew :mebius:publishAndReleaseToMavenCentral --no-configuration-cache

# 4. Tag it.
git tag -a v<version> -m "Release <version>"
git push origin v<version>
```

Step 3 stages the deployment and releases it. Review it at
<https://central.sonatype.com/> under Publishing Settings > Deployments if it
does not go green on its own. Propagation to `repo1.maven.org` takes 10–30
minutes after that.

## Optional: let CI do it instead

`.github/workflows/release.yml` runs on every `v*` tag. Without credentials it
logs a notice and stops — a tag pushed for an ordinary release does not turn the
workflow red. Add four repo secrets (Settings > Secrets and variables > Actions)
and the same tag push publishes to Central:

- `MAVEN_CENTRAL_USERNAME` — Central Portal token username
- `MAVEN_CENTRAL_PASSWORD` — Central Portal token password
- `SIGNING_KEY` — the full ASCII-armored secret key block
- `SIGNING_KEY_PASSWORD` — that key's passphrase

Export the key block with:

```bash
gpg --export-secret-keys --armor 470BEB80958AC4F8
```

Paste it into the `SIGNING_KEY` secret **verbatim, including the BEGIN/END
lines**. GitHub secrets preserve real newlines, so unlike the gradle.properties
form this one needs no `\n` escaping.

## Renewing the signing key

The current key expires **2028-08-05**. To replace it:

```bash
gpg --quick-generate-key "Mebius Release Signing <dev@mebius.io>" rsa4096 sign 2y
gpg --list-secret-keys --keyid-format=long                    # note the new KEY_ID
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>     # Central verifies against this
gpg --export-secret-keys --armor <KEY_ID>                     # feeds gradle.properties / SIGNING_KEY
```

Publishing the public key to a keyserver is not optional — Central checks the
signature against it and rejects the deployment if it cannot find the key.
