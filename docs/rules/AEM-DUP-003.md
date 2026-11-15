---
layout: default
title: AEM-DUP-003
---

# AEM-DUP-003 — DuplicateSlingModel

| Property | Value |
|---|---|
| Severity | WARNING |
| Type | CODE_SMELL |
| Effort | 15 minutes |
| Tags | duplication |

## Description

Multiple Sling Models bind to the exact same resourceType and adapter interface without condition.

## Remediation

Differentiate the resourceType/adapters or merge the models.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-DUP-003
```
