---
layout: default
title: AEM-DEAD-004
---

# AEM-DEAD-004 — BrokenHtlReference

| Property | Value |
|---|---|
| Severity | ERROR |
| Type | BUG |
| Effort | 15 minutes |
| Tags | dead-code, htl |

## Description

HTL template references non-existent Java class or Sling Model.

## Remediation

Correct the class name in the HTL data-sly-use expression.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-DEAD-004
```
