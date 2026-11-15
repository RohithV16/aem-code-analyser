---
layout: default
title: AEM-PERF-003
---

# AEM-PERF-003 — OakQueryTraversalRisk

| Property | Value |
|---|---|
| Severity | WARNING |
| Type | CODE_SMELL |
| Effort | 25 minutes |
| Tags | performance |
| CWE | CWE-400 |

## Description

JCR-SQL2 or XPath query lacks path or nodetype restriction, causing Oak traversal errors.

## Remediation

Add a path or nodetype restriction to the query.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-PERF-003
```
