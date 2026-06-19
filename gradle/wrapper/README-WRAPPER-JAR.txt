The Gradle wrapper JAR (gradle/wrapper/gradle-wrapper.jar) is a binary produced by
running 'gradle wrapper --gradle-version 8.11.1'. It cannot be authored as text and
is NOT present in this commit because no Gradle/Java toolchain is available in the
build environment. Run 'gradle wrapper --gradle-version 8.11.1' once on a machine
with Gradle installed to generate it, or it will be fetched by your CI's setup-gradle step.
