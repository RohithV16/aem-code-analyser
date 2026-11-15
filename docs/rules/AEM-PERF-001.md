---
layout: default
title: AEM-PERF-001
---

# AEM-PERF-001 — UnclosedResourceResolver

| Property | Value |
|---|---|
| Severity | ERROR |
| Type | BUG |
| Effort | 30 minutes |
| Tags | performance |

## Description

ResourceResolver obtained via factory is not closed in try-with-resources or finally.

## Remediation

Wrap the resolver in try-with-resources.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-PERF-001
```
