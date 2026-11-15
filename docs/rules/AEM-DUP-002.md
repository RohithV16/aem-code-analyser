---
layout: default
title: AEM-DUP-002
---

# AEM-DUP-002 — DuplicateOsgiConfiguration

| Property | Value |
|---|---|
| Severity | WARNING |
| Type | CODE_SMELL |
| Effort | 15 minutes |
| Tags | duplication |

## Description

Conflicting or duplicate OSGi configuration targeting the same PID in the same runmode context.

## Remediation

Keep one config per PID and runmode; remove the duplicate.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-DUP-002
```
