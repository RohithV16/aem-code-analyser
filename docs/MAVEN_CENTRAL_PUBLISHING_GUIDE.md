---
layout: default
title: Maven Central publishing guide
---

# Maven Central Publishing Guide for AEM Code Analyser

This guide documents the complete procedure for publishing releases of `io.github.rohithv16:aem-code-analyser` and its submodules (`aem-analyser-core`, `aem-analyser-maven-plugin`, `aem-analyser-cli`) to **Maven Central** via the modern **Sonatype Central Portal** (`central.sonatype.com`).

---

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
3. Click your profile avatar (top right) -> **View Account** -> **Namespaces**.
4. Click **Add Namespace** and enter:
   ```text
   io.github.rohithv16
   ```
5. **Namespace Verification:**
   - Because you signed in with the GitHub account matching `rohithv16`, Sonatype will automatically authorize or ask you to create a temporary empty public repository (e.g., `RohithV16/<verification-code>`) to confirm ownership.
   - Click **Verify Namespace**.
6. Once verified, go to **Account** -> **Generate User Token**.
7. Securely save the generated:
   - `User Name` (Token Username)
   - `Token / Password` (Token Password)

---

### Step 2: Generate and Distribute a GPG Keypair

Maven Central strictly rejects any artifact lacking cryptographic signatures.

#### 1. Generate GPG Key:
```bash
gpg --gen-key
```
- Enter your Real Name: `Rohith`
- Enter your Email: your GitHub-associated email
- Provide a secure passphrase (keep this passphrase safe!).

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
       <version>1.0.6</version>
   </plugin>
   ```
