---
layout: default
title: AEM-QUAL-005
---

# AEM-QUAL-005 — AdministrativeAccessUsage

| Property | Value |
|---|---|
| Severity | ERROR |
| Type | VULNERABILITY |
| Effort | 25 minutes |
| Tags | quality, aem-cloud |
| CWE | CWE-798 |

## Description

Deprecated administrative login or resource resolver API used (AMSCORE-553).

## Remediation

Use a dedicated service user with a mapped subservice (AMSCORE-553).

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-QUAL-005
```
