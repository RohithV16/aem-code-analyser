---
layout: default
title: AEM-CLOUD-001
---

# AEM-CLOUD-001 — HardcodedCloudSecret

| Property | Value |
|---|---|
| Severity | ERROR |
| Type | VULNERABILITY |
| Effort | 15 minutes |
| Tags | aem-cloud |
| CWE | CWE-798 |

## Description

Plaintext secret or password found in OSGi configuration file.

## Remediation

Move the secret to a Cloud Manager secret or $[secret:...] placeholder.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-CLOUD-001
```
