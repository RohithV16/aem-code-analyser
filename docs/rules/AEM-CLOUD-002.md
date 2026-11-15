---
layout: default
title: AEM-CLOUD-002
---

# AEM-CLOUD-002 — DeprecatedJspUsage

| Property | Value |
|---|---|
| Severity | WARNING |
| Type | CODE_SMELL |
| Effort | 30 minutes |
| Tags | aem-cloud |

## Description

Legacy JSP file found in repository; AEMaaCS requires HTL.

## Remediation

Migrate the JSP to HTL.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-CLOUD-002
```
