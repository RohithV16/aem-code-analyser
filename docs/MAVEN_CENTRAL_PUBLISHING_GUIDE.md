---
layout: default
title: Maven Central publishing guide
---

# Maven Central Publishing Guide for AEM Code Analyser

This guide documents the complete procedure for publishing releases of `io.github.rohithv16:aem-code-analyser` and its submodules (`aem-analyser-core`, `aem-analyser-maven-plugin`, `aem-analyser-cli`) to **Maven Central** via the modern **Sonatype Central Portal** (`central.sonatype.com`).

---

## 0. Current state

**The build side is done; the account side needs the maintainer.** Everything Central validates is
produced by the `release-central` profile:

| Requirement | Where it is handled |
|---|---|
| Release version, never `-SNAPSHOT` | `scripts/next-version.py` + the release workflow |
| `-sources.jar` per artefact | `maven-source-plugin` (profile) |
| `-javadoc.jar` per artefact | `maven-javadoc-plugin`, `doclint=none` (record components carry no `@param`) |
| `.asc` signature on every file, POM included | `maven-gpg-plugin` with `--pinentry-mode loopback` |
| POM metadata: name, description, url, licenses, developers, scm | root `pom.xml` |
| Upload through the Portal | `central-publishing-maven-plugin` 0.12.0, `autoPublish=false`, `waitUntil=validated` |

`mvn -Prelease-central deploy` publishes `aem-analyser-core` and `aem-analyser-maven-plugin`. The CLI
is excluded (`excludeArtifacts`): it is an application whose shaded jar bundles every dependency, and
publishing that to Central would hand duplicate classes to anyone who depends on it — it ships as a
GitHub release asset instead. `maven-deploy-plugin` is skipped inside the profile so a Central run
never also pushes to GitHub Packages.

### 0.1 What the maintainer has to do once

1. **Register the namespace** — <https://central.sonatype.com/publishing/namespaces> (also reachable
   from the top-right account menu → *View Namespaces*). Sign in **with the `RohithV16` GitHub account**:
   Sonatype then provisions `io.github.<github username>` automatically, and that is the `groupId` this
   project already uses. Only the GitHub **username** is auto-provisioned — an organisation namespace
   would need manual verification. Check the exact spelling shown on that page against the `groupId` in
   `pom.xml` (`io.github.rohithv16`); if it differs in case, either the Portal or the POM has to change,
   because Central compares the strings literally.
   If it is not auto-created, use *Add Namespace*, then create a temporary public repository named
   after the verification key it shows, press *Verify*, and delete the repository afterwards.
2. **Create the user token** — there is a dedicated page: <https://central.sonatype.com/usertoken>
   (not the account menu). Press **Generate User Token**, give it a display name and an expiry
   (e.g. 1 year), and copy both values immediately — the modal shows them once and they cannot be
   retrieved later. These two strings are `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`; they
   are not your Portal login.
3. **Create and publish a signing key.**
   ```bash
   gpg --quick-generate-key "Rohith V <you@example.com>" rsa4096 sign 0
   gpg --keyserver keyserver.ubuntu.com --send-keys <FINGERPRINT>
   gpg --armor --export-secret-keys <FINGERPRINT>   # goes into GPG_PRIVATE_KEY, never into git
   ```
4. **Add four repository secrets** (`Settings → Secrets and variables → Actions`):

   | Secret | Value |
   |---|---|
   | `MAVEN_CENTRAL_USERNAME` | token username from step 2 |
   | `MAVEN_CENTRAL_PASSWORD` | token password from step 2 |
   | `GPG_PRIVATE_KEY` | ASCII-armoured secret key from step 3 |
   | `GPG_PASSPHRASE` | passphrase of that key |

Until they exist the release workflow skips Central and stays green, printing a notice.

### 0.2 Verifying without publishing

```bash
mvn -B -ntp -Prelease-central -DskipTests verify -Dgpg.passphrase=…
# every module except the CLI must now have: jar ++ sources ++ javadoc ++ .asc for each
gpg --verify aem-analyser-core/target/aem-analyser-core-<v>.jar.asc \
             aem-analyser-core/target/aem-analyser-core-<v>.jar
```

After a release run the bundle waits for approval at
<https://central.sonatype.com/publishing/deployments> — press *Publish* there (`autoPublish=false` is
deliberate: two accidental clicks on a workflow must not be able to publish).

### 0.3 What is available today

| Artefact | From | Token needed |
|---|---|---|
| `aem-analyser-cli` | GitHub release asset (`aem-analyser-cli.jar`) | no |
| `aem-analyser-core` | JitPack, `com.github.RohithV16.aem-code-analyser:aem-analyser-core:<tag>` | no |
| `aem-analyser-maven-plugin` | GitHub Packages | `read:packages` |
| `aem-analyser-maven-plugin` | release assets via `install:install-file` | no |
| both libraries/plugins | Maven Central | — as soon as 0.1 is done |

