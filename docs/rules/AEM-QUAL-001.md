---
layout: default
title: AEM-QUAL-001
---

# AEM-QUAL-001 — PathBoundServletRegistration

| Property | Value |
|---|---|
| Severity | WARNING |
| Type | CODE_SMELL |
| Effort | 15 minutes |
| Tags | quality, aem-cloud |

## Description

Sling servlet registered using paths instead of resource types (CQBP-75).

## Remediation

Register the servlet by resourceType instead of paths (CQBP-75).

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-QUAL-001
```
