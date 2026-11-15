---
layout: default
title: AEM-QUAL-004
---

# AEM-QUAL-004 — UnsafeComponentState

| Property | Value |
|---|---|
| Severity | ERROR |
| Type | BUG |
| Effort | 20 minutes |
| Tags | quality, aem-cloud |

## Description

Non-thread-safe or session-bound object stored in OSGi component field (CQBP-ThreadSafety).

## Remediation

Do not store session-bound or non-thread-safe objects in component fields.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-QUAL-004
```