JitPack cannot serve the plugin: it relocates the groupId and Maven rejects a plugin whose descriptor
disagrees (`Invalid plugin descriptor ... wrong group ID`). Central is the fix for that, not JitPack.

To install the plugin without any registry while Central is not live:

```bash
VERSION=<version>
mvn install:install-file -Dfile=aem-code-analyser-$VERSION.pom -DpomFile=aem-code-analyser-$VERSION.pom
mvn install:install-file -Dfile=aem-analyser-core-$VERSION.jar -DpomFile=aem-analyser-core-$VERSION.pom
mvn install:install-file -Dfile=aem-analyser-maven-plugin-$VERSION.jar -DpomFile=aem-analyser-maven-plugin-$VERSION.pom
mvn io.github.rohithv16:aem-analyser-maven-plugin:$VERSION:check
```

## 1. Overview & Architecture

Maven Central is the default artifact repository for Apache Maven, Gradle, and JVM tooling. Once published here:
- Any developer or CI/CD pipeline can consume `aem-analyser-maven-plugin` without adding custom `<pluginRepositories>`.
- No authentication tokens (PATs) are required for consumers.

```
+-------------------------------------------------------------------------------+
|                             GitHub Actions Workflow                           |
|                               (release.yml)                                   |
+-------------------------------------------------------------------------------+
          |                                                   |
          v                                                   v
+-----------------------------+             +-----------------------------------+
|       GitHub Packages       |             |           Maven Central           |
| (maven.pkg.github.com)      |             |       (central.sonatype.com)      |
|  - Authenticated consumers  |             |   - Global public access          |
|  - Fast internal deploys    |             |   - Requires GPG signature        |
+-----------------------------+             |   - Requires Javadoc & Sources    |
                                            +-----------------------------------+
```

---

## 2. Step-by-Step Setup Guide

### Step 1: Claim the Namespace on Sonatype Central Portal

