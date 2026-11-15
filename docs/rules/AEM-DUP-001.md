---
layout: default
title: AEM-DUP-001
---

# AEM-DUP-001 — DuplicateServletEndpoint

| Property | Value |
|---|---|
| Severity | ERROR |
| Type | BUG |
| Effort | 20 minutes |
| Tags | duplication |

## Description

Multiple OSGi servlets claim identical paths or resourceTypes/selectors/methods without ranking distinction.

## Remediation

Merge the servlets or add distinct selectors/resource types.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-DUP-001
```
