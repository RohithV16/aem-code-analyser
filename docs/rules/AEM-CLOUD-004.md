---
layout: default
title: AEM-CLOUD-004
---

# AEM-CLOUD-004 — DeprecatedAemApiUsage

| Property | Value |
|---|---|
| Severity | WARNING |
| Type | CODE_SMELL |
| Effort | 20 minutes |
| Tags | aem-cloud, api |

## Description

Java source imports or uses an API deprecated in AEM as a Cloud Service.

## Remediation

Replace the deprecated API with the supported AEM as a Cloud Service equivalent.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-CLOUD-004
```
