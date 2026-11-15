---
layout: default
title: AEM-PERF-004
---

# AEM-PERF-004 — UnboundedQuery

| Property | Value |
|---|---|
| Severity | WARNING |
| Type | CODE_SMELL |
| Effort | 20 minutes |
| Tags | performance, query |

## Description

JCR/Oak query has no limit and may scan an unbounded result set.

## Remediation

Add a result limit (p.limit / query LIMIT) to bound the query.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-PERF-004
```
