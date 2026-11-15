---
layout: default
title: AEM-DEAD-005
---

# AEM-DEAD-005 — OrphanedClientlibCategory

| Property | Value |
|---|---|
| Severity | INFO |
| Type | CODE_SMELL |
| Effort | 10 minutes |
| Tags | dead-code, clientlib |

## Description

A client library category is never referenced by an HTL/JSP template or another client library.

## Remediation

Reference the category from an HTL/JSP template or another clientlib, or delete the clientlib.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-DEAD-005
```
