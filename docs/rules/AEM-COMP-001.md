---
layout: default
title: AEM-COMP-001
---

# AEM-COMP-001 — DeprecatedClassicUiDialog

| Property | Value |
|---|---|
| Severity | WARNING |
| Type | CODE_SMELL |
| Effort | 20 minutes |
| Tags | quality, classic-ui |

## Description

Component defines deprecated Classic UI dialog.xml instead of Granite UI _cq_dialog.

## Remediation

Migrate dialog.xml to a Granite UI _cq_dialog.

## Configuration

Enable or disable this rule through the project configuration or baseline it with
`--update-baseline`. Inline suppression:

```java
// aem-analyser-disable-next-line AEM-COMP-001
```