1. Navigate to [https://central.sonatype.com/](https://central.sonatype.com/).
2. Click **Sign In** and authenticate using your GitHub account (`RohithV16`).
3. Open <https://central.sonatype.com/publishing/namespaces> (or profile avatar → *View Namespaces*).
4. Click **Add Namespace** and enter:
   ```text
   io.github.rohithv16
   ```
5. **Namespace Verification:**
   - Because you signed in with the GitHub account matching `rohithv16`, Sonatype will automatically authorize or ask you to create a temporary empty public repository (e.g., `RohithV16/<verification-code>`) to confirm ownership.
   - Click **Verify Namespace**.
6. Once verified, open <https://central.sonatype.com/usertoken> and press **Generate User Token**
   (display name + expiry). The credentials are shown once.
7. Securely save the generated:
   - `User Name` (Token Username)
   - `Token / Password` (Token Password)

---

### Step 2: Generate and Distribute a GPG Keypair

Maven Central strictly rejects any artifact lacking cryptographic signatures.

#### 1. Generate GPG Key:

```bash
gpg --quick-generate-key "Rohith V <your-github-email>" rsa4096 sign 0
```
- The passphrase is what goes into `GPG_PASSPHRASE`; the key id/fingerprint goes into `GPG_KEY_ID`
  if you keep the plugin's `keyname` configured (omitted by default so the only secret key is used).
- `--pinentry-mode loopback` is already configured in the profile, so CI can sign unattended.

#### 2. Retrieve the Key ID:
```bash
gpg --list-keys --keyid-format LONG
```
Example output:
```text
pub   ed25519/ABCD1234EF567890 2026-11-15 [SC]
      1234567890ABCDEF1234567890ABCDEF12345678
uid                 [ultimate] Rohith <email@example.com>
```
The Key ID is the 16-character string after the slash (e.g., `ABCD1234EF567890`).

#### 3. Distribute Public Key to Key Servers:
Maven Central requires public keys to be discoverable on public keyservers:
```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <YOUR_KEY_ID>
```
*(Also syncs with `keys.openpgp.org` and `pgp.mit.edu` automatically).*

#### 4. Export Private Key for GitHub Actions:
```bash
gpg --armor --export-secret-keys <YOUR_KEY_ID>
```
Copy the full output including `-----BEGIN PGP PRIVATE KEY BLOCK-----` and `-----END PGP PRIVATE KEY BLOCK-----`.

---

### Step 3: Configure GitHub Repository Secrets

In your GitHub repository ([`RohithV16/aem-code-analyser`](https://github.com/RohithV16/aem-code-analyser)):
Navigate to: **Settings** -> **Secrets and variables** -> **Actions** -> **New repository secret**.

Add the following 4 secrets:

| Secret Name | Description / Source |
| :--- | :--- |
| `SONATYPE_USERNAME` | The User Name generated from Sonatype Central Portal token |
| `SONATYPE_PASSWORD` | The Token / Password generated from Sonatype Central Portal token |
| `MAVEN_GPG_PRIVATE_KEY` | The full ASCII-armored GPG private key block from Step 2.4 |
| `MAVEN_GPG_PASSPHRASE` | The passphrase set during GPG key generation |

---

### Step 4: Required POM Adjustments for Maven Central Compliance

Maven Central enforces metadata, javadocs, sources, and GPG signatures. When ready to publish to Central, update the root [`pom.xml`](../pom.xml):

#### 1. Metadata (Licenses, Developers, SCM)
Add directly under `<description>` in root `pom.xml`:
```xml
    <licenses>
        <license>
            <name>Apache License, Version 2.0</name>
            <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
            <distribution>repo</distribution>
        </license>
    </licenses>

    <developers>
        <developer>
            <id>rohithv16</id>
            <name>Rohith</name>
            <url>https://github.com/RohithV16</url>
        </developer>
    </developers>

    <scm>
        <connection>scm:git:git://github.com/RohithV16/aem-code-analyser.git</connection>
        <developerConnection>scm:git:ssh://github.com:RohithV16/aem-code-analyser.git</developerConnection>
        <url>https://github.com/RohithV16/aem-code-analyser</url>
    </scm>
```

#### 2. Release Profile in `pom.xml`
Add a dedicated Maven profile `release-central` so everyday local builds and tests are not slowed down by GPG signing:

```xml
    <profiles>
        <profile>
            <id>release-central</id>
            <build>
                <plugins>
                    <!-- Source JAR generation -->
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-source-plugin</artifactId>
                        <version>3.3.1</version>
                        <executions>
                            <execution>
                                <id>attach-sources</id>
                                <goals>
                                    <goal>jar-no-fork</goal>
                                </goals>
                            </execution>
                        </executions>
                    </plugin>

                    <!-- Javadoc JAR generation -->
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-javadoc-plugin</artifactId>
                        <version>3.7.0</version>
                        <executions>
                            <execution>
                                <id>attach-javadocs</id>
                                <goals>
                                    <goal>jar</goal>
                                </goals>
                            </execution>
                        </executions>
                        <configuration>
                            <doclint>none</doclint>
                        </configuration>
                    </plugin>

                    <!-- Cryptographic GPG Signing -->
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-gpg-plugin</artifactId>
                        <version>3.2.4</version>
                        <executions>
                            <execution>
                                <id>sign-artifacts</id>
                                <phase>verify</phase>
                                <goals>
                                    <goal>sign</goal>
                                </goals>
                            </execution>
                        </executions>
                    </plugin>

                    <!-- Modern Sonatype Central Publishing Plugin -->
                    <plugin>
                        <groupId>org.sonatype.central</groupId>
                        <artifactId>central-publishing-maven-plugin</artifactId>
                        <version>0.7.0</version>
                        <extensions>true</extensions>
                        <configuration>
                            <publishingServerId>central</publishingServerId>
                            <autoPublish>true</autoPublish>
                            <waitUntil>published</waitUntil>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
```

---

### Step 5: Updating GitHub Actions Workflow (`release.yml`)

When activating Maven Central publishing, update [`.github/workflows/release.yml`](../.github/workflows/release.yml):

```yaml
      - name: Import GPG Key
        uses: crazy-max/ghaction-import-gpg@v6
        with:
          gpg_private_key: ${{ secrets.MAVEN_GPG_PRIVATE_KEY }}
          passphrase: ${{ secrets.MAVEN_GPG_PASSPHRASE }}

      - name: Deploy to GitHub Packages & Maven Central
        run: |
          # 1. Deploy to GitHub Packages
          mvn deploy -DskipTests -s ${{ github.workspace }}/settings.xml
          
          # 2. Deploy & Publish to Maven Central
          mvn deploy -P release-central -DskipTests \
            -DpublishingServerId=central \
            -Dsonatype.username=${{ secrets.SONATYPE_USERNAME }} \
            -Dsonatype.password=${{ secrets.SONATYPE_PASSWORD }}
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

---

## 3. Verification After Release

1. **Sonatype Dashboard**: Monitor the publication process at [https://central.sonatype.com/publishing/deployments](https://central.sonatype.com/publishing/deployments).
2. **Central Sync**: Artifacts typically appear on Maven Central Search within 15–30 minutes:
   ```text
   https://central.sonatype.com/artifact/io.github.rohithv16/aem-analyser-maven-plugin
   ```
3. **Consumer Verification**: Consumers can now simply declare the plugin in their AEM project `pom.xml` without any repository declaration:
   ```xml
   <plugin>
       <groupId>io.github.rohithv16</groupId>
       <artifactId>aem-analyser-maven-plugin</artifactId>
       <!-- latest: https://github.com/RohithV16/aem-code-analyser/releases -->
       <version>${aem-analyser.version}</version>
   </plugin>
   ```
